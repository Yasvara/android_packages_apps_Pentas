/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.lepas

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LepasSettingsTest {

    @Test
    fun encodesTheChannelSectionByteForByte() {
        // The broker's lepasSection and framefx's channel_test use these exact bytes.
        assertArrayEquals(byteArrayOf(1, 120.toByte(), 0, 0), LepasSettings(120).encode())
        assertArrayEquals(byteArrayOf(1, 90, 0, 0), LepasSettings(90).encode())
        assertArrayEquals(byteArrayOf(1, 240.toByte(), 0, 0), LepasSettings(240).encode())
    }

    @Test
    fun offIsNoSection() {
        assertFalse(LepasSettings().on)
        assertEquals(0, LepasSettings().encode().size)
    }

    @Test
    fun roundTrips() {
        for (t in LepasSettings.TARGETS) {
            assertEquals(LepasSettings(t), LepasSettings.decode(LepasSettings(t).encode()))
        }
        assertTrue(LepasSettings.decode(byteArrayOf(1, 120.toByte(), 0, 0)).on)
    }

    @Test
    fun decodesAnythingElseAsTheGamesCap() {
        assertEquals(LepasSettings(), LepasSettings.decode(null))
        assertEquals(LepasSettings(), LepasSettings.decode(ByteArray(0)))
        assertEquals(LepasSettings(), LepasSettings.decode(byteArrayOf(2, 120.toByte(), 0, 0)))
        assertEquals(LepasSettings(), LepasSettings.decode(byteArrayOf(1, 120.toByte(), 1, 0)))
        assertEquals(LepasSettings(), LepasSettings.decode(byteArrayOf(1, 10, 0, 0)))
        assertEquals(LepasSettings(), LepasSettings.decode(byteArrayOf(1, 120.toByte(), 0)))
    }

    @Test
    fun sectionIdMatchesTheBroker() {
        // FramefxBroker.LEPAS_SECTION and framefx's kSectionLepas.
        assertEquals(8, LepasSettings.SECTION_ID)
    }
}
