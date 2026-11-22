/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.lapis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LapisTest {

    @Test
    fun autoIsNoSection() {
        assertEquals(0, LapisSettings().encode().size)
        assertTrue(LapisSettings.decode(null).on)
        assertTrue(LapisSettings.decode(ByteArray(0)).on)
    }

    @Test
    fun offIsTheSectionFramefxDecodes() {
        // framefx's channel_test decodes these bytes as off.
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), LapisSettings(LapisSettings.Mode.OFF).encode())
        assertFalse(LapisSettings.decode(byteArrayOf(1, 0, 0, 0)).on)
        assertTrue(LapisSettings.decode(byteArrayOf(1, 1, 0, 0)).on)
    }

    @Test
    fun anythingMalformedIsOffLikeTheEngine() {
        for (bytes in listOf(
            byteArrayOf(1, 1, 0), byteArrayOf(1, 1, 0, 0, 0), byteArrayOf(2, 1, 0, 0),
            byteArrayOf(1, 2, 0, 0), byteArrayOf(1, 1, 8, 0), byteArrayOf(1, 1, 0, 1),
        )) {
            assertFalse(LapisSettings.decode(bytes).on)
        }
    }

    @Test
    fun wholeScreenIsTheBitFramefxDecodes() {
        // framefx's channel_test decodes {1, 1, kLapisWholeScreen = 4, 0} as the whole screen.
        assertArrayEquals(byteArrayOf(1, 1, 4, 0), LapisSettings(wholeScreen = true).encode())
        val s = LapisSettings.decode(byteArrayOf(1, 1, 5, 0))
        assertTrue(s.wholeScreen && s.hideHud && s.on)
        assertFalse(LapisSettings.decode(byteArrayOf(1, 1, 1, 0)).wholeScreen)
    }

    private fun page(block: ByteArray): ByteArray {
        val page = ByteArray(512)
        ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0x53584646)
        block.copyInto(page, 264)
        return page
    }

    @Test
    fun decodesTheStatusBlock() {
        // framefx's channel_test writes this block for an engaged tier A frame at 2400x1080.
        val s = LapisStatus.decode(page(byteArrayOf(2, 1, 7, 0, 0x60, 0x09, 0x38, 0x04)))!!
        assertEquals(LapisStatus.State.ENGAGED, s.state)
        assertEquals(LapisStatus.Tier.A, s.tier)
        assertEquals(2400, s.sceneWidth)
        assertEquals(1080, s.sceneHeight)
        assertEquals("Depth ✓ · Scene ✓ · Motion — · Camera — · tier A", s.describe())
    }

    @Test
    fun learningSaysWhy() {
        val s = LapisStatus.decode(page(byteArrayOf(1, 3, 0, 2)))!!
        assertEquals("Learning · no scene on screen", s.describe())
    }

    @Test
    fun offOrForeignPagesAreNull() {
        assertNull(LapisStatus.decode(page(ByteArray(16))))
        assertNull(LapisStatus.decode(ByteArray(512)))
        assertNull(LapisStatus.decode(ByteArray(100)))
    }

    @Test
    fun textTapRoundTripsAndKeepsItsSection() {
        val tapped = LapisSettings(textTap = true)
        assertArrayEquals(byteArrayOf(1, 1, 2, 0), tapped.encode())
        assertEquals(tapped, LapisSettings.decode(tapped.encode()))
        assertEquals(LapisSettings(hideHud = true, textTap = true), LapisSettings.decode(byteArrayOf(1, 1, 3, 0)))
        assertEquals(LapisSettings.Mode.OFF, LapisSettings.decode(byteArrayOf(1, 1, 8, 0)).mode)
    }
}
