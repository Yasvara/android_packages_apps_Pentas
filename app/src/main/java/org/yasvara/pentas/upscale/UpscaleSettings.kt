/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.upscale

/**
 * Upscaling, per game: Qualcomm's SGSR in place of the game's own stretch of its 3D under its
 * HUD, wherever the game draws its 3D smaller than its HUD.
 *
 * On the wire this is the Upscale section of the game's framefx channel
 * (frameworks/libs/framefx/include/framefx/channel.h, "Upscale section v1"): the switch, which
 * filter, and how sharp. No section is auto
 * while Luwes or Kelir arms the game; a section written on arms the game by itself; off only
 * qualifies a game armed for something else.
 */
data class UpscaleSettings(
    val mode: Mode = Mode.AUTO,
    val written: Boolean = false,
    val filter: Filter = Filter.BEST,
    /** 0 leaves the filter its own sharpness; 1 soft to 255 crisp. */
    val sharpness: Int = 0,
) {
    enum class Mode(val wire: Int) { OFF(0), AUTO(1) }

    /** BEST is SGSR 2 where the game's anti-aliasing offers what it reads, SGSR 1 otherwise. */
    enum class Filter(val wire: Int) { BEST(0), SPATIAL(1) }

    val on: Boolean get() = mode != Mode.OFF

    /** Sharpness as the slider shows it, 0 to 1; the filter's own sits in the middle. */
    val sharpnessShare: Float get() = if (sharpness == 0) 0.5f else (sharpness - 1) / 254f

    fun withSharpnessShare(share: Float): UpscaleSettings =
        copy(sharpness = 1 + (share.coerceIn(0f, 1f) * 254f).toInt())

    /** The channel section. */
    fun encode(): ByteArray =
        byteArrayOf(MAJOR.toByte(), mode.wire.toByte(), filter.wire.toByte(), sharpness.toByte())

    companion object {
        const val SECTION_ID = 4

        private const val SIZE = 4
        private const val MAJOR = 1

        /** No section is auto, unwritten; like the engine, a malformed one is off. */
        fun decode(bytes: ByteArray?): UpscaleSettings {
            if (bytes == null || bytes.isEmpty()) return UpscaleSettings()
            val mode = Mode.entries.firstOrNull { it.wire == bytes.getOrNull(1)?.toInt() }
            val filter = Filter.entries.firstOrNull { it.wire == bytes.getOrNull(2)?.toInt() }
            if (bytes.size != SIZE || bytes[0].toInt() != MAJOR || mode == null || filter == null) {
                return UpscaleSettings(Mode.OFF, written = true)
            }
            return UpscaleSettings(mode, written = true, filter, bytes[3].toInt() and 0xff)
        }
    }
}
