/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.upscale

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What upscaling does inside one game process, from the status page the process writes back
 * (framefx/channel.h, "Upscale status block").
 */
data class UpscaleStatus(
    val state: State,
    val reason: Reason,
    val realFps: Float,
    val sceneWidth: Int,
    val sceneHeight: Int,
    val width: Int,
    val height: Int,
    val gpuMs: Float,
    val method: Method = Method.NONE,
    val drawnWidth: Int = 0,
    val drawnHeight: Int = 0,
) {
    enum class State { OFF, WAITING, RUNNING }

    enum class Reason { NONE, NO_SCENE, FULL_SIZE, LAPIS_OFF, RESOURCES }

    /** How the game's frame reaches the screen's size: framefx/channel.h UpscaleMethod. */
    enum class Method(val label: String) {
        NONE(""), SGSR1("SGSR 1"), SGSR2("SGSR 2"), RESAMPLE("Resampled")
    }

    /** The game draws its frame at another size than the screen's, and framefx resamples it. */
    val resized: Boolean get() = drawnWidth > 0 && drawnHeight > 0

    /** Shrunk from a larger frame: supersampling. */
    val downsampled: Boolean get() = resized && drawnWidth > width

    /** How much larger the output is than the 3D under it; supersampled, the frame than the output. */
    val factor: Float get() = when {
        downsampled -> drawnWidth.toFloat() / width
        sceneWidth > 0 -> width.toFloat() / sceneWidth
        resized -> width.toFloat() / drawnWidth
        else -> 1f
    }

    /** One line for the card. */
    fun describe(): String = when {
        state == State.RUNNING && method == Method.SGSR2 ->
            "SGSR 2 rebuilds the game's 3D at the screen's resolution from its jittered frames; the HUD stays the game's own."
        state == State.RUNNING && downsampled ->
            "The game draws at ${drawnWidth}×$drawnHeight and is scaled down to the screen with a sharp filter."
        state == State.RUNNING && method == Method.RESAMPLE ->
            "The game draws at ${drawnWidth}×$drawnHeight; SGSR scales the whole frame to the screen."
        state == State.RUNNING ->
            "The game draws its 3D smaller than the screen. SGSR sharpens it to full size; the HUD stays the game's own."
        reason == Reason.FULL_SIZE ->
            "The game draws its 3D at full size now. Lower its own render quality to draw less; SGSR then sharpens it back."
        reason == Reason.LAPIS_OFF -> "Needs Lapis, which is off for this game."
        reason == Reason.RESOURCES -> "Not enough GPU memory to upscale at this size."
        else -> "Waiting for the game's 3D."
    }

    companion object {
        private const val MAGIC = 0x53584646 // "FFXS"
        private const val OFFSET = 280
        private const val SIZE = 24

        /** Null for a page no upscaler wrote, or one that is off. */
        fun decode(page: ByteArray): UpscaleStatus? {
            if (page.size < OFFSET + SIZE) return null
            val b = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
            if (b.getInt(0) != MAGIC) return null
            val state = State.entries.getOrNull(b.get(OFFSET).toInt()) ?: return null
            if (state == State.OFF) return null
            return UpscaleStatus(
                state = state,
                reason = Reason.entries.getOrNull(b.get(OFFSET + 1).toInt()) ?: Reason.NONE,
                realFps = (b.getShort(OFFSET + 2).toInt() and 0xffff) / 10f,
                sceneWidth = b.getShort(OFFSET + 4).toInt() and 0xffff,
                sceneHeight = b.getShort(OFFSET + 6).toInt() and 0xffff,
                width = b.getShort(OFFSET + 8).toInt() and 0xffff,
                height = b.getShort(OFFSET + 10).toInt() and 0xffff,
                gpuMs = (b.getInt(OFFSET + 12).toLong() and 0xffffffffL) / 1e6f,
                method = Method.entries.getOrNull(b.get(OFFSET + 16).toInt()) ?: Method.NONE,
                drawnWidth = b.getShort(OFFSET + 18).toInt() and 0xffff,
                drawnHeight = b.getShort(OFFSET + 20).toInt() and 0xffff,
            )
        }
    }
}
