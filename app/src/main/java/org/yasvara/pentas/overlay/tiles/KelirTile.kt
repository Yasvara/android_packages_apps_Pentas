/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.tiles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import org.yasvara.pentas.kelir.Kelir
import org.yasvara.pentas.kelir.KelirSlots

/**
 * Kelir's one tap, as Freestyle's hotkey: the game's next slot holding filters, then Off. Its
 * label carries the running slot. With nothing stored, a tap opens the tab instead.
 */
class KelirTile(
    override val id: String,
    private val name: String,
    override val icon: Int,
    private val kelir: Kelir,
    private val pkg: () -> String?,
    private val onOpen: () -> Unit,
) : TileAction {
    private val active = mutableIntStateOf(KelirSlots.OFF)

    override val label: String
        get() = active.intValue.let { if (it == KelirSlots.OFF) name else "$name · ${it + 1}" }

    @Composable
    override fun observeEnabled(): State<Boolean> {
        val stored by kelir.stored.collectAsState()
        val game = pkg()
        LaunchedEffect(stored, game) {
            active.intValue = game?.let { kelir.slots(it).active } ?: KelirSlots.OFF
        }
        return remember { derivedStateOf { active.intValue != KelirSlots.OFF } }
    }

    override fun toggle() {
        val game = pkg() ?: return
        if (kelir.slots(game).slots.all { it.nodes.isEmpty() }) return onOpen()
        active.intValue = kelir.cycle(game).active
    }

    override fun onLongClick() = onOpen()
}
