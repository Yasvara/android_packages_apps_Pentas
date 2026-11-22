/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.charging

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ChargingProfileEditorViewModel @Inject constructor(
    val repository: ChargingProfileRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val profileId: String =
        savedStateHandle.get<String>("profile_id") ?: CustomChargingProfile.newId()
    val isExisting: Boolean = savedStateHandle.get<String>("profile_id") != null
    private val sourceId: String? = savedStateHandle.get<String>("source_id")

    var name by mutableStateOf("")
    val tiers = mutableStateListOf<ChargingTier>()
    // Cell temperature scale (°C × 1000).
    var suspendMc by mutableLongStateOf(50_000L)
    var resumeMc by mutableLongStateOf(46_500L)

    /**
     * Kernel policy the tiers run inside. Empty leaves the kernel at its
     * device-tree defaults.
     */
    var policy by mutableStateOf(ChargingPolicy())

    /** Live charging & thermal telemetry polled on IO by the editor screen. */
    var readings by mutableStateOf(ChargeReadings.read(ChargingPolicy().dynamics))

    /** Easy Mode (default) vs Advanced Mode (full per-tier, JEITA & regulator control). */
    var advancedMode by mutableStateOf(false)

    /** Highlighted curated preset id, cleared whenever user manually tweaks Easy sliders. */
    var activePresetId by mutableStateOf<String?>(null)

    var dirty by mutableStateOf(false)

    /** True while the daemon is running an uncommitted draft from [applyNow]. */
    var previewApplied by mutableStateOf(false)
        private set

    var statusMessage by mutableStateOf<String?>(null)
    var seededFrom by mutableStateOf<String?>(null)
    val validationErrors = mutableStateListOf<String>()

    /** Live Easy-Mode intent config; changing it regenerates tiers immediately. */
    var wizardConfig by mutableStateOf(ChargingConfig())

    init {
        refreshAndLoad()
    }

    private fun refreshAndLoad() {
        val existing = repository.getById(profileId)
        if (existing != null) {
            loadFrom(existing)
            wizardConfig = ChargingSuggestionEngine.inferConfig(existing)
            return
        }
        val source = sourceId?.let { id ->
            ChargingSuggestionEngine.PRESETS.firstOrNull { it.id == id }
                ?: ChargingSuggestionEngine.VENDOR_TEMPLATES.firstOrNull { it.id == id }
                ?: repository.getById(id)?.let { p ->
                    ChargingSource.Vendor(p.id, p.name, "copy", p.tiers.toList(), p.policy)
                }
        }
        if (source != null) {
            importFrom(source)
        } else {
            // Seed brand-new profiles with calibrated Cool 90W instead of a bare 9A stub.
            val defaultPreset = ChargingSuggestionEngine.PRESETS.first()
            applyPreset(defaultPreset, silent = true)
            dirty = false
        }
    }

    private fun loadFrom(p: CustomChargingProfile) {
        name = p.name
        suspendMc = p.emergencySuspendMc
        resumeMc = p.emergencyResumeMc
        policy = p.policy
        tiers.clear()
        tiers.addAll(p.tiers)
        refreshReadings()
    }

    fun refreshReadings() {
        readings = ChargeReadings.read(policy.dynamics)
    }

    fun updateReadings(newReadings: ChargeReadings) {
        readings = newReadings
    }

    // ---- Easy Mode live editing ----

    fun updateEasyConfig(next: ChargingConfig) {
        wizardConfig = next
        activePresetId = null
        val gen = ChargingSuggestionEngine.generateProfile(
            cfg = next,
            name = name.ifBlank { "Custom" },
            id = profileId,
            basePolicy = policy,
        )
        tiers.clear()
        tiers.addAll(gen.tiers)
        suspendMc = gen.emergencySuspendMc
        resumeMc = gen.emergencyResumeMc
        policy = gen.policy
        dirty = true
    }

    /** Ensure policy is populated before editing a single knob so kernel defaults never regress. */
    private fun ensurePolicyBase(p: ChargingPolicy): ChargingPolicy =
        if (p.isEmpty) ChargingPolicyPresets.balanced() else p

    fun updateBypass(block: (BypassTuning) -> BypassTuning) {
        updatePolicy { p ->
            val base = ensurePolicyBase(p)
            val current = base.bypass ?: BypassTuning()
            val updated = block(current).normalized()
            // When enabling bypass, ensure PD/PPS buck input cap is at least 3000 mA so ZDFB v3 has headroom.
            val caps = if (updated.mode != BypassMode.OFF) {
                base.caps.copy(
                    inputMa = base.caps.inputMa + mapOf(
                        "pd" to maxOf(base.caps.inputMa["pd"] ?: 2000, updated.in9vMa),
                        "pps" to maxOf(base.caps.inputMa["pps"] ?: 2000, updated.in9vMa),
                    ),
                )
            } else {
                base.caps
            }
            base.copy(bypass = updated, caps = caps)
        }
    }

    fun setBypassMode(mode: BypassMode) {
        updateBypass { it.copy(mode = mode) }
    }

    fun setCpBypassEnabled(enabled: Boolean) {
        updateBypass { it.copy(cpEnable = enabled) }
    }

    // ---- Advanced Mode policy helpers ----

    fun applyPolicyPreset(preset: ChargingPolicyPresets.Preset) {
        policy = preset.build()
        dirty = true
        refreshReadings()
        statusMessage = "${preset.label} policy loaded"
    }

    fun resetPolicyToKernelDefault() {
        policy = ChargingPolicy()
        dirty = true
        statusMessage = "Policy reset to kernel defaults"
    }

    fun updatePolicy(block: (ChargingPolicy) -> ChargingPolicy) {
        policy = block(policy)
        dirty = true
    }

    val isEnvelope22A: Boolean
        get() = (policy.envelope.fccMaxMa ?: ChargingEnvelope.POLICY_FCC_DEFAULT_MA) >
            ChargingEnvelope.POLICY_FCC_DEFAULT_MA

    fun setEnvelope22A(enabled: Boolean) {
        updatePolicy { p ->
            val base = ensurePolicyBase(p)
            val env = if (enabled) {
                base.envelope.copy(fccMaxMa = 22_000, iinMaxMa = maxOf(base.envelope.iinMaxMa ?: 0, 6_000))
            } else {
                base.envelope.copy(fccMaxMa = null)
            }
            base.copy(envelope = env)
        }
    }

    /** Highest temperature still charging at full current, read from the band table. */
    val hotCeilingC: Int
        get() = policy.jeitaNormal.lastOrNull { !it.isCutoff }?.tHiC ?: 48

    /** Moves the hot ceiling in both band tables, keeping the float voltage. */
    fun setHotCeiling(c: Int) {
        val clamped = c.coerceIn(40, ChargingEnvelope.TEMP_MAX_C - 2)
        updatePolicy { p ->
            val base = p.takeIf { it.jeitaNormal.isNotEmpty() }
                ?: ChargingPolicyPresets.balanced()
            base.copy(
                jeitaNormal = reopen(base.jeitaNormal, clamped),
                jeitaFfc = reopen(base.jeitaFfc, clamped),
                hotFvGateMv = p.hotFvGateMv ?: 4200,
            )
        }
    }

    private fun reopen(bands: List<JeitaBand>, ceilingC: Int): List<JeitaBand> {
        if (bands.isEmpty()) return bands
        val lastCharging = bands.lastOrNull { !it.isCutoff } ?: return bands
        val kept = bands.filter { !it.isCutoff && it.tHiC <= 40 }
        return kept + listOf(
            lastCharging.copy(tLoC = 40, tHiC = ceilingC, hysLo = 1, hysHi = 0),
            JeitaBand(
                ceilingC, JeitaBand.SENTINEL_HI, 2, 2, 0, lastCharging.fvMv,
                lastCharging.itermMa,
            ),
        )
    }

    fun importFrom(source: ChargingSource, silent: Boolean = false) {
        when (source) {
            is ChargingSource.Preset -> {
                wizardConfig = source.config
                activePresetId = source.id
                val gen = ChargingSuggestionEngine.generateProfile(
                    cfg = source.config,
                    name = if (name.isBlank() || activePresetId != null) source.name else name,
                    id = profileId,
                    basePolicy = source.policy,
                )
                loadFrom(gen)
                if (name.isBlank()) name = source.name
            }
            is ChargingSource.Vendor -> {
                activePresetId = null
                tiers.clear()
                tiers.addAll(source.tiers)
                if (!source.policy.isEmpty) {
                    policy = source.policy
                }
                if (name.isBlank()) name = source.name
                wizardConfig = ChargingSuggestionEngine.inferConfig(toProfile())
            }
        }
        seededFrom = source.name
        dirty = true
        if (!silent) {
            statusMessage = "Loaded \"${source.name}\""
        }
    }

    fun applyPreset(preset: ChargingSource.Preset, silent: Boolean = false) {
        // When selecting a preset, update name if blank or still matching another preset's name.
        val isPresetName = name.isBlank() ||
            ChargingSuggestionEngine.PRESETS.any { it.name == name }
        if (isPresetName) name = preset.name
        importFrom(preset, silent = silent)
    }

    // ---- Per-tier editing (Advanced Mode) ----

    fun addTier() {
        if (tiers.size >= ChargingProfiles.MAX_TIERS) return
        val last = tiers.lastOrNull()
        tiers.add(
            last?.let {
                ChargingTier(
                    trigMc = (it.trigMc + 2000).coerceAtMost(56_000L),
                    clrMc = it.trigMc,
                    targetMc = (it.trigMc + 1000).coerceAtMost(57_000L),
                    maxUa = (it.maxUa * 0.65f).toLong().coerceAtLeast(ChargingProfiles.FCC_HARD_MIN_UA),
                    minUa = (it.minUa * 0.65f).toLong().coerceAtLeast(ChargingProfiles.FCC_HARD_MIN_UA),
                    ks = it.ks, ki = it.ki, kc = it.kc,
                )
            } ?: ChargingTier()
        )
        activePresetId = null
        dirty = true
    }

    fun removeTier(index: Int) {
        if (index in tiers.indices && tiers.size > 1) {
            tiers.removeAt(index)
            activePresetId = null
            dirty = true
        }
    }

    fun updateTier(index: Int, tier: ChargingTier) {
        if (index in tiers.indices) {
            tiers[index] = tier
            activePresetId = null
            dirty = true
        }
    }

    // ---- Persistence & daemon apply ----

    fun toProfile() = CustomChargingProfile(
        id = profileId,
        name = name.trim(),
        tiers = tiers.toList(),
        emergencySuspendMc = suspendMc,
        emergencyResumeMc = resumeMc,
        policy = policy,
    )

    fun validate(): List<String> {
        val errs = repository.validate(toProfile())
        validationErrors.clear()
        validationErrors.addAll(errs)
        return errs
    }

    fun save(): Boolean {
        if (validate().isNotEmpty()) {
            statusMessage = "Fix validation errors first"
            return false
        }
        repository.save(toProfile())
        dirty = false
        statusMessage = "Profile saved"
        return true
    }

    fun saveAndApply(): Boolean {
        if (!save()) return false
        val profile = toProfile()
        repository.setGlobalProfileId(profileId)
        repository.applyToDaemon(profile)
        repository.syncBypassSetting(profile)
        previewApplied = false
        statusMessage = "Saved & activated"
        return true
    }

    fun applyNow(): Boolean {
        if (validate().isNotEmpty()) {
            statusMessage = "Fix validation errors first"
            return false
        }
        repository.applyToDaemon(toProfile())
        previewApplied = true
        statusMessage = "Applied live for testing"
        return true
    }

    /** Restores the selected profile after a test-applied draft. */
    fun revertPreview() {
        if (!previewApplied) return
        previewApplied = false
        repository.applyActiveProfile(null)
    }

    fun clearStatus() {
        statusMessage = null
    }
}
