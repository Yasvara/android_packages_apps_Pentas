/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.kelir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KelirStackTest {

    private fun kinds(s: KelirStack) = s.nodes.map { it.kind }

    @Test
    fun addUpToLimits() {
        var s = KelirStack()
        for (k in listOf(KelirKinds.SHARPEN, KelirKinds.DETAILS, KelirKinds.PAINTERLY)) s = s.add(k)
        for (k in listOf(KelirKinds.VIGNETTE, KelirKinds.FILM_GRAIN, KelirKinds.LETTERBOX, KelirKinds.RETRO)) s = s.add(k)
        s = s.add(KelirKinds.COLOR)
        assertEquals(8, s.nodes.size)
    }

    @Test
    fun addRejectsWithReason() {
        var s = KelirStack()
        for (k in listOf(KelirKinds.SHARPEN, KelirKinds.DETAILS, KelirKinds.TILT_SHIFT)) s = s.add(k)
        assertEquals(KelirStack.Refusal.DETAIL_FULL, s.refusal(KelirKinds.PAINTERLY))
        assertSame(s, s.add(KelirKinds.PAINTERLY))
        assertEquals(KelirStack.Refusal.STYLE_TAKEN, KelirStack().add(KelirKinds.PAINTERLY).refusal(KelirKinds.WATERCOLOR))
        var screen = KelirStack()
        for (k in listOf(KelirKinds.VIGNETTE, KelirKinds.FILM_GRAIN, KelirKinds.LETTERBOX, KelirKinds.OLD_FILM)) screen = screen.add(k)
        assertEquals(KelirStack.Refusal.SCREEN_FULL, screen.refusal(KelirKinds.HALFTONE))
        var full = KelirStack()
        repeat(8) { full = full.add(KelirKinds.COLOR) }
        assertEquals(KelirStack.Refusal.FULL, full.refusal(KelirKinds.SEPIA))
    }

    @Test
    fun colourKindsRepeat() {
        val s = KelirStack().add(KelirKinds.COLOR).add(KelirKinds.COLOR).add(KelirKinds.LEVELS).add(KelirKinds.LEVELS)
        assertEquals(4, s.nodes.size)
    }

    @Test
    fun otherKindsOnce() {
        val s = KelirStack().add(KelirKinds.VIGNETTE)
        assertEquals(KelirStack.Refusal.ONCE, s.refusal(KelirKinds.VIGNETTE))
        assertEquals(KelirStack.Refusal.ONCE, KelirStack().add(KelirKinds.RETRO).refusal(KelirKinds.RETRO))
    }

    @Test
    fun addKeepsTheBandsInOrder() {
        val s = KelirStack().add(KelirKinds.VIGNETTE).add(KelirKinds.COLOR).add(KelirKinds.SHARPEN).add(KelirKinds.SEPIA)
        assertEquals(listOf(KelirKinds.SHARPEN, KelirKinds.COLOR, KelirKinds.SEPIA, KelirKinds.VIGNETTE), kinds(s))
    }

    @Test
    fun reorderWithinBand() {
        val s = KelirStack().add(KelirKinds.COLOR).add(KelirKinds.SEPIA).add(KelirKinds.LEVELS)
        assertEquals(listOf(KelirKinds.LEVELS, KelirKinds.COLOR, KelirKinds.SEPIA), kinds(s.move(2, 0)))
    }

    @Test
    fun crossBandMoveSnapsBack() {
        val s = KelirStack().add(KelirKinds.SHARPEN).add(KelirKinds.COLOR).add(KelirKinds.VIGNETTE)
        assertSame(s, s.move(0, 2))
        assertSame(s, s.move(1, 2))
    }

    @Test
    fun removeKeepsOrder() {
        val s = KelirStack().add(KelirKinds.COLOR).add(KelirKinds.SEPIA).add(KelirKinds.LEVELS).remove(1)
        assertEquals(listOf(KelirKinds.COLOR, KelirKinds.LEVELS), kinds(s))
    }

    @Test
    fun enableToggle() {
        val s = KelirStack().add(KelirKinds.SEPIA)
        val off = s.replace(0, s.nodes[0].copy(enabled = false))
        assertEquals(false, off.nodes[0].enabled)
        assertEquals(KelirStack.Refusal.ONCE, off.add(KelirKinds.VIGNETTE).refusal(KelirKinds.VIGNETTE))
    }

    @Test
    fun intensityBounds() {
        val n = KelirNode(KelirKinds.VIGNETTE, intensity = 0.4f)
        val bytes = KelirCodec.encode(KelirStack(listOf(n.copy(intensity = 2f))), KelirCodec.View(), 1)
        assertEquals(0xff.toByte(), bytes[18])
        assertEquals(0xff.toByte(), bytes[19])
        assertEquals(1f, n.with(0, 5f).values[0])
        assertEquals(0f, n.with(0, -5f).values[0])
    }

    @Test
    fun startingStacksAreValid() {
        for ((name, _, stack) in KelirPresets.all) {
            assertTrue(name, stack.nodes.isNotEmpty())
            var rebuilt = KelirStack()
            for (n in stack.nodes) {
                assertNull("$name: ${n.info.name}", rebuilt.refusal(n.kind))
                rebuilt = rebuilt.add(n)
            }
            assertEquals(name, stack, rebuilt)
        }
    }
}
