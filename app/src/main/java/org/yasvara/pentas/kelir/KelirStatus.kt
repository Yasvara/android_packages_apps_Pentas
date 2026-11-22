/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.kelir

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What Kelir is doing inside one game process, from the status page it writes back
 * (framefx/channel.h, "Kelir status block"). Untrusted: bounds are checked, values only shown.
 */
data class KelirStatus(
    val state: State,
    val reason: Reason,
    val vulkan: Boolean,
    val depth: Depth,
    val width: Int,
    val height: Int,
    val turns: Int,
    val path: Path,
    val appliedRevision: Int,
    val rejectedRevision: Int,
    val rejected: Boolean,
    /** GPU time per real frame, while the editor inspects; 0 otherwise. */
    val gpuMicros: Float,
    /** 8 reference colours after the running slot's colour filters, while inspecting. */
    val swatch: List<Int>,
    /** Per stack node, its own luma curve over 17 points; empty outside the colour band. */
    val curves: List<List<Int>>,
) {
    enum class State { OFF, WAITING, RUNNING, DECLINED }

    enum class Reason(val text: String) {
        NONE(""),
        HDR("HDR output"),
        FORMAT("an unsupported output format"),
        PRESERVED("the game keeps its last frame"),
        PRESENT_MODE("the game's present mode"),
        PROTECTED("protected content"),
        RESOURCES("out of GPU resources"),
        GLES_VERSION("OpenGL ES below 3.0"),
    }

    enum class Depth(val bits: Int) { UNORM8(8), SRGB8(8), UNORM10(10) }

    enum class Path { NONE, FETCH, TEXTURE, VULKAN }

    companion object {
        private const val MAGIC = 0x53584646 // "FFXS"
        private const val OFFSET = 64
        private const val SIZE = 200
        private const val SWATCH = 8
        private const val CURVE_POINTS = 17
        private const val NODES = 8

        /** Null for a page no Kelir ever wrote. */
        fun decode(page: ByteArray): KelirStatus? {
            if (page.size < OFFSET + SIZE) return null
            val b = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
            if (b.getInt(0) != MAGIC) return null
            val state = State.entries.getOrNull(b.get(OFFSET).toInt()) ?: return null
            val u8 = { at: Int -> b.get(OFFSET + at).toInt() and 0xff }
            return KelirStatus(
                state = state,
                reason = Reason.entries.getOrNull(u8(1)) ?: Reason.NONE,
                vulkan = u8(2) == 2,
                depth = Depth.entries.getOrNull(u8(3)) ?: Depth.UNORM8,
                width = b.getShort(OFFSET + 4).toInt() and 0xffff,
                height = b.getShort(OFFSET + 6).toInt() and 0xffff,
                turns = u8(8) and 3,
                path = Path.entries.getOrNull(u8(9)) ?: Path.NONE,
                rejected = u8(10) != 0,
                appliedRevision = b.getInt(OFFSET + 12),
                rejectedRevision = b.getInt(OFFSET + 16),
                gpuMicros = (b.getInt(OFFSET + 20).toLong() and 0xffffffffL) / 1000f,
                swatch = List(SWATCH) { i ->
                    (0xff shl 24) or (u8(32 + 3 * i) shl 16) or (u8(33 + 3 * i) shl 8) or u8(34 + 3 * i)
                },
                curves = List(NODES) { n -> List(CURVE_POINTS) { i -> u8(56 + n * CURVE_POINTS + i) } },
            )
        }
    }
}
