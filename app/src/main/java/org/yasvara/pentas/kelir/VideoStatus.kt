/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.kelir

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What a video app's decoder goes through, from the status page its process writes back
 * (framefx/channel.h, "Video status block"). Untrusted: bounds are checked, values only shown.
 */
data class VideoStatus(
    val state: State,
    val reason: Reason,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val width: Int,
    val height: Int,
    val fps: Float,
    /** GPU time per real frame, its generated frames included. */
    val gpuMicros: Float,
    val frames: Long,
    val dropped: Long,
    val presentedFps: Float,
    val generated: Long,
    val delayMillis: Float,
    val upscaled: Boolean,
    /** By the decoder's SR filter, on the HTP, rather than SGSR alone. */
    val superres: Boolean,
) {
    enum class State { OFF, WAITING, RUNNING, DECLINED }

    enum class Reason(val text: String) {
        NONE(""),
        HDR("HDR video"),
        GPU("out of GPU resources"),
        TARGET("the app's surface refused"),
    }

    companion object {
        private const val MAGIC = 0x53584646 // "FFXS"
        private const val OFFSET = 304
        private const val SIZE = 48

        /** Null for a page no video went through. */
        fun decode(page: ByteArray): VideoStatus? {
            if (page.size < OFFSET + SIZE) return null
            val b = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
            if (b.getInt(0) != MAGIC) return null
            val state = State.entries.getOrNull(b.get(OFFSET).toInt()) ?: return null
            if (state == State.OFF) return null
            return VideoStatus(
                state = state,
                reason = Reason.entries.getOrNull(b.get(OFFSET + 1).toInt()) ?: Reason.NONE,
                sourceWidth = b.getShort(OFFSET + 4).toInt() and 0xffff,
                sourceHeight = b.getShort(OFFSET + 6).toInt() and 0xffff,
                width = b.getShort(OFFSET + 8).toInt() and 0xffff,
                height = b.getShort(OFFSET + 10).toInt() and 0xffff,
                fps = b.getFloat(OFFSET + 12),
                gpuMicros = (b.getInt(OFFSET + 16).toLong() and 0xffffffffL) / 1000f,
                frames = b.getInt(OFFSET + 20).toLong() and 0xffffffffL,
                dropped = b.getInt(OFFSET + 24).toLong() and 0xffffffffL,
                presentedFps = b.getFloat(OFFSET + 32),
                generated = b.getInt(OFFSET + 36).toLong() and 0xffffffffL,
                delayMillis = (b.getShort(OFFSET + 40).toInt() and 0xffff) / 10f,
                upscaled = b.get(OFFSET + 42).toInt() and 1 != 0,
                superres = b.get(OFFSET + 42).toInt() and 2 != 0,
            )
        }
    }
}
