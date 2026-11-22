/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.kelir

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The Kelir section of a game's framefx channel (framefx/channel.h, "Kelir section v1"): a 16-byte
 * header and 20 bytes per node, little-endian. Only the active slot travels; Off is no section.
 */
object KelirCodec {
    const val SECTION_ID = 2
    /** The same layout, for what a video app's decoder renders. Its own UI stays out of it. */
    const val VIDEO_SECTION_ID = 6
    const val MAJOR = 1
    const val MINOR = 0
    const val HEADER = 16
    const val NODE = 20

    const val FLAG_PEEK = 1
    const val FLAG_SPLIT = 2
    const val FLAG_INSPECT = 4

    /** Editor state that rides along with the active slot. */
    data class View(
        val peek: Boolean = false,
        /** Display x of the A/B divider in [0, 1], or null without the split. */
        val split: Float? = null,
        /** The editor is open: the game measures and reports curves. */
        val inspect: Boolean = false,
    )

    fun encode(stack: KelirStack, view: View, revision: Int): ByteArray {
        val b = ByteBuffer.allocate(HEADER + NODE * stack.nodes.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(MAJOR.toByte())
        b.put(MINOR.toByte())
        b.put(((if (view.peek) FLAG_PEEK else 0) or (if (view.split != null) FLAG_SPLIT else 0) or
            (if (view.inspect) FLAG_INSPECT else 0)).toByte())
        b.put(stack.nodes.size.toByte())
        b.putShort(unorm(view.split ?: 0f).toShort())
        b.putShort(0)
        b.putInt(revision)
        b.putInt(0)
        for (n in stack.nodes) {
            b.put(n.kind.toByte())
            b.put((if (n.enabled) 1 else 0).toByte())
            b.putShort(unorm(n.intensity).toShort())
            val params = n.info.params
            for (i in 0 until PARAMS) {
                b.putShort((if (i < params.size) params[i].encode(n.values[i]) else 0).toShort())
            }
        }
        return b.array()
    }

    private fun unorm(v: Float): Int = (v.coerceIn(0f, 1f) * 65535f).roundToInt()

    const val PARAMS = 8
}
