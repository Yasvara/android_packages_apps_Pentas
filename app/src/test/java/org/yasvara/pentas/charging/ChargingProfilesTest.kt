/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.charging

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingProfilesTest {

    @Test
    fun wattConversionMatches1SCellPhysics() {
        // 1S 4.25 V cell @ 96.5% CP efficiency ≈ 222 mA/W
        assertEquals(222_000f, ChargingProfiles.UA_PER_WATT, 0.1f)
        assertEquals(19_980_000L, ChargingProfiles.wattToUa(90f))
        assertEquals(22_200_000L, ChargingProfiles.wattToUa(100f))
        assertEquals(9_990_000L, ChargingProfiles.wattToUa(45f))
        assertEquals(90f, ChargingProfiles.uaToWatt(19_980_000L), 0.1f)
        assertEquals(20.0f, ChargingProfiles.uaToAmp(20_000_000L), 0.01f)
    }

    @Test
    fun bypassTuningJsonRoundTripIncludesHybridCpFields() {
        val original = BypassTuning(
            targetMa = 50,
            fccMaxMa = 3500,
            kp = 18,
            deadbandMa = 30,
            stepMa = 50,
            in9vMa = 3200,
            in5vMa = 2800,
            cpEnable = true,
            cpEnterMw = 14_000,
            cpVbatMaxMv = 4_440,
            mode = BypassMode.AUTO,
        )
        val json = original.toJson()
        assertEquals(1, json.getInt("cp_en"))
        assertEquals(14_000, json.getInt("cp_mw"))
        assertEquals(4_440, json.getInt("cp_vbat"))
        assertEquals(30, json.getInt("deadband"))
        assertEquals(50, json.getInt("step"))

        val parsed = BypassTuning.fromJson(json)
        assertEquals(original, parsed)
    }

    @Test
    fun bypassTuningLegacyJsonDefaultsToZdfbV3AndCpEnabled() {
        val legacy = JSONObject("""{"target":0,"kp":15,"mode":2}""")
        val parsed = BypassTuning.fromJson(legacy)
        assertTrue(parsed.cpEnable)
        assertEquals(12_000, parsed.cpEnterMw)
        assertEquals(4_450, parsed.cpVbatMaxMv)
        assertEquals(30, parsed.deadbandMa)
        assertEquals(50, parsed.stepMa)
        assertEquals(BypassMode.AUTO, parsed.mode)
    }

    @Test
    fun bypassTuningNormalizationClampsOutOfRangeValues() {
        val wild = BypassTuning(
            cpEnterMw = 99_000,
            cpVbatMaxMv = 4_900,
            deadbandMa = 900,
            stepMa = 0,
        ).normalized()
        assertEquals(ChargingEnvelope.CP_ENTER_MAX_MW, wild.cpEnterMw)
        assertEquals(ChargingEnvelope.CP_VBAT_MAX_MV, wild.cpVbatMaxMv)
        assertEquals(500, wild.deadbandMa)
        assertEquals(1, wild.stepMa)
    }

    @Test
    fun presetsAreStrictlyMonotonicAndPreserveColdPlateau() {
        assertTrue(ChargingSuggestionEngine.PRESETS.size >= 5)
        for (preset in ChargingSuggestionEngine.PRESETS) {
            val profile = ChargingSuggestionEngine.generateProfile(
                cfg = preset.config,
                name = preset.name,
                basePolicy = preset.policy,
            )
            assertTrue("Preset ${preset.id} must have tiers", profile.tiers.isNotEmpty())
            assertTrue("Preset ${preset.id} must have <= MAX_TIERS", profile.tiers.size <= ChargingProfiles.MAX_TIERS)

            // Cold plateau tier ensures 33-36°C tropical ambient never throttles prematurely.
            val first = profile.tiers.first()
            assertEquals("Preset ${preset.id} cold plateau at 15°C", 15_000L, first.trigMc)
            assertTrue("Preset ${preset.id} cold plateau is pure cap", first.isPureCap)

            // Strict trigger monotonicity + valid hysteresis + valid current range.
            profile.tiers.forEachIndexed { i, t ->
                assertTrue("${preset.id} tier $i clr < trig", t.clrMc < t.trigMc)
                assertTrue("${preset.id} tier $i min <= max", t.minUa <= t.maxUa)
                if (i > 0) {
                    assertTrue(
                        "${preset.id} tier $i trig > prev",
                        t.trigMc > profile.tiers[i - 1].trigMc,
                    )
                }
            }
            assertTrue(profile.emergencyResumeMc < profile.emergencySuspendMc)
        }
    }

    @Test
    fun max100wPresetWidensEnvelopeTo22A() {
        val max100 = ChargingSuggestionEngine.PRESETS.first { it.id == "max_100w" }
        val profile = ChargingSuggestionEngine.generateProfile(
            cfg = max100.config,
            name = max100.name,
            basePolicy = max100.policy,
        )
        val env = profile.policy.envelope.fccMaxMa
        assertNotNull(env)
        assertTrue("100W profile must widen envelope >= 22000 mA", env!! >= 22_000)

        // Daemon wire payload must contain both tiers and bypass CP-Bypass keys.
        val daemon = profile.toDaemonJson()
        val bypass = daemon.getJSONObject("bypass")
        assertEquals(1, bypass.getInt("cp_en"))
        assertEquals(12_000, bypass.getInt("cp_mw"))
    }

    @Test
    fun stockCapsMatchDtsi2000mAAndMarathonEnablesCpBypass() {
        assertEquals(2000, ChargingPolicyPresets.STOCK_CAPS.inputMa["pd"])
        assertEquals(2000, ChargingPolicyPresets.STOCK_CAPS.inputMa["pps"])

        val marathon = ChargingPolicyPresets.marathon()
        val bp = marathon.bypass
        assertNotNull(bp)
        assertTrue(bp!!.cpEnable)
        assertEquals(BypassMode.AUTO, bp.mode)
        assertEquals(30, bp.deadbandMa)
        assertEquals(50, bp.stepMa)

        val balanced = ChargingPolicyPresets.balanced()
        assertEquals(BypassMode.OFF, balanced.bypass?.mode)
        assertFalse(balanced.isEmpty)
    }
}
