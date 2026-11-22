/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.charging

import org.yasvara.pentas.ui.kit.SymId
import kotlin.math.roundToLong

/** User intent for the Easy-Mode tuner and preset generator. */
data class ChargingConfig(
    /** Target battery cell temperature (°C) the cruise PID holds steady. */
    val targetTempC: Float = 39.5f,
    /** Peak charge speed in Watt (universal PD/PPS, 222 mA/W on 1S Si-C cell). */
    val maxWatt: Float = 90f,
    /** 0 = faster (warmer headroom), 50 = balanced cool cruise, 100 = coolest battery. */
    val aggressiveness: Int = 45,
)

sealed class ChargingSource {
    abstract val id: String
    abstract val name: String
    abstract val description: String

    data class Preset(
        override val id: String,
        @androidx.annotation.DrawableRes val icon: Int,
        override val name: String,
        override val description: String,
        val config: ChargingConfig,
        val badge: String = "",
        val policy: ChargingPolicy = ChargingPolicyPresets.balanced(),
    ) : ChargingSource()

    /** Cell-scale reference curves (currents in µA). */
    data class Vendor(
        override val id: String,
        override val name: String,
        override val description: String,
        val tiers: List<ChargingTier>,
        val policy: ChargingPolicy = ChargingPolicy(),
    ) : ChargingSource()
}

object ChargingSuggestionEngine {
    // ---- One-tap curated presets (calibrated to 222 mA/W cell-scale physics) ----
    val PRESETS: List<ChargingSource.Preset> = listOf(
        ChargingSource.Preset(
            id = "cool_90w",
            icon = SymId.Bolt,
            name = "Cool 90W",
            description = "Full 90W cold burst, smooth ~39.5°C cool cruise. Auto CP-Bypass in games.",
            config = ChargingConfig(targetTempC = 39.5f, maxWatt = 90f, aggressiveness = 45),
            badge = "Recommended",
            policy = ChargingPolicyPresets.balanced().copy(
                bypass = BypassTuning(
                    mode = BypassMode.AUTO,
                    cpEnable = true,
                    cpEnterMw = 12_000,
                    cpVbatMaxMv = 4_450,
                ),
            ),
        ),
        ChargingSource.Preset(
            id = "max_100w",
            icon = SymId.RocketLaunch,
            name = "Max 100W",
            description = "22A SC8581 peak burst when cool, fast 90W→70W cruise. Widens envelope to 22A.",
            config = ChargingConfig(targetTempC = 41.5f, maxWatt = 100f, aggressiveness = 20),
            badge = "22A Peak",
            policy = ChargingPolicyPresets.max100w(),
        ),
        ChargingSource.Preset(
            id = "gaming_bypass",
            icon = SymId.Speed,
            name = "Gaming Bypass",
            description = "Direct SC8581 2:1 bypass (>12W PPS) + 3A Buck ZDFB v3. Zero drain & ~6× cooler.",
            config = ChargingConfig(targetTempC = 40.0f, maxWatt = 65f, aggressiveness = 50),
            badge = "CP-Bypass",
            policy = ChargingPolicyPresets.marathon(),
        ),
        ChargingSource.Preset(
            id = "cool_daily",
            icon = SymId.Thermostat,
            name = "Cool Daily 45W",
            description = "Flat 45W cool plateau (~37.5°C). Minimal heat for maximum Si-C longevity.",
            config = ChargingConfig(targetTempC = 37.5f, maxWatt = 45f, aggressiveness = 65),
            badge = "Longevity",
            policy = ChargingPolicyPresets.balanced(),
        ),
        ChargingSource.Preset(
            id = "night_care",
            icon = SymId.Bedtime,
            name = "Night Care 15W",
            description = "Gentle 15W overnight charge. Barely warms above ambient, ultra-low wear.",
            config = ChargingConfig(targetTempC = 35.0f, maxWatt = 15f, aggressiveness = 85),
            badge = "Overnight",
            policy = ChargingPolicyPresets.balanced(),
        ),
    )

    // ---- Reference templates (calibrated to battery fuel-gauge cell scale) ----
    val VENDOR_TEMPLATES: List<ChargingSource.Vendor> = listOf(
        ChargingSource.Vendor(
            id = "onyx_cool_90w",
            name = "Onyx Cool 90W (Daemon Default)",
            description = "Calibrated cell-scale schedule matching mi_thermal_engine GLOBAL",
            tiers = listOf(
                tier(15000, 14000, 0,     22000, 22000, 0,   0,  0),
                tier(36500, 35000, 0,     20000, 20000, 0,   0,  0),
                tier(38000, 36500, 39500, 16000, 11500, 220, 8,  10),
                tier(40500, 39000, 41500, 11500, 7500,  250, 12, 15),
                tier(42500, 41000, 43000, 7500,  4500,  280, 15, 20),
                tier(44000, 42800, 44500, 3500,  3500,  320, 20, 25),
                tier(45000, 44000, 45500, 2500,  2500,  350, 20, 25),
                tier(46000, 45000, 46500, 1500,  1500,  350, 20, 25),
                tier(47000, 46000, 47500, 800,   800,   350, 20, 25),
            ),
            policy = ChargingPolicyPresets.balanced(),
        ),
        ChargingSource.Vendor(
            id = "onyx_gaming",
            name = "Onyx Gaming (Hybrid CP-Bypass)",
            description = "Cool charging when idle, SC8581 direct CP-Bypass in games",
            tiers = listOf(
                tier(15000, 14000, 0,     15000, 15000, 0,   0,  0),
                tier(37500, 36000, 39000, 11000, 6500,  250, 12, 15),
                tier(40500, 39000, 41500, 6500,  3500,  300, 18, 20),
                tier(43000, 41500, 43500, 3500,  2200,  350, 25, 25),
                tier(45000, 43800, 45500, 1800,  1500,  350, 25, 25),
                tier(46500, 45500, 47000, 800,   800,   350, 25, 25),
            ),
            policy = ChargingPolicyPresets.marathon(),
        ),
        ChargingSource.Vendor(
            id = "onyx_video",
            name = "Onyx Video Cool",
            description = "Low-heat curve for video streaming & screen-on use",
            tiers = listOf(
                tier(15000, 14000, 0,     11000, 11000, 0,   0,  0),
                tier(36500, 35000, 37500, 8000,  4500,  280, 15, 20),
                tier(39000, 37500, 40000, 4500,  2500,  320, 20, 25),
                tier(42000, 40800, 42500, 2500,  2000,  350, 25, 25),
                tier(44500, 43500, 45000, 1200,  1200,  350, 25, 25),
                tier(46000, 45000, 46500, 600,   600,   350, 25, 25),
            ),
            policy = ChargingPolicyPresets.balanced(),
        ),
    )

    private fun tier(
        trig: Long, clr: Long, target: Long, maxMa: Long, minMa: Long,
        ks: Long, ki: Long, kc: Long,
    ) = ChargingTier(
        trigMc = trig, clrMc = clr, targetMc = target,
        maxUa = maxMa * 1000, minUa = minMa * 1000,
        ks = ks, ki = ki, kc = kc,
    )

    private fun snapUa(ua: Long): Long =
        ((ua / 100_000.0).roundToLong() * 100_000L)
            .coerceIn(ChargingProfiles.FCC_HARD_MIN_UA, ChargingProfiles.FCC_HARD_MAX_UA)

    /**
     * Generates a strictly monotonic, cell-scale thermal schedule anchored to [cfg.targetTempC].
     *
     * Key physical design:
     * 1. Cold full-speed plateau from 15 °C up to the cruise entry so ambient 32–36 °C never
     *    prematurely throttles a fast charger.
     * 2. High-power (>65 W) curves insert a short high-speed step before entering the smooth
     *    PID cruise zone around [cfg.targetTempC], delivering fast 0→50% bursts while keeping
     *    skin/cell temperature comfortably cool.
     * 3. Warm protection tiers cap current cleanly as cell temp approaches 44–47 °C.
     */
    fun suggestTiers(cfg: ChargingConfig): List<ChargingTier> {
        val maxUa = snapUa(ChargingProfiles.wattToUa(cfg.maxWatt))
        val targetC = cfg.targetTempC.coerceIn(33f, 45f)
        val aggr = cfg.aggressiveness.coerceIn(0, 100) / 100f

        val ks = (200L + (aggr * 140f).toLong())
        val ki = (8L + (aggr * 18f).toLong())
        val kc = (10L + (aggr * 15f).toLong())

        val tiers = mutableListOf<ChargingTier>()

        // 1) Cold full-speed plateau: full requested wattage below cruise entry.
        tiers += ChargingTier(
            trigMc = 15_000L,
            clrMc = 14_000L,
            targetMc = 0L,
            maxUa = maxUa,
            minUa = maxUa,
            ks = 0L, ki = 0L, kc = 0L,
        )

        // Cruise entry relative to target temp: cooler priority enters slightly earlier.
        val cruiseStartC = (targetC - 1.0f - aggr * 1.2f).coerceIn(32f, 42.5f)
        val cruiseHighUa = snapUa((maxUa * (0.86f - aggr * 0.16f)).toLong()).coerceAtMost(maxUa)
        val cruiseMidUa = snapUa((maxUa * (0.62f - aggr * 0.18f)).toLong()).coerceAtMost(cruiseHighUa)
        val cruiseLowUa = snapUa((maxUa * (0.38f - aggr * 0.14f)).toLong()).coerceAtMost(cruiseMidUa)

        // 2) For high-power fast profiles (>= 65 W), add an intermediate burst step just before cruise.
        if (cfg.maxWatt >= 65f && cruiseStartC >= 36.5f) {
            val burstStepC = cruiseStartC - 1.5f
            val burstUa = snapUa((maxUa * 0.90f).toLong()).coerceAtMost(maxUa)
            if (burstUa < maxUa) {
                tiers += ChargingTier(
                    trigMc = ChargingProfiles.cToMc(burstStepC),
                    clrMc = ChargingProfiles.cToMc(burstStepC - 1.5f),
                    targetMc = 0L,
                    maxUa = burstUa,
                    minUa = burstUa,
                    ks = 0L, ki = 0L, kc = 0L,
                )
            }
        }

        // 3) Primary PID cool-cruise band: holds cell temperature steady around targetC.
        tiers += ChargingTier(
            trigMc = ChargingProfiles.cToMc(cruiseStartC),
            clrMc = ChargingProfiles.cToMc(cruiseStartC - 1.5f),
            targetMc = ChargingProfiles.cToMc(targetC),
            maxUa = cruiseHighUa,
            minUa = cruiseMidUa,
            ks = ks, ki = ki, kc = kc,
        )

        // 4) Secondary PID taper band (+2.0 °C above cruise entry).
        val taperC = (cruiseStartC + 2.5f).coerceAtMost(43.5f)
        if (taperC > cruiseStartC + 0.5f) {
            tiers += ChargingTier(
                trigMc = ChargingProfiles.cToMc(taperC),
                clrMc = ChargingProfiles.cToMc(taperC - 1.5f),
                targetMc = ChargingProfiles.cToMc((targetC + 2.0f).coerceAtMost(44.5f)),
                maxUa = cruiseMidUa,
                minUa = cruiseLowUa,
                ks = ks + 30L, ki = ki + 4L, kc = kc + 5L,
            )
        }

        // 5) Warm safety ladder above cruise zone (44 °C -> 45.5 °C -> 47 °C).
        val warmSteps = listOf(
            Triple(44.0f, 3_500_000L, 2_500_000L),
            Triple(45.5f, 2_200_000L, 1_500_000L),
            Triple(47.0f, 1_000_000L, 800_000L),
        )
        var prevTrig = tiers.last().trigMc
        var prevMax = tiers.last().minUa
        for ((tempC, capMax, capMin) in warmSteps) {
            val trigMc = ChargingProfiles.cToMc(tempC)
            if (trigMc <= prevTrig + 500L) continue
            val hi = capMax.coerceAtMost(prevMax).coerceAtLeast(ChargingProfiles.FCC_HARD_MIN_UA)
            val lo = capMin.coerceAtMost(hi).coerceAtLeast(ChargingProfiles.FCC_HARD_MIN_UA)
            tiers += ChargingTier(
                trigMc = trigMc,
                clrMc = trigMc - 1_200L,
                targetMc = trigMc + 500L,
                maxUa = hi,
                minUa = lo,
                ks = 320L, ki = 20L, kc = 25L,
            )
            prevTrig = trigMc
            prevMax = lo
        }

        return tiers.take(ChargingProfiles.MAX_TIERS)
    }

    fun generateProfile(
        cfg: ChargingConfig,
        name: String,
        id: String = CustomChargingProfile.newId(),
        basePolicy: ChargingPolicy = ChargingPolicy(),
    ): CustomChargingProfile {
        val tiers = suggestTiers(cfg)
        val peakMa = ((tiers.maxOfOrNull { it.maxUa } ?: 0L) / 1000L).toInt()
        val effectivePolicy = if (peakMa > ChargingEnvelope.POLICY_FCC_DEFAULT_MA) {
            val wantFcc = peakMa.coerceAtLeast(22_000).coerceAtMost(ChargingEnvelope.FCC_MAX_MA)
            val base = if (basePolicy.isEmpty) ChargingPolicyPresets.max100w() else basePolicy
            base.copy(
                envelope = base.envelope.copy(
                    fccMaxMa = maxOf(base.envelope.fccMaxMa ?: 0, wantFcc),
                    iinMaxMa = maxOf(base.envelope.iinMaxMa ?: 0, 6_000),
                ),
            )
        } else {
            basePolicy
        }
        val suspendC = (cfg.targetTempC + 10.5f).coerceIn(48f, 54f)
        return CustomChargingProfile(
            id = id,
            name = name,
            tiers = tiers,
            emergencySuspendMc = ChargingProfiles.cToMc(suspendC),
            emergencyResumeMc = ChargingProfiles.cToMc(suspendC - 3.5f),
            policy = effectivePolicy,
        )
    }

    /** Reverse-estimates an Easy-Mode [ChargingConfig] from an existing saved profile. */
    fun inferConfig(profile: CustomChargingProfile): ChargingConfig {
        val peakW = profile.peakWatt.coerceIn(ChargingProfiles.MIN_WATT, ChargingProfiles.MAX_WATT)
        val pidTier = profile.tiers.firstOrNull { !it.isPureCap }
        val targetC = pidTier?.let { ChargingProfiles.mcToC(it.targetMc) }?.coerceIn(33f, 45f)
            ?: 39.5f
        val aggr = when {
            peakW <= 20f || targetC <= 36f -> 80
            peakW <= 50f || targetC <= 38f -> 65
            targetC >= 41f -> 20
            else -> 45
        }
        return ChargingConfig(targetTempC = targetC, maxWatt = peakW, aggressiveness = aggr)
    }
}
