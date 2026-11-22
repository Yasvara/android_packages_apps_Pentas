/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.luwes

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What Luwes is doing inside one game process, from the status page the process writes back
 * (framefx/channel.h, "Luwes status block").
 */
data class LuwesStatus(
    val state: State,
    val reason: Reason,
    val vulkan: Boolean,
    val width: Int,
    val height: Int,
    val generated: Int,
    val paced: Boolean,
    val realFps: Float,
    val presentedFps: Float,
    val afme: Boolean = false,
    val scene: Boolean = false,
) {
    enum class State { OFF, WAITING, RUNNING, DECLINED }

    enum class Reason(val text: String) {
        NONE(""),
        NO_MOTION_SOURCE("this GPU has no motion estimation"),
        FORMAT("HDR or unsupported output"),
        PRESENT_MODE("the game's present mode"),
        RESOURCES("out of GPU resources"),
    }

    /** One line for the editor. */
    fun describe(): String = when (state) {
        State.OFF -> "Off"
        State.WAITING -> "Waiting for the game to render"
        State.DECLINED -> "Not running: ${reason.text}"
        State.RUNNING -> buildString {
            append(if (vulkan) "Vulkan" else "GLES")
            append(" · ${LuwesSettings.fps(realFps)} → ${LuwesSettings.fps(presentedFps)} fps")
            if (afme) append(" · Qualcomm")
            if (scene) append(" · HUD-clean")
            if (paced) append(" · paced")
        }
    }

    companion object {
        private const val MAGIC = 0x53584646 // "FFXS"
        private const val OFFSET = 16
        private const val SOURCE_AFME = 5
        private const val FLAG_SCENE = 1

        /** Null for a page no Luwes ever wrote. */
        fun decode(page: ByteArray): LuwesStatus? {
            if (page.size < OFFSET + 48) return null
            val b = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
            if (b.getInt(0) != MAGIC) return null
            val state = State.entries.getOrNull(b.get(OFFSET).toInt()) ?: return null
            if (state == State.OFF && b.get(OFFSET + 2).toInt() == 0) return null
            return LuwesStatus(
                state = state,
                reason = Reason.entries.getOrNull(b.get(OFFSET + 1).toInt()) ?: Reason.NONE,
                vulkan = b.get(OFFSET + 2).toInt() == 2,
                width = b.getShort(OFFSET + 4).toInt() and 0xffff,
                height = b.getShort(OFFSET + 6).toInt() and 0xffff,
                generated = b.get(OFFSET + 9).toInt(),
                paced = b.get(OFFSET + 11).toInt() != 0,
                realFps = b.getFloat(OFFSET + 12),
                presentedFps = b.getFloat(OFFSET + 16),
                afme = b.get(OFFSET + 3).toInt() == SOURCE_AFME,
                scene = b.get(OFFSET + 28).toInt() and FLAG_SCENE != 0,
            )
        }
    }
}
