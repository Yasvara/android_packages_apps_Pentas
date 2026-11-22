/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.telemetry

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.yasvara.pentas.device.DeviceProfile
import org.yasvara.pentas.device.DeviceProfiles
import org.yasvara.pentas.overlay.monitor.ProcessUsage
import org.yasvara.pentas.thermal.KernelNodeIO
import javax.inject.Inject
import javax.inject.Singleton

/** One cluster's live DVFS state. `scalingMax < hwMax` ⇒ a thermal ceiling is in force. */
data class ClusterSnap(
    val name: String,
    val cpuIds: List<Int>,
    val curKhz: Long,        // avg scaling_cur_freq of the cluster
    val scalingMaxKhz: Long, // current ceiling (thermal .ko / governor)
    val hwMaxKhz: Long,      // silicon max (cpuinfo_max_freq)
)

/** Immutable, whole-device telemetry frame. Everything the UI shows lives here. */
data class TelemetrySnapshot(
    val cpuUsageTotal: Int = 0,
    val cpuUsagePerCluster: List<Float> = emptyList(),
    val cpuCoreUsage: List<Float> = emptyList(),     // per logical core, 0..100
    val cpuFreqPerClusterMhz: List<Int> = emptyList(),
    val cpuCoreFreqKhz: List<Int> = emptyList(),     // per logical core
    val cpuFreqMaxKhz: Long = 0,                     // busiest core (panel "x.x GHz" cell)
    val cpuTempC: Float = 0f,                        // max over cpu-typed zones
    val cpuHeat: Float = 0f,                         // 0..1+ vs trip point
    val cpuCeilingCapped: Boolean = false,
    val gpuUsage: Int = 0,
    val gpuFreqMhz: Int = 0,
    val gpuCapHz: Long = 0,                          // live devfreq ceiling, 0 if unreadable
    val gpuTempC: Float = 0f,                        // max over gpu-typed zones
    val gpuHeat: Float = 0f,
    val gpuCeilingCapped: Boolean = false,
    val thermalHeadroomReduced: Boolean = false,     // chip-fact: any ceiling pulled
    val ddrTempC: Float = 0f,
    val batteryTempC: Float = 0f,
    val batteryPowerW: Float = 0f,
    val batteryCapacity: Int = 0,
    val batteryCharging: Boolean = false,
    val ramTotalGb: Float = 0f,
    val ramUsedGb: Float = 0f,
    val ramPct: Float = 0f,
    val swapTotalGb: Float = 0f,
    val swapUsedGb: Float = 0f,
    val gpuUsageHistory: List<Int> = emptyList(),    // 60-sample ring from pantaud
    val cpuClusters: List<ClusterSnap> = emptyList(),
    val net: NetSnapshot = NetSnapshot(),
    val tickMs: Long = 0,
)

/**
 * Feather-light telemetry hub backed by the native Rust `pantaud` daemon (`SharedMemory` seqlock).
 *
 * - **Zero delay on open**: maps `pantaud`'s synchronously primed `memfd` and emits a populated
 *   snapshot immediately (`t = 0 ms`), then refreshes delta metrics after 160 ms.
 * - **Zero `/proc` / `/sys` / `top` overhead in Pentas**: reads a 4 KB `MappedByteBuffer` in ~3 µs
 *   while observed, and drops `setConsumers(0)` when the sidebar closes while keeping the last
 *   warm snapshot so reopening never flashes zeros.
 * - Gracefully falls back to direct sysfs (also zero-delay!) only if `pantaud` is absent.
 */
@Singleton
class TelemetryBus @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pantau: PantauClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _snapshot = MutableStateFlow(TelemetrySnapshot())
    val snapshot: StateFlow<TelemetrySnapshot> = _snapshot.asStateFlow()

    private val _netSnapshot = MutableStateFlow(NetSnapshot())
    val netSnapshot: StateFlow<NetSnapshot> = _netSnapshot.asStateFlow()

    private val _fpsHistory = MutableStateFlow<List<Float>>(emptyList())
    val fpsHistory: StateFlow<List<Float>> = _fpsHistory.asStateFlow()

    private val _processes = MutableStateFlow<List<ProcessUsage>>(emptyList())
    val processes: StateFlow<List<ProcessUsage>> = _processes.asStateFlow()

    val isNativeDaemonInstalled: Boolean get() = pantau.isNativeDaemonInstalled

    @Volatile var usingNative: Boolean = false
        private set
    @Volatile var nativeFpsTaskId: Int = -1
        private set

    fun syncGameTask(taskId: Int, uid: Int) = pantau.syncGameTask(taskId, uid)
    fun clearFpsHistory() { _fpsHistory.value = emptyList() }

    private val lock = Any()
    private var refs = 0
    private var job: Job? = null

    // Fallback state (only touched if `pantaud` service is unavailable).
    private val profile: DeviceProfile by lazy { DeviceProfiles.get(context) }
    private val am by lazy { context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }
    private var statPrimed = false
    private var prevAggTotal = 0L
    private var prevAggIdle = 0L
    private var prevCoreTotal = LongArray(0)
    private var prevCoreIdle = LongArray(0)
    private val gpuHist = ArrayDeque<Int>(GPU_HIST)
    private var cpuTripRef = 0f
    private var gpuTripRef = 0f
    private var tripRefreshIn = 0

    /** Ref-counted start: pulls an instant snapshot immediately when opening Pentas (0 ms delay!). */
    fun start() = synchronized(lock) {
        if (refs++ == 0) {
            // Fast non-blocking read if already mapped so first Compose frame already has live data!
            if (pantau.isConnected) {
                pantau.read()?.let(::publishNativeFrame)
            }
            job = scope.launch { loop() }
        }
    }

    fun stop() = synchronized(lock) {
        if (refs > 0 && --refs == 0) {
            job?.cancel(); job = null
            // Release panel consumer mask in pantaud so it can sleep/exit when idle,
            // while retaining last warm StateFlow values so reopening never flashes blanks.
            if (usingNative) {
                pantau.setConsumers(0L)
            }
        }
    }

    private suspend fun loop() {
        if (pantau.isNativeDaemonInstalled) {
            pantau.ensureConnected(PantauClient.MASK_PANEL_HOME)
            usingNative = true
            nativeLoop()
            return
        }
        if (pantau.ensureConnected(PantauClient.MASK_PANEL_HOME)) {
            usingNative = true
            nativeLoop()
            return
        }
        usingNative = false
        fallbackLoop()
    }

    private suspend fun nativeLoop() {
        var lastSeq = -1
        var lastTickNs = -1L
        var lastFpsHead = -1
        var stalePolls = 0
        // Immediate read #1 (pantaud::snapshotFd synchronously primes Snapshot before returning!).
        val first = pantau.read()
        if (first != null) {
            lastSeq = first.seq
            lastTickNs = first.tickNs
            lastFpsHead = first.fpsHead
            publishNativeFrame(first)
        } else {
            // Resilient live fallback if pantaud is temporarily unavailable: never flash zeros!
            if (!statPrimed) primeStat()
            _snapshot.value = buildSnapshot()
        }
        // Fast 180 ms follow-up picks up pantaud's 150 ms warm-up delta tick (CPU%, Net KB/s, Procs).
        delay(WARMUP_MS)
        while (currentCoroutineContext().isActive) {
            // Fast 0-allocation seqlock header check: skip decoding & object allocation when unchanged!
            val seq = pantau.currentSeq()
            if (seq >= 0 && seq == lastSeq) {
                stalePolls++
                if (stalePolls >= 5) {
                    stalePolls = 0
                    if (!pantau.checkServiceIdentity()) {
                        pantau.ensureConnected(PantauClient.MASK_PANEL_HOME)
                    } else {
                        pantau.setConsumers(PantauClient.MASK_PANEL_HOME)
                    }
                }
                delay(NATIVE_POLL_MS)
                continue
            }
            val frame = pantau.read()
            if (frame == null) {
                lastSeq = -1
                stalePolls = 0
                val reconnected = pantau.ensureConnected(PantauClient.MASK_PANEL_HOME)
                if (!reconnected) {
                    if (!pantau.isNativeDaemonInstalled) {
                        usingNative = false
                        fallbackLoop()
                        return
                    }
                    // Keep Sidebar telemetry alive via direct sysfs while waiting for pantaud.
                    if (!statPrimed) primeStat()
                    _snapshot.value = buildSnapshot()
                    delay(TICK_MS)
                    continue
                }
                pantau.read()?.let { f ->
                    lastSeq = f.seq
                    lastTickNs = f.tickNs
                    lastFpsHead = f.fpsHead
                    publishNativeFrame(f)
                }
            } else if (frame.seq != lastSeq || frame.tickNs != lastTickNs || frame.fpsHead != lastFpsHead) {
                lastSeq = frame.seq
                lastTickNs = frame.tickNs
                lastFpsHead = frame.fpsHead
                stalePolls = 0
                publishNativeFrame(frame)
            }
            delay(NATIVE_POLL_MS)
        }
    }

    private fun publishNativeFrame(f: PantauFrame) {
        nativeFpsTaskId = f.fpsTaskId
        _snapshot.value = f.telemetry
        _netSnapshot.value = f.net
        if (f.fpsHistory.isNotEmpty()) {
            _fpsHistory.value = f.fpsHistory
        } else if (f.currentFps > 0f) {
            _fpsHistory.value = listOf(f.currentFps)
        }
        if (f.processes.isNotEmpty()) {
            _processes.value = f.processes
        }
    }

    // ── Zero-delay fallback loop (only if pantaud daemon is absent) ────────
    private suspend fun fallbackLoop() {
        if (!statPrimed) primeStat()
        // Emit immediately at t = 0 ms (fixes the old 1000 ms blank-screen bug!).
        _snapshot.value = buildSnapshot()
        delay(WARMUP_MS)
        while (currentCoroutineContext().isActive) {
            _snapshot.value = buildSnapshot()
            delay(TICK_MS)
        }
    }

    private fun buildSnapshot(): TelemetrySnapshot {
        val now = android.os.SystemClock.elapsedRealtime()
        val cpu = computeCpu()

        val clusterUsage = mutableListOf<Float>()
        val clusterFreqMhz = mutableListOf<Int>()
        val clusterSnaps = mutableListOf<ClusterSnap>()
        var cpuCeiling = false
        for (c in profile.cpuClusters) {
            val usages = c.cpuIds.map { cpu.perCore.getOrNull(it) ?: 0f }
            clusterUsage += if (usages.isNotEmpty()) usages.average().toFloat() else 0f
            val curKhz = c.cpuIds.map { readKhz(coreCurPath(it)) }.filter { it > 0 }
            val avgCur = if (curKhz.isNotEmpty()) curKhz.average().toLong() else 0L
            val scalingMax = KernelNodeIO.readLong("${c.policyDir}/scaling_max_freq") ?: c.hwMaxKhz
            if (scalingMax in 1 until c.hwMaxKhz - 1_000L) cpuCeiling = true
            clusterFreqMhz += (avgCur / 1000L).toInt()
            clusterSnaps += ClusterSnap(c.name, c.cpuIds, avgCur, scalingMax, c.hwMaxKhz)
        }

        val cpuTemp = maxTemp(profile.cpuTempPaths)
        val gpuTemp = maxTemp(profile.gpuTempPaths)
        val ddrTemp = profile.ddrTempPath?.let { readTempC(it) } ?: 0f

        if (tripRefreshIn <= 0) {
            cpuTripRef = resolveTrip(profile.cpuTempPaths, CPU_TRIP_FALLBACK)
            gpuTripRef = resolveTrip(profile.gpuTempPaths, GPU_TRIP_FALLBACK)
            tripRefreshIn = TRIP_REFRESH_TICKS
        } else tripRefreshIn--
        val cpuHeat = if (cpuTripRef > 0f && cpuTemp > 0f) cpuTemp / cpuTripRef else 0f
        val gpuHeat = if (gpuTripRef > 0f && gpuTemp > 0f) gpuTemp / gpuTripRef else 0f

        val gpu = profile.gpu
        val gpuUsage = readGpuBusy(gpu?.busyPercentPath)
        val gpuFreqMhz = readGpuFreqMhz(gpu)
        val gpuScalingMax = gpu?.devfreqMaxPath?.let { KernelNodeIO.readLong(it) } ?: 0L
        val gpuHwMax = gpuHwMaxHz(gpu)
        val gpuCeiling = gpuScalingMax in 1 until gpuHwMax - 1_000_000L

        val batTemp = profile.batteryTempPath?.let { readTempC(it, deci = true) } ?: 0f
        val curUa = profile.batteryCurrentNowPath?.let { KernelNodeIO.readLong(it) } ?: 0L
        val volUv = profile.batteryVoltageNowPath?.let { KernelNodeIO.readLong(it) } ?: 0L
        val powerW = if (volUv > 0)
            (kotlin.math.abs(curUa.toDouble() * volUv.toDouble()) / 1_000_000_000_000.0).toFloat() else 0f
        val cap = profile.batteryCapacityPath?.let { KernelNodeIO.readInt(it) } ?: 0

        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalGb = mem.totalMem / GIB
        val usedGb = (mem.totalMem - mem.availMem) / GIB
        val mi = meminfoMap()
        val swapTotal = mi["SwapTotal"]?.kbToGb() ?: 0f
        val swapUsed = ((mi["SwapTotal"] ?: 0L) - (mi["SwapFree"] ?: 0L)).coerceAtLeast(0).kbToGb()

        gpuHist.addLast(gpuUsage)
        while (gpuHist.size > GPU_HIST) gpuHist.removeFirst()

        return TelemetrySnapshot(
            cpuUsageTotal = cpu.total,
            cpuUsagePerCluster = clusterUsage,
            cpuCoreUsage = cpu.perCore.toList(),
            cpuFreqPerClusterMhz = clusterFreqMhz,
            cpuCoreFreqKhz = cpu.perCoreFreqKhz.map { it.toInt() },
            cpuFreqMaxKhz = cpu.maxFreqKhz,
            cpuTempC = cpuTemp, cpuHeat = cpuHeat, cpuCeilingCapped = cpuCeiling,
            gpuUsage = gpuUsage, gpuFreqMhz = gpuFreqMhz, gpuCapHz = gpuScalingMax,
            gpuTempC = gpuTemp, gpuHeat = gpuHeat, gpuCeilingCapped = gpuCeiling,
            thermalHeadroomReduced = cpuCeiling || gpuCeiling,
            ddrTempC = ddrTemp,
            batteryTempC = batTemp, batteryPowerW = powerW,
            batteryCapacity = cap, batteryCharging = curUa < 0L,
            ramTotalGb = totalGb, ramUsedGb = usedGb,
            ramPct = if (totalGb > 0f) (usedGb / totalGb * 100f).coerceIn(0f, 100f) else 0f,
            swapTotalGb = swapTotal, swapUsedGb = swapUsed,
            gpuUsageHistory = gpuHist.toList(),
            cpuClusters = clusterSnaps,
            net = _netSnapshot.value,
            tickMs = now,
        )
    }

    private data class CpuStat(
        val total: Int, val perCore: FloatArray,
        val perCoreFreqKhz: LongArray, val maxFreqKhz: Long,
    )

    private fun primeStat() {
        val parsed = parseStat() ?: return
        prevAggTotal = parsed.aggTotal; prevAggIdle = parsed.aggIdle
        prevCoreTotal = parsed.coreTotal; prevCoreIdle = parsed.coreIdle
        statPrimed = true
    }

    private fun computeCpu(): CpuStat {
        val p = parseStat()
        val nCores = p?.coreTotal?.size ?: 0
        val perCore = FloatArray(nCores)
        if (statPrimed && p != null) {
            val dT = p.aggTotal - prevAggTotal
            val dI = p.aggIdle - prevAggIdle
            val total = if (dT > 0) ((dT - dI) * 100 / dT).toInt().coerceIn(0, 100) else 0
            for (i in 0 until nCores) {
                val dt = p.coreTotal[i] - prevCoreTotal.getOrElse(i) { p.coreTotal[i] }
                val di = p.coreIdle[i] - prevCoreIdle.getOrElse(i) { p.coreIdle[i] }
                perCore[i] = if (dt > 0) ((dt - di) * 100f / dt).coerceIn(0f, 100f) else 0f
            }
            prevAggTotal = p.aggTotal; prevAggIdle = p.aggIdle
            prevCoreTotal = p.coreTotal; prevCoreIdle = p.coreIdle
            val freqs = LongArray(nCores) { readKhz(coreCurPath(it)) }
            return CpuStat(total, perCore, freqs, freqs.maxOrNull() ?: 0L)
        }
        statPrimed = true
        if (p != null) {
            prevAggTotal = p.aggTotal; prevAggIdle = p.aggIdle
            prevCoreTotal = p.coreTotal; prevCoreIdle = p.coreIdle
        }
        val freqs = LongArray(nCores) { readKhz(coreCurPath(it)) }
        return CpuStat(0, perCore, freqs, freqs.maxOrNull() ?: 0L)
    }

    private data class ParsedStat(
        val aggTotal: Long, val aggIdle: Long,
        val coreTotal: LongArray, val coreIdle: LongArray,
    )

    private fun parseStat(): ParsedStat? {
        val text = KernelNodeIO.read("/proc/stat") ?: return null
        var aggT = 0L; var aggI = 0L; var haveAgg = false
        val cT = LongArray(MAX_CORES); val cI = LongArray(MAX_CORES); var top = -1
        for (line in text.lineSequence()) {
            if (!line.startsWith("cpu")) continue
            val parts = line.split(WHITESPACE)
            if (parts.size < 8) continue
            val v0 = parts[1].toLongOrNull() ?: 0L
            val v1 = parts[2].toLongOrNull() ?: 0L
            val v2 = parts[3].toLongOrNull() ?: 0L
            val v3 = parts[4].toLongOrNull() ?: 0L
            val v4 = parts[5].toLongOrNull() ?: 0L
            val v5 = parts[6].toLongOrNull() ?: 0L
            val v6 = parts[7].toLongOrNull() ?: 0L
            val total = v0 + v1 + v2 + v3 + v4 + v5 + v6 + (parts.getOrNull(8)?.toLongOrNull() ?: 0L)
            val idle = v3 + v4
            if (parts[0] == "cpu") { aggT = total; aggI = idle; haveAgg = true }
            else {
                val idx = parts[0].removePrefix("cpu").toIntOrNull() ?: continue
                if (idx in 0 until MAX_CORES) { cT[idx] = total; cI[idx] = idle; if (idx > top) top = idx }
            }
        }
        if (!haveAgg) return null
        val n = (top + 1).coerceAtLeast(0)
        return ParsedStat(aggT, aggI, cT.copyOf(n), cI.copyOf(n))
    }

    private fun readKhz(path: String): Long = KernelNodeIO.readLong(path) ?: 0L
    private fun readTempC(path: String, deci: Boolean = false): Float {
        val raw = KernelNodeIO.readLong(path) ?: return 0f
        return if (deci && !path.contains("thermal_zone")) raw / 10f else if (raw > 1000L) raw / 1000f else raw / 10f
    }
    private fun maxTemp(paths: List<String>): Float {
        var m = 0f
        for (p in paths) { val t = readTempC(p); if (t > m) m = t }
        return m
    }
    private fun readGpuBusy(path: String?): Int {
        if (path == null) return 0
        val t = KernelNodeIO.read(path)?.trim() ?: return 0
        if (t.contains('%')) return t.substringBefore('%').trim().toIntOrNull()?.coerceIn(0, 100) ?: 0
        val ps = t.split(WHITESPACE).filter { it.isNotEmpty() }
        return if (ps.size >= 2) {
            val b = ps[0].toLongOrNull() ?: 0L
            val tot = ps[1].toLongOrNull() ?: 1L
            ((b * 100) / tot.coerceAtLeast(1)).toInt().coerceIn(0, 100)
        } else ps.firstOrNull()?.toIntOrNull()?.coerceIn(0, 100) ?: 0
    }
    private fun readGpuFreqMhz(gpu: org.yasvara.pentas.device.GpuSpec?): Int {
        if (gpu == null) return 0
        for (p in gpu.curFreqPaths) {
            val raw = KernelNodeIO.readLong(p) ?: continue
            if (raw <= 0) continue
            return when {
                raw > 1_000_000L -> (raw / 1_000_000L).toInt()
                raw > 1_000L -> (raw / 1_000L).toInt()
                else -> raw.toInt()
            }
        }
        return 0
    }
    private var gpuHwMaxCache = -1L
    private fun gpuHwMaxHz(gpu: org.yasvara.pentas.device.GpuSpec?): Long {
        if (gpu == null) return 0L
        if (gpuHwMaxCache > 0) return gpuHwMaxCache
        val list = gpu.availableFreqsPath?.let { KernelNodeIO.readLongList(it) }.orEmpty()
        gpuHwMaxCache = list.maxOrNull() ?: 0L
        return gpuHwMaxCache
    }
    private fun meminfoMap(): Map<String, Long> = runCatching {
        (KernelNodeIO.read("/proc/meminfo") ?: "").lineSequence().mapNotNull { l ->
            val c = l.indexOf(':'); if (c < 0) null else
                l.substring(0, c) to (l.substring(c + 1).trim().removeSuffix(" kB").toLongOrNull() ?: 0L)
        }.toMap()
    }.getOrDefault(emptyMap())
    private fun Long.kbToGb(): Float = this / 1_048_576f

    private fun resolveTrip(paths: List<String>, fallback: Float): Float {
        var best = 0f
        for (p in paths) {
            val zone = p.substringAfterLast("thermal_zone").substringBefore("/")
            for (n in 0..3) {
                val tp = "/sys/class/thermal/thermal_zone$zone/trip_point_${n}_temp"
                if (!KernelNodeIO.exists(tp)) continue
                val v = (KernelNodeIO.readLong(tp) ?: 0L) / 1000f
                if (v > best) best = v
            }
        }
        return if (best > 1f) best else fallback
    }

    private fun coreCurPath(i: Int) = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq"

    companion object {
        private const val TICK_MS = 1000L
        private const val WARMUP_MS = 180L
        private const val NATIVE_POLL_MS = 500L
        private const val MAX_CORES = 16
        private const val GPU_HIST = 30
        private const val TRIP_REFRESH_TICKS = 30
        private const val CPU_TRIP_FALLBACK = 95f
        private const val GPU_TRIP_FALLBACK = 95f
        private const val GIB = 1_073_741_824f
        private val WHITESPACE = Regex("\\s+")
    }
}
