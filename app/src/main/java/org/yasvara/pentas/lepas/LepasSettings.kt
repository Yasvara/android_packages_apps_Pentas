/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.lepas

/**
 * Panel rate, per game: the game's own fps cap lifted to the panel's rate. lepasd finds the value
 * the game caps itself with from outside the game and holds it at the target; the broker moves the
 * game's window to the target. Applies from the game's next start.
 *
 * On the wire this is the Lepas section of the game's framefx channel
 * (frameworks/libs/framefx/include/framefx/channel.h, "Lepas section v1"); none is the game's cap.
 */
data class LepasSettings(val targetFps: Int = 0) {
    val on: Boolean get() = targetFps > 0

    /** The channel section; empty removes it. */
    fun encode(): ByteArray =
        if (!on) ByteArray(0) else byteArrayOf(MAJOR.toByte(), targetFps.toByte(), 0, 0)

    companion object {
        const val SECTION_ID = 8
        /** Game's, 90, 120. */
        val TARGETS = listOf(0, 90, 120)

        private const val SIZE = 4
        private const val MAJOR = 1

        fun decode(bytes: ByteArray?): LepasSettings {
            if (bytes == null || bytes.size != SIZE || bytes[0].toInt() != MAJOR ||
                bytes[2].toInt() != 0 || bytes[3].toInt() != 0) {
                return LepasSettings()
            }
            val target = bytes[1].toInt() and 0xff
            return if (target in 30..240) LepasSettings(target) else LepasSettings()
        }
    }
}
