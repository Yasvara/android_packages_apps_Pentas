/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.charging

/** The device's stock policy and the presets built from it. */
object ChargingPolicyPresets {

    /** Vendor normal-charge bands, verbatim. */
    val STOCK_JEITA_NORMAL = listOf(
        JeitaBand(JeitaBand.SENTINEL_LO, -9, 0, 2, 0, 4530, 572),
        JeitaBand(-9, 0, 1, 2, 940, 4530, 572),
        JeitaBand(0, 5, 1, 1, 3115, 4530, 572),
        JeitaBand(5, 10, 1, 1, 4385, 4530, 572),
        JeitaBand(10, 15, 1, 1, 7560, 4530, 572),
        JeitaBand(15, 20, 1, 1, 12640, 4530, 572),
        JeitaBand(20, 35, 1, 1, 20000, 4510, 572),
        JeitaBand(35, 40, 1, 1, 20000, 4510, 572),
        JeitaBand(40, 48, 1, 0, 20000, 4510, 572),
        // The band that made a 49 °C phone stop charging: 3115 mA and a 4100 mV
        // float, below the driver's 4200 mV cutoff gate.
        JeitaBand(48, 55, 2, 2, 3115, 4100, 572),
        JeitaBand(55, JeitaBand.SENTINEL_HI, 2, 2, 0, 4100, 572),
    )

    /** Vendor fast-full-charge bands. Note the 4580 mV float. */
    val STOCK_JEITA_FFC = listOf(
        JeitaBand(20, 35, 1, 2, 20000, 4580, 2667),
        JeitaBand(35, 40, 2, 1, 20000, 4580, 2731),
        JeitaBand(40, 48, 1, 0, 20000, 4580, 3239),
        JeitaBand(48, 55, 2, 2, 3175, 4060, 572),
        JeitaBand(55, JeitaBand.SENTINEL_HI, 2, 2, 0, 4060, 572),
    )

    /** Onyx per-adapter ceilings, matching onyx-charger-common.dtsi (2 A PD/PPS buck input). */
    val STOCK_CAPS = AdapterCaps(
        inputMa = mapOf(
            "dcp" to 1500, "pd" to 2000, "pps" to 2000, "hvdcp" to 1200,
            "hvdcp3" to 1600, "hvdcp3p5" to 1600, "cdp" to 900, "sdp" to 500,
            "float" to 1000,
        ),
        chargeMa = mapOf(
            "dcp" to 2000, "pd" to 3000, "pps" to 2000, "hvdcp" to 2600,
            "hvdcp3" to 3600, "hvdcp3p5" to 3600, "cdp" to 900, "sdp" to 500,
            "float" to 1000,
        ),
        ffcTempLowC = 15,
        ffcTempHighC = 48,
    )

    /** Replaces bands from [fromC] up with one full-current band, keeping the float voltage. */
    private fun openHotEnd(
        bands: List<JeitaBand>,
        fromC: Int,
        toC: Int,
        fccMa: Int,
        fvMv: Int,
    ): List<JeitaBand> {
        val kept = bands.filter { it.tHiC <= fromC }
        val iterm = bands.firstOrNull()?.itermMa ?: 572
        return kept +
            JeitaBand(fromC, toC, 1, 0, fccMa, fvMv, iterm) +
            JeitaBand(toC, JeitaBand.SENTINEL_HI, 2, 2, 0, fvMv, iterm)
    }

    /** Cool 90 W default: safe JEITA, 2 A PD/PPS buck caps, Hybrid CP-Bypass ready (mode off). */
    fun balanced() = ChargingPolicy(
        jeitaNormal = STOCK_JEITA_NORMAL,
        jeitaFfc = STOCK_JEITA_FFC,
        caps = STOCK_CAPS,
        bypass = BypassTuning(
            targetMa = 0, fccMaxMa = 3000, kp = 15, deadbandMa = 30, stepMa = 50,
            in9vMa = 3000, in5vMa = 3000, cpEnable = true, cpEnterMw = 12_000,
            cpVbatMaxMv = 4_450, mode = BypassMode.OFF,
        ),
        dynamics = ChargeDynamics(),
    )

    /** Peak 100 W (22 A) envelope for short cold bursts; auto Hybrid CP-Bypass during games. */
    fun max100w() = ChargingPolicy(
        envelope = PolicyEnvelope(fccMaxMa = 22_000, iinMaxMa = 6_000),
        jeitaNormal = openHotEnd(STOCK_JEITA_NORMAL, fromC = 40, toC = 50, fccMa = 22000, fvMv = 4510),
        jeitaFfc = openHotEnd(STOCK_JEITA_FFC, fromC = 40, toC = 50, fccMa = 22000, fvMv = 4580),
        caps = STOCK_CAPS.copy(
            inputMa = STOCK_CAPS.inputMa + mapOf("pd" to 3000, "pps" to 3000),
            chargeMa = STOCK_CAPS.chargeMa + mapOf("pd" to 6000, "pps" to 6000),
            ffcTempHighC = 50,
        ),
        bypass = BypassTuning(
            targetMa = 0, fccMaxMa = 3000, kp = 15, deadbandMa = 30, stepMa = 50,
            in9vMa = 3000, in5vMa = 3000, cpEnable = true, cpEnterMw = 12_000,
            cpVbatMaxMv = 4_450, mode = BypassMode.AUTO,
        ),
        dynamics = ChargeDynamics(),
    )

    /** Gaming Hybrid: SC8581 DIV2 CP-Bypass (>12 W PPS) + 3 A Buck ZDFB v3, hot bands open to 55 °C. */
    fun marathon() = ChargingPolicy(
        jeitaNormal = openHotEnd(STOCK_JEITA_NORMAL, fromC = 40, toC = 55, fccMa = 20000, fvMv = 4510),
        jeitaFfc = openHotEnd(STOCK_JEITA_FFC, fromC = 40, toC = 55, fccMa = 20000, fvMv = 4580),
        hotFvGateMv = 4200,
        caps = STOCK_CAPS.copy(
            inputMa = STOCK_CAPS.inputMa + mapOf(
                "pd" to 3000, "pps" to 3000, "hvdcp3" to 3000, "hvdcp3p5" to 3000,
            ),
            chargeMa = STOCK_CAPS.chargeMa + mapOf("pd" to 6000, "pps" to 6000),
            ffcTempHighC = 55,
        ),
        bypass = BypassTuning(
            targetMa = 0, fccMaxMa = 3000, kp = 15, deadbandMa = 30, stepMa = 50,
            in9vMa = 3000, in5vMa = 3000, cpEnable = true, cpEnterMw = 12_000,
            cpVbatMaxMv = 4_450, mode = BypassMode.AUTO,
        ),
        dynamics = ChargeDynamics(netzero = true, netzeroTargetMa = 0),
    )

    /** Full 22 A (100 W) silicon envelope with flat derate ladder and Hybrid CP-Bypass in games. */
    fun unrestricted() = ChargingPolicy(
        // 100 W = 22.2 A @222k (single SC8581 silicon limit). Widen the live kernel
        // envelope first or the tables below get clamped back to the 20 A default.
        envelope = PolicyEnvelope(fccMaxMa = 22_000, iinMaxMa = 6_000),
        jeitaNormal = openHotEnd(STOCK_JEITA_NORMAL, fromC = 40, toC = 58, fccMa = 22000, fvMv = 4510),
        jeitaFfc = openHotEnd(STOCK_JEITA_FFC, fromC = 40, toC = 58, fccMa = 22000, fvMv = 4580),
        hotFvGateMv = 4200,
        caps = STOCK_CAPS.copy(
            inputMa = STOCK_CAPS.inputMa.mapValues { (k, v) ->
                if (k in setOf("pd", "pps", "hvdcp3", "hvdcp3p5")) 4000 else v
            },
            chargeMa = STOCK_CAPS.chargeMa.mapValues { (k, v) ->
                if (k in setOf("pd", "pps", "hvdcp3", "hvdcp3p5")) 8000 else v
            },
            ffcTempHighC = 58,
        ),
        // Every rung flat: the ladder still exists, it just stops derating.
        ladder = (1..15).map { ThermalLadderRow.flat(it, inputMa = 4000, packMa = 22000) },
        bypass = BypassTuning(
            targetMa = 0, fccMaxMa = 4000, kp = 20, deadbandMa = 30, stepMa = 50,
            in9vMa = 4000, in5vMa = 3000, cpEnable = true, cpEnterMw = 10_000,
            cpVbatMaxMv = 4_450, mode = BypassMode.AUTO,
        ),
        dynamics = ChargeDynamics(
            netzero = true,
            netzeroTargetMa = 0,
            slewUa = 0,
            predAheadS = 0,
            predGain = 0,
        ),
    )

    enum class Preset(val label: String, val summary: String) {
        BALANCED("Balanced", "Cool 90 W default, safe JEITA"),
        MARATHON("Gaming Hybrid", "SC8581 CP-Bypass (>12 W) + 55 °C JEITA"),
        UNRESTRICTED("Max 100 W", "22 A envelope, flat ladder, CP-Bypass");

        fun build(): ChargingPolicy = when (this) {
            BALANCED -> balanced()
            MARATHON -> marathon()
            UNRESTRICTED -> unrestricted()
        }
    }
}
