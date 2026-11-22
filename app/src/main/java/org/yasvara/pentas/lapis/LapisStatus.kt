/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.lapis

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What Lapis found in one game process's frames, from the status page the process writes back
 * (framefx/channel.h, "Lapis status block").
 */
data class LapisStatus(
    val state: State,
    val tier: Tier,
    val roles: Int,
    val miss: Miss,
    val sceneWidth: Int,
    val sceneHeight: Int,
) {
    enum class State { OFF, LEARNING, ENGAGED }

    enum class Tier(val text: String) { NONE(""), A("tier A"), B("tier B"), C("tier C") }

    enum class Miss(val text: String) {
        NONE(""),
        NO_FRAME("nothing drawn"),
        NO_SCENE("no scene on screen"),
        GAME_GENERATES("the game makes its own frames"),
        CHANGED("the frame changed"),
        RESIZED("the frame was resized"),
        UNTONED("the game tonemaps straight to the screen"),
    }

    fun has(role: Int): Boolean = roles and role != 0

    /** One line for the editor; null while Lapis is not recording. */
    fun describe(): String? = when (state) {
        State.OFF -> null
        State.LEARNING -> listOf("Learning", miss.text).filter { it.isNotEmpty() }.joinToString(" · ")
        State.ENGAGED -> listOf(
            "Depth" to (has(DEPTH) && has(KEPT)), "Scene" to has(SCENE),
            "Motion" to has(MOTION), "Camera" to has(CAMERA),
        ).joinToString(" · ") { (name, found) -> "$name ${if (found) "✓" else "—"}" } +
            if (tier != Tier.NONE) " · ${tier.text}" else ""
    }

    companion object {
        const val DEPTH = 1
        const val KEPT = 2
        const val SCENE = 4
        const val MOTION = 8
        const val CAMERA = 16

        private const val MAGIC = 0x53584646 // "FFXS"
        private const val OFFSET = 264
        private const val SIZE = 16

        /** Null for a page Lapis never wrote. */
        fun decode(page: ByteArray): LapisStatus? {
            if (page.size < OFFSET + SIZE) return null
            val b = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
            if (b.getInt(0) != MAGIC) return null
            val state = State.entries.getOrNull(b.get(OFFSET).toInt()) ?: return null
            if (state == State.OFF) return null
            return LapisStatus(
                state = state,
                tier = Tier.entries.getOrNull(b.get(OFFSET + 1).toInt()) ?: Tier.NONE,
                roles = b.get(OFFSET + 2).toInt() and 0xff,
                miss = Miss.entries.getOrNull(b.get(OFFSET + 3).toInt()) ?: Miss.NONE,
                sceneWidth = b.getShort(OFFSET + 4).toInt() and 0xffff,
                sceneHeight = b.getShort(OFFSET + 6).toInt() and 0xffff,
            )
        }
    }
}
