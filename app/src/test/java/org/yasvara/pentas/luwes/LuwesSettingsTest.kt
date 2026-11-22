/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.luwes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yasvara.pentas.luwes.LuwesSettings.Mode

class LuwesSettingsTest {

    @Test
    fun encodesTheChannelSectionByteForByte() {
        // framefx's channel_test decodes exactly these bytes as 3x, interpolate, HUD on.
        assertArrayEquals(
            byteArrayOf(1, 3, 1, 1, 0, 0, 0, 0),
            LuwesSettings(3, Mode.INTERPOLATE, hudProtection = true).encode(),
        )
        assertArrayEquals(
            byteArrayOf(1, 2, 0, 0, 0, 0, 0, 0),
            LuwesSettings(2, Mode.EXTRAPOLATE, hudProtection = false).encode(),
        )
    }

    @Test
    fun luwesEngineIsFlagBit1() {
        // framefx reads bit 1 as kLuwesOwnEngine; clear is Qualcomm's AFME where it runs.
        assertArrayEquals(
            byteArrayOf(1, 2, 0, 3, 0, 0, 0, 0),
            LuwesSettings(2, Mode.EXTRAPOLATE, true, LuwesSettings.Engine.LUWES).encode(),
        )
        assertEquals(LuwesSettings.Engine.QUALCOMM, LuwesSettings().engine)
    }

    @Test
    fun offRemovesTheSection() {
        assertEquals(0, LuwesSettings().encode().size)
        assertEquals(0, LuwesSettings(LuwesSettings.OFF, Mode.INTERPOLATE, false).encode().size)
        assertFalse(LuwesSettings().on)
    }

    @Test
    fun everyValidSettingRoundTrips() {
        for (m in LuwesSettings.MIN..LuwesSettings.MAX) {
            for (mode in Mode.entries) {
                for (hud in listOf(true, false)) {
                    for (engine in LuwesSettings.Engine.entries) {
                        val s = LuwesSettings(m, mode, hud, engine)
                        assertTrue(s.on)
                        assertEquals(s, LuwesSettings.decode(s.encode()))
                    }
                }
            }
        }
    }

    @Test
    fun anythingMalformedDecodesAsOff() {
        val good = byteArrayOf(1, 4, 0, 1, 0, 0, 0, 0)
        assertEquals(LuwesSettings(4, Mode.EXTRAPOLATE, true), LuwesSettings.decode(good))
        val cases = listOf(
            null,
            ByteArray(0),
            good.copyOf(7),
            good.copyOf(9),
            good.copyOf().also { it[0] = 2 },  // major
            good.copyOf().also { it[1] = 1 },  // multiplier below 2
            good.copyOf().also { it[1] = 5 },  // multiplier above 4
            good.copyOf().also { it[2] = 2 },  // mode
            good.copyOf().also { it[3] = 4 },  // unknown flag
            good.copyOf().also { it[7] = 1 },  // reserved
        )
        for (bytes in cases) assertFalse(LuwesSettings.decode(bytes).on)
    }

    @Test
    fun multipliersKeepTheBaseAt30OrAbove() {
        assertEquals(listOf(2, 3, 4), LuwesSettings.multipliers(135f))
        assertEquals(listOf(2, 3), LuwesSettings.multipliers(90f))
        assertEquals(listOf(2), LuwesSettings.multipliers(60f))
        assertEquals(emptyList<Int>(), LuwesSettings.multipliers(50f))
    }

    @Test
    fun effectiveMatchesTheEngine() {
        assertEquals(4, LuwesSettings.effective(4, 135f))
        assertEquals(3, LuwesSettings.effective(4, 90f))
        assertEquals(2, LuwesSettings.effective(4, 60f))
        assertEquals(1, LuwesSettings.effective(2, 50f))
    }

    @Test
    fun cadenceShowsBaseAgainstThePanel() {
        assertEquals("67.5 → 135", LuwesSettings.cadence(2, 135f))
        assertEquals("45 → 135", LuwesSettings.cadence(3, 135f))
        assertEquals("33.75 → 135", LuwesSettings.cadence(4, 135f))
        assertEquals("45 → 90", LuwesSettings.cadence(2, 90f))
        assertEquals("30 → 90", LuwesSettings.cadence(3, 90f))
        assertEquals("30 → 60", LuwesSettings.cadence(2, 60f))
    }

    @Test
    fun readsTheStatusPageTheEngineWrites() {
        // Byte for byte what framefx's status test expects: running on Vulkan, 1968x908, 3x
        // interpolate generating 2, paced, 45 -> 135.
        val page = ByteArray(4096)
        val header = byteArrayOf(0x46, 0x46, 0x58, 0x53, 1, 0, 0, 0x10, 4, 0, 0, 0)
        header.copyInto(page)
        byteArrayOf(
            2, 0, 2, 3, 0xb0.toByte(), 0x07, 0x8c.toByte(), 0x03, 3, 2, 1, 1,
            0, 0, 0x34, 0x42, 0, 0, 0x07, 0x43, 0, 0, 0x07, 0x43, 4, 3, 2, 1,
        ).copyInto(page, 16)
        val s = LuwesStatus.decode(page)!!
        assertEquals(LuwesStatus.State.RUNNING, s.state)
        assertTrue(s.vulkan)
        assertEquals(1968, s.width)
        assertEquals(908, s.height)
        assertEquals(2, s.generated)
        assertTrue(s.paced)
        assertEquals(45f, s.realFps)
        assertEquals(135f, s.presentedFps)
        assertEquals("Vulkan · 45 → 135 fps · paced", s.describe())
    }

    @Test
    fun namesQualcommsEngineAndTheHudCleanScene() {
        val page = ByteArray(4096)
        byteArrayOf(0x46, 0x46, 0x58, 0x53).copyInto(page)
        byteArrayOf(2, 0, 2, 5).copyInto(page, 16)  // running, Vulkan, AFME
        page[16 + 12] = 0; page[16 + 13] = 0; page[16 + 14] = 0x70; page[16 + 15] = 0x42  // 60
        page[16 + 18] = 0x70; page[16 + 19] = 0x43  // 240
        page[16 + 28] = 1
        val s = LuwesStatus.decode(page)!!
        assertTrue(s.afme)
        assertTrue(s.scene)
        assertEquals("Vulkan · 60 → 240 fps · Qualcomm · HUD-clean", s.describe())
    }

    @Test
    fun declinedStatusSaysWhy() {
        val page = ByteArray(4096)
        byteArrayOf(0x46, 0x46, 0x58, 0x53).copyInto(page)
        byteArrayOf(3, 2, 2).copyInto(page, 16)  // declined, format, Vulkan
        assertEquals("Not running: HDR or unsupported output", LuwesStatus.decode(page)!!.describe())
    }

    @Test
    fun aPageNoEngineWroteIsNoStatus() {
        assertNull(LuwesStatus.decode(ByteArray(4096)))
        assertNull(LuwesStatus.decode(ByteArray(0)))
    }
}
