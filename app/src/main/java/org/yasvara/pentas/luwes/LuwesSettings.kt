/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.luwes

import java.util.Locale

/**
 * Luwes, per game: how many frames reach the panel for each frame the game renders, how the
 * extra ones are made, whether screen-fixed HUD is kept out of them, and whose motion estimation
 * makes them. Everything else (phase, pacing, panel calibration) the engine decides on its own.
 *
 * On the wire this is the Luwes section of the game's framefx channel
 * (frameworks/libs/framefx/include/framefx/channel.h, "Luwes section v1"). Off is no section,
 * so a game with nothing else on is not armed at all.
 */
data class LuwesSettings(
    val multiplier: Int = OFF,
    val mode: Mode = Mode.EXTRAPOLATE,
    val hudProtection: Boolean = true,
    val engine: Engine = Engine.QUALCOMM,
) {
    enum class Mode(val wire: Int) {
        /** The real frame first, generated ones projected past it: no added latency. */
        EXTRAPOLATE(0),

        /** Generated frames between the last two real ones: smoother, one interval later. */
        INTERPOLATE(1),
    }

    enum class Engine {
        /** Qualcomm's AFME on Adreno's optical flow hardware, where the device has it. */
        QUALCOMM,

        /** Luwes's own block search, on any GPU. */
        LUWES,
    }

    val on: Boolean get() = multiplier in MIN..MAX

    /** The channel section; empty removes it. */
    fun encode(): ByteArray {
        if (!on) return ByteArray(0)
        return byteArrayOf(
            MAJOR.toByte(), multiplier.toByte(), mode.wire.toByte(),
            ((if (hudProtection) FLAG_HUD else 0) or (if (engine == Engine.LUWES) FLAG_OWN else 0)).toByte(),
            0, 0, 0, 0,
        )
    }

    companion object {
        const val SECTION_ID = 1
        const val OFF = 0
        const val MIN = 2
        const val MAX = 4

        /** Lowest real frame rate generation runs from. */
        const val MIN_BASE_FPS = 30f

        private const val SIZE = 8
        private const val MAJOR = 1
        private const val FLAG_HUD = 1
        private const val FLAG_OWN = 2

        /** Strict, like the engine: anything but a well-formed section reads as off. */
        fun decode(bytes: ByteArray?): LuwesSettings {
            if (bytes == null || bytes.size != SIZE || bytes[0].toInt() != MAJOR) return LuwesSettings()
            val multiplier = bytes[1].toInt()
            val mode = Mode.entries.firstOrNull { it.wire == bytes[2].toInt() }
            val flags = bytes[3].toInt() and 0xff
            if (multiplier !in MIN..MAX || mode == null || flags and (FLAG_HUD or FLAG_OWN).inv() != 0 ||
                (4 until SIZE).any { bytes[it].toInt() != 0 }
            ) {
                return LuwesSettings()
            }
            return LuwesSettings(
                multiplier, mode, flags and FLAG_HUD != 0,
                if (flags and FLAG_OWN != 0) Engine.LUWES else Engine.QUALCOMM,
            )
        }

        /** Multipliers worth offering on a [hz] panel: the base never drops under 30 fps. */
        fun multipliers(hz: Float): List<Int> = (MIN..MAX).filter { hz / it >= MIN_BASE_FPS - 1e-3f }

        /** What the engine runs for [multiplier] on a [hz] panel; 1 means none. */
        fun effective(multiplier: Int, hz: Float): Int {
            var m = multiplier.coerceIn(1, MAX)
            while (m > 1 && hz / m < MIN_BASE_FPS - 1e-3f) m--
            return m
        }

        /** "45 → 135": the game's held rate and what reaches the panel. */
        fun cadence(multiplier: Int, hz: Float): String = "${fps(hz / multiplier)} → ${fps(hz)}"

        /** A frame rate as short as it is exact: 135, 67.5, 33.75. */
        fun fps(value: Float): String =
            String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    }
}
