/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.upscale

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UpscaleTest {

    @Test
    fun noSectionIsAutoButUnwritten() {
        val s = UpscaleSettings.decode(null)
        assertTrue(s.on)
        assertFalse(s.written)
    }

    @Test
    fun sectionsMatchWhatFramefxDecodes() {
        // framefx's channel_test decodes these as auto and off.
        assertArrayEquals(byteArrayOf(1, 1, 0, 0), UpscaleSettings().encode())
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), UpscaleSettings(UpscaleSettings.Mode.OFF).encode())
        assertEquals(UpscaleSettings(UpscaleSettings.Mode.AUTO, true), UpscaleSettings.decode(byteArrayOf(1, 1, 0, 0)))
        assertFalse(UpscaleSettings.decode(byteArrayOf(1, 0, 0, 0)).on)
    }

    @Test
    fun filterAndSharpnessRoundTrip() {
        val s = UpscaleSettings(filter = UpscaleSettings.Filter.SPATIAL).withSharpnessShare(1f)
        assertArrayEquals(byteArrayOf(1, 1, 1, 255.toByte()), s.encode())
        assertEquals(s.copy(written = true), UpscaleSettings.decode(s.encode()))
        assertEquals(0.5f, UpscaleSettings().sharpnessShare)
    }

    @Test
    fun anythingMalformedIsOffLikeTheEngine() {
        for (bytes in listOf(
            byteArrayOf(1, 1, 0), byteArrayOf(1, 1, 0, 0, 0), byteArrayOf(2, 1, 0, 0),
            byteArrayOf(1, 2, 0, 0), byteArrayOf(1, 1, 4, 0),
        )) {
            val s = UpscaleSettings.decode(bytes)
            assertFalse(s.on)
            assertTrue(s.written)
        }
    }

    private fun page(block: ByteArray): ByteArray {
        val page = ByteArray(512)
        ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0x53584646)
        block.copyInto(page, 280)
        return page
    }

    @Test
    fun decodesTheStatusBlock() {
        // framefx's channel_test writes this block: running, 60 fps, 1124x518 under 2119x979, 0.61 ms.
        val s = UpscaleStatus.decode(page(byteArrayOf(
            2, 0, 0x58, 0x02, 0x64, 0x04, 0x06, 0x02, 0x47, 0x08, 0xd3.toByte(), 0x03,
            0xd0.toByte(), 0x4e, 0x09, 0x00,
        )))!!
        assertEquals(UpscaleStatus.State.RUNNING, s.state)
        assertEquals(60f, s.realFps)
        assertEquals(1124, s.sceneWidth)
        assertEquals(518, s.sceneHeight)
        assertEquals(2119, s.width)
        assertEquals(979, s.height)
        assertEquals(0.61f, s.gpuMs, 1e-6f)
        assertEquals("1.89", String.format(java.util.Locale.ROOT, "%.2f", s.factor))
    }

    @Test
    fun waitingSaysWhy() {
        val s = UpscaleStatus.decode(page(byteArrayOf(1, 2)))!!
        assertEquals(UpscaleStatus.Reason.FULL_SIZE, s.reason)
        assertTrue(s.describe().startsWith("The game draws its 3D at full size"))
    }

    @Test
    fun offOrForeignPagesAreNull() {
        assertNull(UpscaleStatus.decode(page(ByteArray(16))))
        assertNull(UpscaleStatus.decode(ByteArray(512)))
    }
}
