/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.kelir

import org.junit.Assert.assertEquals
import org.junit.Test

class KelirLiveTest {

    private class FakeClock : KelirLive.Clock {
        var time = 0L
        private val due = mutableListOf<Pair<Long, () -> Unit>>()
        override fun now() = time
        override fun after(ms: Long, block: () -> Unit) {
            due += time + ms to block
        }

        fun advance(ms: Long) {
            time += ms
            val ready = due.filter { it.first <= time }
            due.removeAll(ready)
            ready.forEach { it.second() }
        }
    }

    private fun edit(intensity: Float) = KelirLive.Edit(
        "com.game",
        KelirSlots().edit(0, KelirStack().add(KelirNode(KelirKinds.SEPIA, intensity = intensity))).select(0),
        KelirCodec.View(inspect = true),
    )

    @Test
    fun coalescesDragToFrameRate() {
        val clock = FakeClock()
        val published = mutableListOf<KelirLive.Edit>()
        val live = KelirLive(clock, frameMs = 16, publish = { published += it }, persist = {})
        // 100 moves over 160 ms: at most one publish per 16 ms frame.
        for (i in 0 until 100) {
            live.preview(edit(i / 100f))
            clock.advance(if (i % 10 == 9) 16 else 0)
        }
        clock.advance(16)
        assertEquals(true, published.size in 10..12)
    }

    @Test
    fun lastValueAlwaysDelivered() {
        val clock = FakeClock()
        val published = mutableListOf<KelirLive.Edit>()
        val live = KelirLive(clock, frameMs = 16, publish = { published += it }, persist = {})
        live.preview(edit(0.1f))
        live.preview(edit(0.2f))
        live.preview(edit(0.3f))
        clock.advance(16)
        assertEquals(0.3f, published.last().slots.running!!.nodes[0].intensity)
    }

    @Test
    fun persistsOnCommitOnly() {
        val clock = FakeClock()
        val stored = mutableListOf<KelirLive.Edit>()
        val live = KelirLive(clock, frameMs = 16, publish = {}, persist = { stored += it })
        repeat(20) {
            live.preview(edit(it / 20f))
            clock.advance(5)
        }
        assertEquals(0, stored.size)
        live.commit(edit(0.9f))
        assertEquals(listOf(edit(0.9f)), stored)
    }
}
