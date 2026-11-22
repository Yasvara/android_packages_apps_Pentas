/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.video

/**
 * What a video app's decoder goes through besides Kelir: generated frames up to a display rate,
 * and upscaling to the size it is shown at.
 *
 * On the wire this is the Video section of the app's framefx channel
 * (frameworks/libs/framefx/include/framefx/channel.h, "Video section v1").
 */
data class VideoSettings(
    /** The display rate to generate up to, 0 for none. */
    val motionHz: Int = 0,
    val upscale: Boolean = false,
    /** Qualcomm's AFME over Luwes's own motion: one frame between every two. */
    val afme: Boolean = false,
    /** 1 follows the motion all the way; less keeps some of the film's cadence. */
    val smoothness: Float = 1f,
) {
    val on: Boolean get() = motionHz != 0 || upscale

    /** The channel section. The display's size says what a surface fixed at the video's is shown at. */
    fun encode(displayLong: Int, displayShort: Int): ByteArray = byteArrayOf(
        MAJOR.toByte(), motionHz.toByte(),
        ((if (upscale) FLAG_UPSCALE else 0) or (if (afme) FLAG_AFME else 0)).toByte(),
        (smoothness.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte(),
        displayLong.toByte(), (displayLong shr 8).toByte(),
        displayShort.toByte(), (displayShort shr 8).toByte(),
    )

    companion object {
        const val SECTION_ID = 7

        private const val SIZE = 8
        private const val MAJOR = 1
        private const val FLAG_UPSCALE = 1
        private const val FLAG_AFME = 2

        /** No section, or a malformed one like the engine reads it, is neither. */
        fun decode(bytes: ByteArray?): VideoSettings {
            if (bytes == null || bytes.size != SIZE || bytes[0].toInt() != MAJOR) return VideoSettings()
            return VideoSettings(
                motionHz = bytes[1].toInt() and 0xff,
                upscale = bytes[2].toInt() and FLAG_UPSCALE != 0,
                afme = bytes[2].toInt() and FLAG_AFME != 0,
                smoothness = (bytes[3].toInt() and 0xff) / 255f,
            )
        }
    }
}
