/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.thermal

import android.content.Context
import android.os.SystemProperties
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.yasvara.pentas.device.DeviceProfile
import org.yasvara.pentas.device.DeviceProfiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-game and global CPU/GPU clock limits, snapped to the kernel's frequency tables.
 *
 * When {@code mi_thermal_engine} is running, user CPU/GPU ceilings are published via
 * {@code persist.sys.mithermal.user_cpu_caps} and {@code persist.sys.mithermal.user_gpu_cap}
 * so the native daemon enforces {@code min(thermal_cap, user_cap)} with zero polling and zero
 * sysfs write races — even across reboots while Pentas stays unloaded at 0 KB.
 */
@Singleton
class PerfTuner @Inject constructor(@ApplicationContext context: Context) {

    /**
     * One cpufreq policy. [policyIndex] is the kernel's `policyN`; [slot] is the 0-based
     * position in the thermal daemon's cluster array. Do not mix them up.
     */
    data class CpuCluster(
        val slot: Int,
        val policyIndex: Int,
        val name: String,
        val policyDir: String,
        val availableKhz: List<Long>,
        val governors: List<String>,
        val hwMinKhz: Long,
        val hwMaxKhz: Long,
        val defaultGovernor: String,
    )

    /** Immutable hardware description of the GPU. */
    data class GpuInfo(
        val availableHz: List<Long>,
        val governors: List<String>,
        val hwMinHz: Long,
        val hwMaxHz: Long,
        val defaultGovernor: String,
    )

    /** One cluster's requested tuning; nulls mean "hardware default". */
    data class ClusterTuning(
        val minKhz: Long? = null,
        val maxKhz: Long? = null,
        val governor: String? = null,
    ) {
        val isDefault get() = minKhz == null && maxKhz == null && governor == null
    }

    /** Whether a game runs on its own limits or on the global baseline. */
    object ClockScope {
        /** This app's own profile is the whole truth; unset = hardware default. */
        const val PER_APP = 0

        /** Ignore this app's profile — the global baseline stays in force. */
        const val GLOBAL = 1

        /** New apps tune themselves, not the phone. */
        const val DEFAULT = PER_APP
    }

    /** Full per-game tuning profile. Cluster map is keyed by [CpuCluster.policyIndex]. */
    data class PerfProfile(
        val clusters: Map<Int, ClusterTuning> = emptyMap(),
        val gpuMinHz: Long? = null,
        val gpuMaxHz: Long? = null,
        val gpuGovernor: String? = null,
    ) {
        val isDefault
            get() = clusters.values.all { it.isDefault } &&
                gpuMinHz == null && gpuMaxHz == null && gpuGovernor == null
    }

    /** Lazy: credential-encrypted storage is unavailable until user 0 unlocks. */
    private val prefs by lazy {
        context.getSharedPreferences("perf_tuner", Context.MODE_PRIVATE)
    }
    /** Applies are serialised: they mutate held-node state and order writes against readbacks. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val deviceProfile: DeviceProfile by lazy { DeviceProfiles.get(context) }

    /** Clusters in policy order, which is also the thermal daemon's slot order. */
    val cpuClusters: List<CpuCluster> by lazy {
        deviceProfile.cpuClusters.mapIndexed { slot, spec ->
            CpuCluster(
                slot = slot,
                policyIndex = spec.policyIndex,
                name = spec.name,
                policyDir = spec.policyDir,
                availableKhz = KernelNodeIO
                    .readLongList("${spec.policyDir}/scaling_available_frequencies")
                    .sorted(),
                governors = KernelNodeIO
                    .readTokenList("${spec.policyDir}/scaling_available_governors"),
                hwMinKhz = spec.hwMinKhz,
                hwMaxKhz = spec.hwMaxKhz,
                defaultGovernor =
                    KernelNodeIO.read("${spec.policyDir}/scaling_governor") ?: "walt",
            )
        }
    }

    /** Detected GPU capabilities — derived from DeviceProfile's kgsl/devfreq node. */
    val gpuInfo: GpuInfo by lazy {
        val gpu = deviceProfile.gpu
        val freqs = gpu?.availableFreqsPath
            ?.let { KernelNodeIO.readLongList(it) }
            ?.sorted() ?: emptyList()
        GpuInfo(
            availableHz = freqs,
            governors = gpu?.availableGovernorsPath
                ?.let { KernelNodeIO.readTokenList(it) } ?: emptyList(),
            hwMinHz = freqs.firstOrNull() ?: 0L,
            hwMaxHz = freqs.lastOrNull() ?: 0L,
            defaultGovernor =
                gpu?.governorPath?.let { KernelNodeIO.read(it) } ?: "msm-adreno-tz",
        )
    }

    private val _activeProfile = MutableStateFlow<PerfProfile?>(null)
    val activeProfile: StateFlow<PerfProfile?> = _activeProfile.asStateFlow()

    /** [ClockScope] of the running session, so the overlay tuner can show it. */
    private val _activeScope = MutableStateFlow(ClockScope.DEFAULT)
    val activeScope: StateFlow<Int> = _activeScope.asStateFlow()

    private var activePackage: String? = null

    // ── Live telemetry (cheap fd-cached reads, safe at 1 Hz+) ───────────────

    /** The ceiling in force; lower than requested while thermal throttling is active. */
    fun cpuLiveMaxKhz(cluster: CpuCluster): Long =
        KernelNodeIO.readLong(ThermalNodes.cpuScalingMax(cluster.policyDir)) ?: 0L

    fun gpuLiveMaxHz(): Long = KernelNodeIO.readLong(ThermalNodes.GPU_DEVFREQ_MAX) ?: 0L

    // ── Application ─────────────────────────────────────────────────────────

    /** Apply the saved tuning profile for [packageName] (game session start). */
    fun applyForGame(packageName: String) {
        scope.launch {
            activePackage = packageName
            val profile = loadProfile(packageName) ?: PerfProfile()
            _activeProfile.value = profile
            _activeScope.value = clockScope(packageName)
            applyProfile(effectiveProfile(packageName))
            if (!profile.isDefault) Log.i(TAG, "Applied perf profile for $packageName")
        }
    }

    /**
     * Persist a profile for any game (hub/per-app editor). Applied to the
     * kernel immediately only when that game is the active session.
     */
    fun saveProfileFor(packageName: String, profile: PerfProfile) {
        scope.launch {
            saveProfile(packageName, profile)
            if (packageName == activePackage) {
                _activeProfile.value = profile
                applyProfile(effectiveProfile(packageName))
            }
        }
    }

    // ── Clock scope (per app: own limits, or follow the global baseline) ────

    /** [ClockScope] saved for [packageName]; [ClockScope.DEFAULT] when unset. */
    fun clockScope(packageName: String): Int =
        prefs.getInt(scopeKey(packageName), ClockScope.DEFAULT)

    /** Persist the scope for [packageName], re-applying if it is the live session. */
    fun setClockScope(packageName: String, value: Int) {
        scope.launch {
            prefs.edit().putInt(scopeKey(packageName), value).apply()
            if (packageName == activePackage) {
                _activeScope.value = value
                applyProfile(effectiveProfile(packageName))
            }
            Log.i(TAG, "Clock scope for $packageName -> " +
                if (value == ClockScope.GLOBAL) "GLOBAL" else "PER_APP")
        }
    }

    /** Per-app scope applies only fields the user set; unset fields release their node. */
    private fun effectiveProfile(packageName: String): PerfProfile =
        if (clockScope(packageName) == ClockScope.GLOBAL) loadGlobalProfile()
        else loadProfile(packageName) ?: PerfProfile()

    private fun scopeKey(packageName: String) = "$SCOPE_PREFIX$packageName"

    // ── Global baseline ─────────────────────────────────────────────────────

    /** The baseline outside sessions and for games following the global scope. */
    fun loadGlobalProfile(): PerfProfile = loadProfile(GLOBAL_KEY) ?: PerfProfile()

    fun saveGlobalProfile(profile: PerfProfile) {
        scope.launch {
            saveProfile(GLOBAL_KEY, profile)
            // Outside a session the baseline *is* the live state; inside one it
            // is live only for a game that follows global, so the re-apply goes
            // through the same resolver the session start used.
            val pkg = activePackage
            applyProfile(if (pkg == null) profile else effectiveProfile(pkg))
            Log.i(TAG, "Global perf profile saved (default=${profile.isDefault})")
        }
    }

    /** Apply the global baseline — boot, and whenever a game session ends. */
    fun applyGlobal() {
        scope.launch {
            activePackage = null
            applyProfile(loadGlobalProfile())
            _activeProfile.value = null
            _activeScope.value = ClockScope.DEFAULT
            Log.i(TAG, "Perf tuning restored to global baseline")
        }
    }

    private fun isThermalDaemonRunning(): Boolean =
        SystemProperties.get("init.svc.mi_thermal_engine", "") == "running"

    private fun applyProfile(profile: PerfProfile) {
        val daemonOwned = isThermalDaemonRunning()

        // Publish ceilings to mi_thermal_engine first so it owns min(thermal, user) race-free
        // and persists global ceilings across reboots with zero persistent Pentas process.
        val cpuCaps = LongArray(4)
        cpuClusters.forEach { cluster ->
            val tuning = profile.clusters[cluster.policyIndex] ?: ClusterTuning()
            val minReq = snap(tuning.minKhz, cluster.availableKhz)
            val maxReq = snap(tuning.maxKhz, cluster.availableKhz)
            if (cluster.slot in 0 until 4) {
                cpuCaps[cluster.slot] = maxReq ?: 0L
            }
            if (daemonOwned) {
                // Only manage min_freq/governor directly; apply immediate cap-down if above req.
                applyNode(ThermalNodes.cpuScalingMin(cluster.policyDir), minReq, cluster.hwMinKhz)
                if (maxReq != null) {
                    val liveMax = KernelNodeIO.readLong(ThermalNodes.cpuScalingMax(cluster.policyDir))
                    if (liveMax != null && liveMax > maxReq) {
                        KernelNodeIO.write(ThermalNodes.cpuScalingMax(cluster.policyDir), maxReq)
                    }
                }
            } else {
                applyRange(
                    minNode = ThermalNodes.cpuScalingMin(cluster.policyDir),
                    maxNode = ThermalNodes.cpuScalingMax(cluster.policyDir),
                    minReq = minReq,
                    maxReq = maxReq,
                    hwMin = cluster.hwMinKhz,
                    hwMax = cluster.hwMaxKhz,
                )
            }
            applyGovernor(
                node = ThermalNodes.cpuGovernor(cluster.policyDir),
                requested = tuning.governor,
                hwDefault = cluster.defaultGovernor,
                supported = cluster.governors,
            )
        }
        SystemProperties.set(PROP_USER_CPU_CAPS, cpuCaps.joinToString(","))

        if (gpuInfo.availableHz.isNotEmpty()) {
            val minHz = snap(profile.gpuMinHz, gpuInfo.availableHz)
            val maxHz = snap(profile.gpuMaxHz, gpuInfo.availableHz)
            SystemProperties.set(PROP_USER_GPU_CAP, (maxHz ?: 0L).toString())
            if (daemonOwned) {
                applyNode(ThermalNodes.GPU_DEVFREQ_MIN, minHz, gpuInfo.hwMinHz)
                applyNode(
                    ThermalNodes.GPU_MIN_CLOCK_MHZ,
                    minHz?.div(1_000_000),
                    gpuInfo.hwMinHz / 1_000_000,
                )
                if (maxHz != null) {
                    val liveMax = KernelNodeIO.readLong(ThermalNodes.GPU_DEVFREQ_MAX)
                    if (liveMax != null && liveMax > maxHz) {
                        KernelNodeIO.write(ThermalNodes.GPU_DEVFREQ_MAX, maxHz)
                    }
                }
            } else {
                applyRange(
                    minNode = ThermalNodes.GPU_DEVFREQ_MIN,
                    maxNode = ThermalNodes.GPU_DEVFREQ_MAX,
                    minReq = minHz,
                    maxReq = maxHz,
                    hwMin = gpuInfo.hwMinHz,
                    hwMax = gpuInfo.hwMaxHz,
                )
                // kgsl MHz mirrors keep the vendor pwrlevel logic consistent
                applyRange(
                    minNode = ThermalNodes.GPU_MIN_CLOCK_MHZ,
                    maxNode = ThermalNodes.GPU_MAX_CLOCK_MHZ,
                    minReq = minHz?.div(1_000_000),
                    maxReq = maxHz?.div(1_000_000),
                    hwMin = gpuInfo.hwMinHz / 1_000_000,
                    hwMax = gpuInfo.hwMaxHz / 1_000_000,
                )
            }
            applyGovernor(
                node = ThermalNodes.GPU_GOVERNOR,
                requested = profile.gpuGovernor,
                hwDefault = gpuInfo.defaultGovernor,
                supported = gpuInfo.governors,
            )
        } else {
            SystemProperties.set(PROP_USER_GPU_CAP, "0")
        }
    }

    // ── Node ownership (see the class kdoc for why this matters) ────────────

    /** Node → last value written. Lets [release] tell whether the node is still ours. */
    private val heldNodes = HashMap<String, String>()

    /** Writes a floor/ceiling pair in the order cpufreq accepts relative to the live range. */
    private fun applyRange(
        minNode: String,
        maxNode: String,
        minReq: Long?,
        maxReq: Long?,
        hwMin: Long,
        hwMax: Long,
    ) {
        // A ceiling below the floor is not expressible; the ceiling wins because
        // it is the thermally meaningful half.
        val min = minReq?.coerceAtMost(maxReq ?: hwMax)
        val max = maxReq?.coerceAtLeast(minReq ?: hwMin)
        val liveMax = KernelNodeIO.readLong(maxNode) ?: hwMax
        if (min != null && min > liveMax) {
            applyNode(maxNode, max, hwMax)
            applyNode(minNode, min, hwMin)
        } else {
            applyNode(minNode, min, hwMin)
            applyNode(maxNode, max, hwMax)
        }
    }

    /** Reconciles one node; a null request releases it rather than writing [hwDefault]. */
    private fun applyNode(node: String, requested: Long?, hwDefault: Long) =
        if (requested == null) release(node, hwDefault.toString())
        else hold(node, requested.toString())

    /** Same ownership contract as [applyNode], for string-valued nodes. */
    private fun applyGovernor(
        node: String,
        requested: String?,
        hwDefault: String,
        supported: List<String>,
    ) = when {
        requested == null -> release(node, hwDefault)
        requested in supported -> hold(node, requested)
        else -> Unit // kernel doesn't offer it; nothing to say about this node
    }

    /** Claim [node] and drive it to [value]. */
    private fun hold(node: String, value: String) {
        heldNodes[node] = value
        // Readback instead of a blind write: skips the syscall in the steady
        // state, and still re-asserts after another writer moved the node.
        if (KernelNodeIO.read(node) != value) KernelNodeIO.write(node, value)
    }

    /** Restores [hwDefault] only if the node still holds our last write. */
    private fun release(node: String, hwDefault: String) {
        val held = heldNodes.remove(node) ?: return
        if (KernelNodeIO.read(node) == held) KernelNodeIO.write(node, hwDefault)
    }

    /** Snap a requested frequency to the nearest kernel-supported step. */
    private fun snap(requested: Long?, table: List<Long>): Long? {
        if (requested == null || table.isEmpty()) return requested
        return table.minByOrNull { kotlin.math.abs(it - requested) }
    }

    // ── Persistence (app-private prefs, JSON per package) ───────────────────

    fun loadProfile(packageName: String): PerfProfile? {
        val raw = prefs.getString(packageName, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            PerfProfile(
                clusters = o.optJSONObject("cpu")?.let { cpu ->
                    cpu.keys().asSequence().associate { key ->
                        val c = cpu.getJSONObject(key)
                        key.toInt() to ClusterTuning(
                            minKhz = c.optLong("min").takeIf { it > 0 },
                            maxKhz = c.optLong("max").takeIf { it > 0 },
                            governor = c.optString("gov").takeIf { it.isNotEmpty() },
                        )
                    }
                } ?: emptyMap(),
                gpuMinHz = o.optLong("gpuMin").takeIf { it > 0 },
                gpuMaxHz = o.optLong("gpuMax").takeIf { it > 0 },
                gpuGovernor = o.optString("gpuGov").takeIf { it.isNotEmpty() },
            )
        }.getOrNull()
    }

    private fun saveProfile(packageName: String, profile: PerfProfile) {
        if (profile.isDefault) {
            prefs.edit().remove(packageName).apply()
            return
        }
        val o = JSONObject()
        val cpu = JSONObject()
        profile.clusters.forEach { (idx, t) ->
            if (!t.isDefault) {
                cpu.put(
                    idx.toString(),
                    JSONObject()
                        .put("min", t.minKhz ?: 0)
                        .put("max", t.maxKhz ?: 0)
                        .put("gov", t.governor ?: "")
                )
            }
        }
        o.put("cpu", cpu)
        profile.gpuMinHz?.let { o.put("gpuMin", it) }
        profile.gpuMaxHz?.let { o.put("gpuMax", it) }
        profile.gpuGovernor?.let { o.put("gpuGov", it) }
        prefs.edit().putString(packageName, o.toString()).apply()
    }

    companion object {
        private const val TAG = "PerfTuner"

        /** Per-cluster user CPU ceiling (kHz CSV, 4 slots, 0 = uncapped) owned by mi_thermal_engine. */
        const val PROP_USER_CPU_CAPS = "persist.sys.mithermal.user_cpu_caps"
        /** User GPU ceiling (Hz, 0 = uncapped) owned by mi_thermal_engine. */
        const val PROP_USER_GPU_CAP = "persist.sys.mithermal.user_gpu_cap"

        /**
         * Prefs key for the global baseline. Has no dot, so it can never
         * collide with a real Android package name.
         */
        const val GLOBAL_KEY = "__global__"

        /** Prefix for per-app scope keys; ':' cannot appear in a package name. */
        private const val SCOPE_PREFIX = "scope::"
    }
}
