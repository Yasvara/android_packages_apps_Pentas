/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.kelir

/**
 * Live edits from a drag: at most one publish per editor frame, and the newest value always goes
 * out, so the game follows the finger without being flooded. Storage happens only on commit.
 */
class KelirLive(
    private val clock: Clock,
    private val frameMs: Long,
    private val publish: (Edit) -> Unit,
    private val persist: (Edit) -> Unit,
) {
    interface Clock {
        fun now(): Long

        /** Runs [block] once after [ms]. */
        fun after(ms: Long, block: () -> Unit)
    }

    data class Edit(val pkg: String, val slots: KelirSlots, val view: KelirCodec.View)

    private var pending: Edit? = null
    private var scheduled = false
    private var lastPublish = Long.MIN_VALUE / 2

    /** A value while dragging. */
    fun preview(edit: Edit) {
        pending = edit
        if (scheduled) return
        val wait = lastPublish + frameMs - clock.now()
        if (wait <= 0) {
            flush()
        } else {
            scheduled = true
            clock.after(wait) {
                scheduled = false
                flush()
            }
        }
    }

    /** The final value: published now and stored. */
    fun commit(edit: Edit) {
        pending = edit
        flush()
        persist(edit)
    }

    private fun flush() {
        val edit = pending ?: return
        pending = null
        lastPublish = clock.now()
        publish(edit)
    }
}
