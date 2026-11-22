/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.tiles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import org.yasvara.pentas.lapis.LapisStatus
import org.yasvara.pentas.luwes.Luwes

/**
 * Hide HUD, for clean footage: the game's 3D alone, with Kelir and Luwes still on it. It needs
 * the frame before the HUD from Lapis, and lasts until the game's session ends.
 */
class HideHudTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val luwes: Luwes,
    private val pkg: () -> String?,
    private val onRefused: (restart: Boolean) -> Unit,
) : TileAction {
    private val hidden = mutableStateOf(false)

    @Composable
    override fun observeEnabled(): State<Boolean> {
        val game = pkg()
        LaunchedEffect(game) { hidden.value = game != null && luwes.lapis(game).hideHud }
        return hidden
    }

    override fun toggle() {
        val game = pkg() ?: return
        val next = !hidden.value
        if (next) {
            val pages = luwes.lapisStatus(game).filterNotNull()
            // Still learning may yet find the scene; engaged without it, or off, never will.
            val scene = pages.any {
                it.state == LapisStatus.State.LEARNING ||
                    (it.state == LapisStatus.State.ENGAGED && it.has(LapisStatus.SCENE))
            }
            if (pages.isNotEmpty() && !scene) return onRefused(false)
            if (!luwes.setHideHud(game, true)) return
            hidden.value = true
            // Not armed yet: the section waits for the next start, past this session's end.
            if (pages.isEmpty()) {
                luwes.holdHideHudForRestart(game)
                onRefused(true)
            }
        } else if (luwes.setHideHud(game, false)) {
            hidden.value = false
        }
    }
}
