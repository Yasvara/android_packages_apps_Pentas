/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.telemetry

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.Binder
import android.os.IBinder
import android.os.IServiceManager
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Service
import android.os.ServiceManager
import android.os.SystemClock
import android.util.Log
import com.android.internal.os.BinderInternal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.yasvara.pentas.overlay.monitor.ProcessUsage
import yasvara.pantau.IPantau
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** Unified Wi-Fi + Mobile Data + passive kernel TCP RTT snapshot read from `pantaud` memfd. */
data class NetSnapshot(
    val rxBps: Long = 0L,
    val txBps: Long = 0L,
    val wifiRxBps: Long = 0L,
    val wifiTxBps: Long = 0L,
    val cellRxBps: Long = 0L,
    val cellTxBps: Long = 0L,
    val wifiSessionKb: Long = 0L,
    val cellSessionKb: Long = 0L,
    val rttMs: Int = 0,
    val jitterMs: Int = 0,
    val retransPerSec: Int = 0,
    val wifiLinkMbps: Int = 0,
    val wifiFreqMhz: Int = 0,
    val wifiRssiDbm: Int = 0,
    val cellRsrpDbm: Int = 0,
    val cellLevel: Int = 0,
    val cellGen: Int = 0,      // 0=none, 2=2G, 3=3G, 4=4G, 5=5G, 6=5G+
    val wifiStandard: Int = 0, // 0=unknown, 4..7 = Wi-Fi 4..7
    val linkMask: Int = 0,     // bit0=wifi, bit1=cell, bit2=eth, bit3=vpn
    val wifiRxHistKbps: List<Int> = emptyList(),
    val cellRxHistKbps: List<Int> = emptyList(),
    val txHistKbps: List<Int> = emptyList(),
    val rttHistMs: List<Int> = emptyList(),
) {
    val hasWifi: Boolean get() = (linkMask and 1) != 0
    val hasCell: Boolean get() = (linkMask and 2) != 0
    val hasEth: Boolean get() = (linkMask and 4) != 0
    val hasVpn: Boolean get() = (linkMask and 8) != 0
    val rxKbps: Float get() = rxBps / 1024f
    val txKbps: Float get() = txBps / 1024f
}

/** Complete zero-syscall frame decoded lock-free from `pantaud`'s sealed shared-memory memfd. */
data class PantauFrame(
    val telemetry: TelemetrySnapshot,
    val net: NetSnapshot,
    val fpsHistory: List<Float>,
    val currentFps: Float,
    val fpsTaskId: Int,
    val fpsHead: Int,
    val processes: List<ProcessUsage>,
    val tickNs: Long,
    val seq: Int = 0,
)

/**
 * Lock-free client for the native Rust `pantaud` daemon (`yasvara.pantau`).
 *
 * Maps the sealed read-only `Snapshot` memfd once and reads all telemetry, unified Wi-Fi/Mobile
 * network graphs, SF FPS history, and cgroup processes via seqlock in ~3 µs with zero `/proc`,
 * zero `/sys`, and zero `top` subprocess forks.
 */
@Singleton
class PantauClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val lock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var service: IPantau? = null
    @Volatile private var mappedBinder: IBinder? = null
    @Volatile private var mapped: MappedByteBuffer? = null
    @Volatile private var lastWakeFailMs: Long = 0L
    private var mappedPfd: ParcelFileDescriptor? = null
    private val desiredConsumers = AtomicLong(0L)
    private val uidPkgCache = ConcurrentHashMap<Int, String>()
    private val pushedUidMeta = ConcurrentHashMap.newKeySet<Int>()
    private val nameBytesBuf = ByteArray(PantauLayout.PROC_NAME_BYTES)

    /**
     * True whenever the native Rust `pantaud` daemon is installed on this ROM.
     * Checks `system_file`-labeled Pantau assets (`/system_ext/etc/pantau/atlas.bin` and
     * `pantaud.rc`) instead of `/system_ext/bin/pantaud` (`pantaud_exec`), which `system_app`
     * cannot stat under enforcing SELinux.
     */
    val isNativeDaemonInstalled: Boolean by lazy {
        File("/system_ext/etc/pantau/atlas.bin").exists() ||
            File("/system_ext/etc/init/pantaud.rc").exists() ||
            ServiceManager.checkService(SERVICE_NAME) != null
    }

    val isConnected: Boolean
        get() {
            val b = service?.asBinder() ?: return false
            return b.isBinderAlive && b === mappedBinder && mapped != null
        }

    /**
     * Connects lazily to `pantaud` and maps its sealed read-only Snapshot memfd (sync-primed).
     * Never blocks the Main Thread and bounds lazy `waitForService` to 1.5 s so Pentas can
     * NEVER ANR even if `pantaud` is absent or restarting.
     */
    fun ensureConnected(consumers: Long = 0L): Boolean {
        if (consumers != 0L) desiredConsumers.set(consumers)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Never perform blocking Binder wake on the UI thread!
            return isConnected
        }
        return synchronized(lock) {
            val cur = service
            val curBinder = cur?.asBinder()
            val liveBinder = ServiceManager.checkService(SERVICE_NAME)
            if (cur != null && curBinder != null && curBinder.isBinderAlive &&
                mapped != null && curBinder === mappedBinder &&
                (liveBinder == null || liveBinder === curBinder)
            ) {
                val want = desiredConsumers.get()
                if (want != 0L) runCatching { cur.setConsumers(want) }
                return@synchronized true
            }
            closeMappingLocked()
            try {
                val binder: IBinder = liveBinder
                    ?: wakeServiceBounded(WAKE_TIMEOUT_MS)
                    ?: return@synchronized false
                val iface = IPantau.Stub.asInterface(binder)
                val want = desiredConsumers.get()
                if (want != 0L) {
                    iface.setConsumers(want)
                }
                val pfd = iface.snapshotFd() ?: return@synchronized false
                val buf = FileInputStream(pfd.fileDescriptor).channel
                    .map(FileChannel.MapMode.READ_ONLY, 0L, PantauLayout.SNAP_SIZE.toLong())
                buf.order(ByteOrder.LITTLE_ENDIAN)
                if (buf.getInt(PantauLayout.HDR_MAGIC) != PantauLayout.MAGIC ||
                    (buf.getShort(PantauLayout.HDR_VERSION).toInt() and 0xffff) != PantauLayout.VERSION
                ) {
                    Log.w(TAG, "Snapshot ABI mismatch (expected v${PantauLayout.VERSION})")
                    runCatching { pfd.close() }
                    return@synchronized false
                }
                service = iface
                mappedBinder = binder
                mappedPfd = pfd
                mapped = buf
                true
            } catch (t: Throwable) {
                Log.w(TAG, "pantaud connect failed, retrying later", t)
                closeMappingLocked()
                false
            }
        }
    }

    /**
     * Checks that our mapped memfd still belongs to the live `yasvara.pantau` daemon instance.
     * Drops any stale zombie mapping immediately if `pantaud` restarted.
     */
    fun checkServiceIdentity(): Boolean {
        val cur = mappedBinder ?: return false
        if (!cur.isBinderAlive) {
            synchronized(lock) { closeMappingLocked() }
            return false
        }
        val live = ServiceManager.checkService(SERVICE_NAME)
        if (live != null && live !== cur) {
            synchronized(lock) { closeMappingLocked() }
            return false
        }
        return true
    }

    /**
     * Sends a single non-blocking `IServiceManager.getService2` RPC directly to `servicemanager`
     * (handle 0), bypassing Java `ServiceManagerProxy`'s `checkService2` redirect so lazy AIDL
     * startup (`ctl.interface_start`) triggers immediately without calling infinite `waitForService`.
     */
    private fun triggerLazyStart(): IBinder? = runCatching {
        val sm = IServiceManager.Stub.asInterface(
            Binder.allowBlocking(BinderInternal.getContextObject()),
        ) ?: return@runCatching null
        val svc = sm.getService2(SERVICE_NAME) ?: return@runCatching null
        if (svc.tag != Service.serviceWithMetadata) return@runCatching null
        svc.serviceWithMetadata?.service?.let { Binder.allowBlocking(it) }
    }.getOrNull()

    /**
     * Triggers lazy startup of `yasvara.pantau` via a single non-blocking `getService2()` RPC
     * and polls `checkService()` up to [timeoutMs]. Never calls infinite `waitForService()`,
     * preventing leaked wake threads and `ctl.interface_start` spam if the daemon is unavailable.
     */
    private fun wakeServiceBounded(timeoutMs: Long): IBinder? {
        val now = SystemClock.uptimeMillis()
        val lastFail = lastWakeFailMs
        if (lastFail > 0L && now - lastFail < WAKE_BACKOFF_MS) {
            return ServiceManager.checkService(SERVICE_NAME)?.also { lastWakeFailMs = 0L }
        }
        var binder = triggerLazyStart()
        if (binder != null) {
            lastWakeFailMs = 0L
            return binder
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(30L)
            binder = ServiceManager.checkService(SERVICE_NAME)
            if (binder != null) {
                lastWakeFailMs = 0L
                return binder
            }
        }
        lastWakeFailMs = SystemClock.uptimeMillis()
        return null
    }

    fun setConsumers(mask: Long) {
        desiredConsumers.set(mask)
        ioScope.launch {
            val target = desiredConsumers.get()
            val s = service?.takeIf { it.asBinder().isBinderAlive && it.asBinder() === mappedBinder }
                ?: if (target != 0L && ensureConnected(target)) service else null
            runCatching { s?.setConsumers(desiredConsumers.get()) }
        }
    }

    fun configureHud(profileRaw: String) {
        ioScope.launch {
            val s = service?.takeIf { it.asBinder().isBinderAlive }
                ?: ServiceManager.checkService(SERVICE_NAME)?.let { IPantau.Stub.asInterface(it) }
                ?: return@launch
            runCatching { s.configure(profileRaw) }
        }
    }

    fun moveWidget(widgetId: Int, x: Int, y: Int) {
        // Never overwrite `service` without remapping `mapped`! Use local binder lookup if needed.
        val s = service?.takeIf { it.asBinder().isBinderAlive }
            ?: ServiceManager.checkService(SERVICE_NAME)?.let { IPantau.Stub.asInterface(it) }
            ?: return
        runCatching { s.moveWidget(widgetId, x, y) }
    }

    fun pushHudPin(pin: Boolean) {
        ioScope.launch {
            val s = service?.takeIf { it.asBinder().isBinderAlive }
                ?: if (pin && ensureConnected(0L)) service else null
            runCatching { s?.event(8 /* HudPin */, if (pin) 1L else 0L, 0L) }
        }
    }

    /** Ensures `pantaud`'s SurfaceFlinger FPS listener tracks the active fullscreen game task. */
    fun syncGameTask(taskId: Int, uid: Int) {
        if (taskId < 0) return
        ioScope.launch {
            val s = service?.takeIf { it.asBinder().isBinderAlive }
                ?: if (ensureConnected(0L)) service else null
            runCatching { s?.session(taskId, uid, true) }
        }
    }

    /**
     * Zero-allocation seqlock header check (~5 ns). Returns the current even sequence number, or
     * `-1` if disconnected / mid-write so callers can skip `read()` when the frame hasn't changed.
     */
    fun currentSeq(): Int {
        val buf = mapped ?: return -1
        val b = service?.asBinder()
        if (b == null || !b.isBinderAlive || b !== mappedBinder) {
            synchronized(lock) { closeMappingLocked() }
            return -1
        }
        val s = runCatching { buf.getInt(PantauLayout.HDR_SEQ) }.getOrDefault(-1)
        return if (s >= 0 && (s and 1) == 0) s else -1
    }

    /** Lock-free seqlock read from `pantaud`'s sealed memfd (~3 µs, 0 syscalls). */
    fun read(): PantauFrame? {
        val buf = mapped ?: return null
        val b = service?.asBinder()
        if (b == null || !b.isBinderAlive || b !== mappedBinder) {
            synchronized(lock) { closeMappingLocked() }
            return null
        }
        return runCatching { readBuffer(buf) }.getOrNull()
    }

    private fun readBuffer(buf: MappedByteBuffer): PantauFrame? {
        repeat(6) {
            val s1 = buf.getInt(PantauLayout.HDR_SEQ)
            if ((s1 and 1) != 0) return@repeat

            val tickNs = buf.getLong(PantauLayout.HDR_TICK_NS)
            val numCores = (buf.get(PantauLayout.HDR_NUM_CORES).toInt() and 0xff)
                .coerceIn(0, PantauLayout.MAX_CORES)
            val numClusters = (buf.get(PantauLayout.HDR_NUM_CLUSTERS).toInt() and 0xff)
                .coerceIn(0, PantauLayout.MAX_CLUSTERS)

            // CPU
            val cpuTotal = (buf.getShort(PantauLayout.OFF_CPU_TOTAL).toInt() and 0xffff) / 10
            val cpuCeil = buf.get(PantauLayout.OFF_CPU_CEILING).toInt() != 0
            val cpuMaxKhz = buf.getInt(PantauLayout.OFF_CPU_MAX_KHZ).toLong() and 0xffffffffL

            val coreUtil = ArrayList<Float>(numCores)
            val coreFreq = ArrayList<Int>(numCores)
            for (i in 0 until numCores) {
                val u = buf.getShort(PantauLayout.OFF_CPU_CORE_UTIL + i * 2).toInt() and 0xffff
                coreUtil.add(u / 10f)
                coreFreq.add(buf.getInt(PantauLayout.OFF_CPU_CORE_KHZ + i * 4))
            }

            val clusters = ArrayList<ClusterSnap>(numClusters)
            val clusterUtil = ArrayList<Float>(numClusters)
            val clusterFreqMhz = ArrayList<Int>(numClusters)
            for (ci in 0 until numClusters) {
                val base = PantauLayout.OFF_CPU_CLUSTERS + ci * PantauLayout.SIZE_CLUSTER_SNAP
                val mask = buf.getInt(base)
                val curKhz = buf.getInt(base + 4).toLong() and 0xffffffffL
                val smaxKhz = buf.getInt(base + 8).toLong() and 0xffffffffL
                val hwMaxKhz = buf.getInt(base + 12).toLong() and 0xffffffffL
                val uPm = buf.getShort(base + 16).toInt() and 0xffff
                val ids = ArrayList<Int>(4)
                for (bit in 0 until PantauLayout.MAX_CORES) {
                    if ((mask and (1 shl bit)) != 0) ids.add(bit)
                }
                val cname = clusterName(ci, numClusters)
                clusters.add(ClusterSnap(cname, ids, curKhz, smaxKhz, hwMaxKhz))
                clusterUtil.add(uPm / 10f)
                clusterFreqMhz.add((curKhz / 1000L).toInt())
            }

            // GPU
            val gpuBusy = (buf.getShort(PantauLayout.OFF_GPU_BUSY).toInt() and 0xffff) / 10
            val gpuCurMhz = buf.getShort(PantauLayout.OFF_GPU_CUR_MHZ).toInt() and 0xffff
            val gpuAvgMhz = buf.getShort(PantauLayout.OFF_GPU_AVG_MHZ).toInt() and 0xffff
            val gpuCapMhz = buf.getShort(PantauLayout.OFF_GPU_CAP_MHZ).toInt() and 0xffff
            val gpuCeil = buf.get(PantauLayout.OFF_GPU_CEILING).toInt() != 0
            val gpuMhz = if (gpuAvgMhz > 0) gpuAvgMhz else gpuCurMhz

            // Thermal
            val cpuTemp = buf.getShort(PantauLayout.OFF_THERM_CPU) / 10f
            val gpuTemp = buf.getShort(PantauLayout.OFF_THERM_GPU) / 10f
            val ddrTemp = buf.getShort(PantauLayout.OFF_THERM_DDR) / 10f
            val batTemp = buf.getShort(PantauLayout.OFF_THERM_BAT) / 10f
            val cpuHeat = (buf.getShort(PantauLayout.OFF_THERM_CPU_HEAT).toInt() and 0xffff) / 1000f
            val gpuHeat = (buf.getShort(PantauLayout.OFF_THERM_GPU_HEAT).toInt() and 0xffff) / 1000f
            val headroomReduced = buf.get(PantauLayout.OFF_THERM_REDUCED).toInt() != 0

            // Memory
            val memTotalKb = buf.getInt(PantauLayout.OFF_MEM_TOTAL_KB).toLong() and 0xffffffffL
            val memUsedKb = buf.getInt(PantauLayout.OFF_MEM_USED_KB).toLong() and 0xffffffffL
            val swapTotKb = buf.getInt(PantauLayout.OFF_MEM_SWAP_TOT_KB).toLong() and 0xffffffffL
            val swapUsedKb = buf.getInt(PantauLayout.OFF_MEM_SWAP_USED_KB).toLong() and 0xffffffffL
            val memUsedPm = buf.getShort(PantauLayout.OFF_MEM_USED_PM).toInt() and 0xffff

            // Power
            val pwrMw = buf.getInt(PantauLayout.OFF_PWR_MW).toLong() and 0xffffffffL
            val capPct = buf.get(PantauLayout.OFF_PWR_CAP).toInt() and 0xff
            val charging = buf.get(PantauLayout.OFF_PWR_CHG).toInt() != 0

            // Network
            val rxBps = buf.getInt(PantauLayout.OFF_NET_RX_BPS).toLong() and 0xffffffffL
            val txBps = buf.getInt(PantauLayout.OFF_NET_TX_BPS).toLong() and 0xffffffffL
            val wifiRxBps = buf.getInt(PantauLayout.OFF_NET_WIFI_RX_BPS).toLong() and 0xffffffffL
            val wifiTxBps = buf.getInt(PantauLayout.OFF_NET_WIFI_TX_BPS).toLong() and 0xffffffffL
            val cellRxBps = buf.getInt(PantauLayout.OFF_NET_CELL_RX_BPS).toLong() and 0xffffffffL
            val cellTxBps = buf.getInt(PantauLayout.OFF_NET_CELL_TX_BPS).toLong() and 0xffffffffL
            val wifiSessKb = buf.getInt(PantauLayout.OFF_NET_WIFI_SESS_KB).toLong() and 0xffffffffL
            val cellSessKb = buf.getInt(PantauLayout.OFF_NET_CELL_SESS_KB).toLong() and 0xffffffffL
            val rttUs = buf.getInt(PantauLayout.OFF_NET_RTT_US).toLong() and 0xffffffffL
            val rttVarUs = buf.getInt(PantauLayout.OFF_NET_RTTVAR_US).toLong() and 0xffffffffL
            val retransS = buf.getShort(PantauLayout.OFF_NET_RETRANS_S).toInt() and 0xffff
            val wifiMbps = buf.getShort(PantauLayout.OFF_NET_WIFI_MBPS).toInt() and 0xffff
            val wifiFreq = buf.getShort(PantauLayout.OFF_NET_WIFI_FREQ).toInt() and 0xffff
            val wifiRssi = buf.getShort(PantauLayout.OFF_NET_WIFI_RSSI).toInt()
            val cellRsrp = buf.getShort(PantauLayout.OFF_NET_CELL_RSRP).toInt()
            val cellLvl = buf.get(PantauLayout.OFF_NET_CELL_LVL).toInt() and 0xff
            val cellGen = buf.get(PantauLayout.OFF_NET_CELL_GEN).toInt() and 0xff
            val wifiStd = buf.get(PantauLayout.OFF_NET_WIFI_STD).toInt() and 0xff
            val linkMask = buf.get(PantauLayout.OFF_NET_LINK_TYPE).toInt() and 0xff

            // FPS
            val curFps = (buf.getShort(PantauLayout.OFF_FPS_CUR_X100).toInt() and 0xffff) / 100f
            val fpsTaskId = buf.getInt(PantauLayout.OFF_FPS + 8)

            // History rings
            val fpsHead = (buf.getShort(PantauLayout.OFF_HIST_FPS_HEAD).toInt() and 0xffff)
                .coerceIn(0, PantauLayout.FPS_HIST_LEN - 1)
            val fpsLen = (buf.getShort(PantauLayout.OFF_HIST_FPS_LEN).toInt() and 0xffff)
                .coerceIn(0, PantauLayout.FPS_HIST_LEN)
            val fpsHist = ArrayList<Float>(fpsLen)
            for (i in 0 until fpsLen) {
                val ri = (fpsHead + PantauLayout.FPS_HIST_LEN - fpsLen + i) % PantauLayout.FPS_HIST_LEN
                fpsHist.add((buf.getShort(PantauLayout.OFF_HIST_FPS + ri * 2).toInt() and 0xffff) / 100f)
            }

            val mHead = (buf.getShort(PantauLayout.OFF_HIST_METRIC_HEAD).toInt() and 0xffff)
                .coerceIn(0, PantauLayout.METRIC_HIST_LEN - 1)
            val mLen = (buf.getShort(PantauLayout.OFF_HIST_METRIC_LEN).toInt() and 0xffff)
                .coerceIn(0, PantauLayout.METRIC_HIST_LEN)
            val gpuHist = ArrayList<Int>(mLen)
            for (i in 0 until mLen) {
                val ri = (mHead + PantauLayout.METRIC_HIST_LEN - mLen + i) % PantauLayout.METRIC_HIST_LEN
                gpuHist.add(buf.get(PantauLayout.OFF_HIST_GPU_PCT + ri).toInt() and 0xff)
            }

            val nHead = (buf.getShort(PantauLayout.OFF_HIST_NET_HEAD).toInt() and 0xffff)
                .coerceIn(0, PantauLayout.METRIC_HIST_LEN - 1)
            val nLen = (buf.getShort(PantauLayout.OFF_HIST_NET_LEN).toInt() and 0xffff)
                .coerceIn(0, PantauLayout.METRIC_HIST_LEN)
            val wifiHist = ArrayList<Int>(nLen)
            val cellHist = ArrayList<Int>(nLen)
            val txHist = ArrayList<Int>(nLen)
            val rttHist = ArrayList<Int>(nLen)
            for (i in 0 until nLen) {
                val ri = (nHead + PantauLayout.METRIC_HIST_LEN - nLen + i) % PantauLayout.METRIC_HIST_LEN
                wifiHist.add(buf.getInt(PantauLayout.OFF_HIST_NET_WIFI_RX + ri * 4).coerceAtLeast(0))
                cellHist.add(buf.getInt(PantauLayout.OFF_HIST_NET_CELL_RX + ri * 4).coerceAtLeast(0))
                txHist.add(buf.getInt(PantauLayout.OFF_HIST_NET_TX + ri * 4).coerceAtLeast(0))
                rttHist.add(buf.getShort(PantauLayout.OFF_HIST_NET_RTT + ri * 2).toInt() and 0xffff)
            }

            // Processes (Rust cgroup scanner — zero `top` forks!)
            val procCount = (buf.getShort(PantauLayout.OFF_PROCS_COUNT).toInt() and 0xffff)
                .coerceIn(0, PantauLayout.MAX_PROCS)
            val procs = ArrayList<ProcessUsage>(procCount)
            if (procCount > 0) {
                for (i in 0 until PantauLayout.PROC_NAME_BYTES) {
                    nameBytesBuf[i] = buf.get(PantauLayout.OFF_PROCS_NAMES + i)
                }
                for (i in 0 until procCount) {
                    val rb = PantauLayout.OFF_PROCS_ROWS + i * PantauLayout.SIZE_PROC_ROW
                    val uid = buf.getInt(rb)
                    val cpuPm = buf.getShort(rb + 8).toInt() and 0xffff
                    val nameOff = (buf.getShort(rb + 10).toInt() and 0xffff)
                        .coerceIn(0, PantauLayout.PROC_NAME_BYTES)
                    val nameLen = (buf.get(rb + 12).toInt() and 0xff)
                        .coerceIn(0, PantauLayout.PROC_NAME_BYTES - nameOff)
                    val kind = buf.get(rb + 13).toInt() and 0xff
                    val rawName = String(nameBytesBuf, nameOff, nameLen, Charsets.UTF_8)
                    val resolved = if (kind != 2 && (uid >= 10000 || rawName.startsWith("uid_"))) {
                        resolveUidPkg(uid, rawName)
                    } else rawName
                    if (kind != 2 && uid >= 10000 && pushedUidMeta.add(uid)) {
                        pushUidIconAsync(uid, resolved)
                    }
                    procs.add(ProcessUsage(resolved, cpuPm / 10f, uid = uid, kind = kind))
                }
            }

            val s2 = buf.getInt(PantauLayout.HDR_SEQ)
            if (s1 == s2) {
                val net = NetSnapshot(
                    rxBps = rxBps, txBps = txBps,
                    wifiRxBps = wifiRxBps, wifiTxBps = wifiTxBps,
                    cellRxBps = cellRxBps, cellTxBps = cellTxBps,
                    wifiSessionKb = wifiSessKb, cellSessionKb = cellSessKb,
                    rttMs = (rttUs / 1000L).toInt(),
                    jitterMs = (rttVarUs / 1000L).toInt(),
                    retransPerSec = retransS,
                    wifiLinkMbps = wifiMbps, wifiFreqMhz = wifiFreq,
                    wifiRssiDbm = wifiRssi, cellRsrpDbm = cellRsrp,
                    cellLevel = cellLvl, cellGen = cellGen,
                    wifiStandard = wifiStd, linkMask = linkMask,
                    wifiRxHistKbps = wifiHist, cellRxHistKbps = cellHist,
                    txHistKbps = txHist, rttHistMs = rttHist,
                )
                val snap = TelemetrySnapshot(
                    cpuUsageTotal = cpuTotal,
                    cpuUsagePerCluster = clusterUtil,
                    cpuCoreUsage = coreUtil,
                    cpuFreqPerClusterMhz = clusterFreqMhz,
                    cpuCoreFreqKhz = coreFreq,
                    cpuFreqMaxKhz = cpuMaxKhz,
                    cpuTempC = cpuTemp,
                    cpuHeat = cpuHeat,
                    cpuCeilingCapped = cpuCeil,
                    gpuUsage = gpuBusy,
                    gpuFreqMhz = gpuMhz,
                    gpuCapHz = gpuCapMhz.toLong() * 1_000_000L,
                    gpuTempC = gpuTemp,
                    gpuHeat = gpuHeat,
                    gpuCeilingCapped = gpuCeil,
                    thermalHeadroomReduced = headroomReduced || cpuCeil || gpuCeil,
                    ddrTempC = ddrTemp,
                    batteryTempC = batTemp,
                    batteryPowerW = abs(pwrMw) / 1000f,
                    batteryCapacity = capPct,
                    batteryCharging = charging,
                    ramTotalGb = memTotalKb / 1_048_576f,
                    ramUsedGb = memUsedKb / 1_048_576f,
                    ramPct = memUsedPm / 10f,
                    swapTotalGb = swapTotKb / 1_048_576f,
                    swapUsedGb = swapUsedKb / 1_048_576f,
                    gpuUsageHistory = gpuHist,
                    cpuClusters = clusters,
                    net = net,
                    tickMs = tickNs / 1_000_000L,
                )
                return PantauFrame(snap, net, fpsHist, curFps, fpsTaskId, fpsHead, procs, tickNs, s1)
            }
        }
        return null
    }

    private fun resolveUidPkg(uid: Int, fallback: String): String {
        uidPkgCache[uid]?.let { return it }
        val pm = context.packageManager
        val pkgs = runCatching { pm.getPackagesForUid(uid) }.getOrNull()
        val chosen = pkgs?.firstOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { pm.getNameForUid(uid) }.getOrNull()
                ?.substringBefore(':')?.takeIf { it.isNotEmpty() }
            ?: fallback
        if (uidPkgCache.size > 128) uidPkgCache.clear()
        uidPkgCache[uid] = chosen
        return chosen
    }

    private fun pushUidIconAsync(uid: Int, pkg: String) {
        if (pkg.startsWith("uid_")) return
        ioScope.launch {
            val s = service?.takeIf { it.asBinder().isBinderAlive } ?: return@launch
            runCatching {
                val pm = context.packageManager
                val ai = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(ai)?.toString()?.takeIf { it.isNotEmpty() } ?: pkg
                val d = pm.getApplicationIcon(ai)
                val dim = 28
                val bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val clip = Path().apply { addRoundRect(RectF(0f, 0f, dim.toFloat(), dim.toFloat()), 7f, 7f, Path.Direction.CW) }
                c.clipPath(clip)
                d.setBounds(0, 0, dim, dim)
                d.draw(c)
                val bb = ByteBuffer.allocate(dim * dim * 4)
                bmp.copyPixelsToBuffer(bb)
                bmp.recycle()
                s.pushUidMeta(uid, label, bb.array())
            }
        }
    }

    private fun closeMappingLocked() {
        mapped = null
        mappedBinder = null
        runCatching { mappedPfd?.close() }
        mappedPfd = null
        service = null
        pushedUidMeta.clear()
    }

    companion object {
        private const val TAG = "PantauClient"
        private const val SERVICE_NAME = "yasvara.pantau"
        private const val WAKE_TIMEOUT_MS = 1200L
        private const val WAKE_BACKOFF_MS = 5000L

        // Matches Rust `schema::mask::PANEL_HOME`
        const val MASK_PANEL_HOME: Long =
            (1L shl 0) or (1L shl 1) or (1L shl 2) or (1L shl 3) or
            (1L shl 4) or (1L shl 5) or (1L shl 6) or (1L shl 7) or (1L shl 9)

        private fun clusterName(idx: Int, total: Int): String = when {
            total <= 1 -> "CPU"
            idx == 0 -> "Little"
            idx == total - 1 -> "Prime"
            total == 3 -> "Big"
            else -> "Mid $idx"
        }
    }
}
