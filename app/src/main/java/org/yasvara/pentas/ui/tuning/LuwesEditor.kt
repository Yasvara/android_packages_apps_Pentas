/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.ui.tuning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.yasvara.pentas.lapis.LapisSettings
import org.yasvara.pentas.luwes.LuwesSettings
import org.yasvara.pentas.luwes.LuwesSettings.Engine
import org.yasvara.pentas.luwes.LuwesSettings.Mode
import org.yasvara.pentas.luwes.LuwesStatus
import org.yasvara.pentas.ui.kit.Block
import org.yasvara.pentas.ui.kit.Section
import org.yasvara.pentas.ui.kit.Segmented
import org.yasvara.pentas.ui.kit.SettingRow
import org.yasvara.pentas.ui.kit.SwitchRow
import org.yasvara.pentas.ui.kit.label

/**
 * Luwes frame generation for one game: the multiplier against the live panel rate, the mode, HUD
 * protection, the motion engine where Qualcomm's is installed, and Lapis, which hands the engine
 * the game's frame before its HUD. Shown only where the engine and a GPU motion source exist.
 */
@Composable
fun LuwesEditor(pkg: String) {
    val luwes = rememberTuning().luwes()
    val available by produceState(false) { value = luwes.available.await() }
    if (!available) return
    val cs = MaterialTheme.colorScheme
    val hz = remember { luwes.panelHz() }
    var settings by remember(pkg) { mutableStateOf(luwes.settings(pkg)) }
    var lapis by remember(pkg) { mutableStateOf(luwes.lapis(pkg)) }
    var status by remember(pkg) { mutableStateOf<String?>(null) }
    var lapisStatus by remember(pkg) { mutableStateOf<String?>(null) }
    var restart by remember(pkg) { mutableStateOf(false) }
    LaunchedEffect(pkg, settings, lapis) {
        while (true) {
            val polled = withContext(Dispatchers.IO) {
                if (!settings.on) return@withContext Poll()
                val running = luwes.status(pkg).filterNotNull().maxByOrNull { it.state.ordinal }
                val found = luwes.lapisStatus(pkg).filterNotNull().maxByOrNull { it.state.ordinal }
                // Lapis decides whether to record at the game's start: turned on later, it waits
                // for the next one.
                val lapisWaits = lapis.on && found == null && running?.vulkan == true &&
                    running.state == LuwesStatus.State.RUNNING
                Poll(running?.describe(), found?.describe(), luwes.needsRestart(pkg) || lapisWaits)
            }
            status = polled.luwes
            lapisStatus = polled.lapis
            restart = polled.restart
            delay(1000)
        }
    }
    fun commit(next: LuwesSettings) {
        settings = next
        luwes.set(pkg, next)
    }
    fun commitLapis(next: LapisSettings) {
        lapis = next
        luwes.setLapis(pkg, next)
    }

    Section("Luwes")
    Block(padding = 10.dp) {
        val selected = if (settings.on) LuwesSettings.effective(settings.multiplier, hz) else LuwesSettings.OFF
        SettingRow(
            "Frame generation",
            tag = { if (restart) RestartTag() else LiveTag() },
            value = if (selected >= LuwesSettings.MIN) "${LuwesSettings.cadence(selected, hz)} fps" else null,
        )
        Segmented(
            listOf("Off" to LuwesSettings.OFF) + LuwesSettings.multipliers(hz).map { "$it×" to it },
            selected = selected,
            onSelect = { commit(settings.copy(multiplier = it)) },
        )
        if (settings.on) {
            SettingRow("Mode")
            Segmented(
                listOf("Extrapolate" to Mode.EXTRAPOLATE, "Interpolate" to Mode.INTERPOLATE),
                selected = settings.mode,
                onSelect = { commit(settings.copy(mode = it)) },
            )
            SwitchRow("HUD protection", settings.hudProtection, { commit(settings.copy(hudProtection = it)) })
            if (luwes.afmeAvailable) {
                SettingRow("Motion engine", tag = { LiveTag() })
                Segmented(
                    listOf("Qualcomm" to Engine.QUALCOMM, "Luwes" to Engine.LUWES),
                    selected = settings.engine,
                    onSelect = { commit(settings.copy(engine = it)) },
                )
            }
            SwitchRow(
                "Lapis",
                lapis.on,
                { commitLapis(luwes.lapis(pkg).copy(mode = if (it) LapisSettings.Mode.AUTO else LapisSettings.Mode.OFF)) },
            )
            if (lapis.on) lapisStatus?.let { Text(it, style = label(11.sp), color = cs.onSurfaceVariant) }
        }
        status?.let { Text(it, style = label(11.sp), color = cs.onSurfaceVariant) }
        if (restart) SettingRow("Applies after restart") {
            Text(
                "Restart game",
                Modifier.clip(RoundedCornerShape(12.dp))
                    .clickable { luwes.restart(pkg); restart = false }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = label(12.sp, 600), color = cs.primary,
            )
        }
    }
}

private data class Poll(val luwes: String? = null, val lapis: String? = null, val restart: Boolean = false)
