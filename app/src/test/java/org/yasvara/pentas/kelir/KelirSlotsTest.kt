/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.kelir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KelirSlotsTest {

    private fun cycled(start: KelirSlots, times: Int): List<Int> {
        var s = start
        return List(times) { s = s.cycle(); s.active }
    }

    @Test
    fun threeSlotsPlusOff() {
        val full = KelirSlots(List(KelirSlots.COUNT) { KelirStack().add(KelirKinds.SEPIA) })
        assertEquals(3, full.slots.size)
        assertNull(full.running)
        assertEquals(listOf(0, 1, 2, KelirSlots.OFF), cycled(full, 4))
        assertEquals(KelirSlots.OFF, full.select(5).active)
    }

    @Test
    fun cycleSkipsEmptySlots() {
        assertEquals(listOf(KelirSlots.OFF, KelirSlots.OFF), cycled(KelirSlots(), 2))
        val oneAndThree = KelirSlots().edit(0, KelirStack().add(KelirKinds.SEPIA)).edit(2, KelirStack().add(KelirKinds.VIGNETTE))
        assertEquals(listOf(0, 2, KelirSlots.OFF, 0), cycled(oneAndThree, 4))
    }

    @Test
    fun switchEncodesActiveOnly() {
        val s = KelirSlots()
            .edit(0, KelirStack().add(KelirKinds.SEPIA))
            .edit(1, KelirStack().add(KelirKinds.VIGNETTE).add(KelirKinds.COLOR))
            .select(1)
        val bytes = KelirCodec.encode(s.running!!, KelirCodec.View(), 1)
        assertEquals(2, bytes[3].toInt())
        assertEquals(KelirKinds.COLOR, bytes[16].toInt())
        assertEquals(KelirKinds.VIGNETTE, bytes[36].toInt())
    }

    @Test
    fun copyFromGame() {
        val other = KelirSlots().edit(2, KelirStack().add(KelirKinds.NIGHT_MODE))
        val mine = KelirSlots().copyFrom(other, 2, 0)
        assertEquals(other.slots[2], mine.slots[0])
        assertEquals(KelirStack(), mine.slots[2])
    }

    @Test
    fun resetSlot() {
        val s = KelirSlots().edit(1, KelirStack().add(KelirKinds.SEPIA)).duplicate(1, 2).reset(1)
        assertEquals(KelirStack(), s.slots[1])
        assertEquals(1, s.slots[2].nodes.size)
    }
}
