/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.lapis

/**
 * Lapis, per game: whether framefx learns the game's own frame (its depth, the frame before its
 * HUD, motion vectors and camera) for the features that read it, Auto by default; Hide HUD,
 * the game's 3D shown alone; and where Kelir grades: the game's 3D under its HUD where Lapis has
 * it, or the whole finished screen.
 *
 * On the wire this is the Lapis section of the game's framefx channel
 * (frameworks/libs/framefx/include/framefx/channel.h, "Lapis section v1"). Auto with the HUD
 * shown is no section.
 */
data class LapisSettings(
    val mode: Mode = Mode.AUTO,
    val hideHud: Boolean = false,
    /** Alih reads the game's text and replaces it. Takes effect at the game's next start. */
    val textTap: Boolean = false,
    /** Kelir on the whole screen at the last stage, HUD included, never switching to the 3D. */
    val wholeScreen: Boolean = false,
) {
    enum class Mode(val wire: Int) { OFF(0), AUTO(1) }

    val on: Boolean get() = mode != Mode.OFF

    /** The channel section; empty removes it. */
    fun encode(): ByteArray =
        if (mode == Mode.AUTO && !hideHud && !textTap && !wholeScreen) {
            ByteArray(0)
        } else {
            val flags = (if (hideHud) HIDE_HUD else 0) or (if (textTap) TEXT_TAP else 0) or
                (if (wholeScreen) WHOLE_SCREEN else 0)
            byteArrayOf(MAJOR.toByte(), mode.wire.toByte(), flags.toByte(), 0)
        }

    companion object {
        const val SECTION_ID = 3

        private const val SIZE = 4
        private const val MAJOR = 1
        private const val HIDE_HUD = 1
        const val TEXT_TAP = 2
        private const val WHOLE_SCREEN = 4
        private const val FLAGS = HIDE_HUD or TEXT_TAP or WHOLE_SCREEN

        /** No section is Auto; like the engine, a malformed one is off. */
        fun decode(bytes: ByteArray?): LapisSettings {
            if (bytes == null || bytes.isEmpty()) return LapisSettings()
            val mode = Mode.entries.firstOrNull { it.wire == bytes.getOrNull(1)?.toInt() }
            if (bytes.size != SIZE || bytes[0].toInt() != MAJOR || mode == null ||
                bytes[2].toInt() and FLAGS.inv() != 0 || bytes[3].toInt() != 0
            ) {
                return LapisSettings(Mode.OFF)
            }
            val flags = bytes[2].toInt()
            return LapisSettings(mode, flags and HIDE_HUD != 0, flags and TEXT_TAP != 0, flags and WHOLE_SCREEN != 0)
        }
    }
}
