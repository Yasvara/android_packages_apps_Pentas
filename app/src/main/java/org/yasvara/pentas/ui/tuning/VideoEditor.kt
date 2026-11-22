/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.ui.tuning

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yasvara.pentas.ui.kit.Block
import org.yasvara.pentas.ui.kit.Segmented
import org.yasvara.pentas.ui.kit.SettingRow
import org.yasvara.pentas.ui.kit.Slider
import org.yasvara.pentas.ui.kit.SwitchRow
import org.yasvara.pentas.ui.kit.label

/** Generated frames and upscaling for a video app. Live: the decoder follows within a frame. */
@Composable
fun VideoEditor(pkg: String) {
    val tuning = rememberTuning()
    val path = tuning.videoPath()
    val afmeAvailable = remember { tuning.luwes().afmeAvailable }
    var settings by remember(pkg) { mutableStateOf(path.settings(pkg)) }
    val rates = remember { path.rates().filter { it >= 60 } }
    fun commit(next: org.yasvara.pentas.video.VideoSettings) {
        settings = next
        path.set(pkg, next)
    }
    Block(padding = 10.dp) {
        SettingRow("Motion", tag = { LiveTag() })
        Segmented(
            listOf("Off" to 0) + rates.map { "$it" to it },
            selected = settings.motionHz, onSelect = { commit(settings.copy(motionHz = it)) },
        )
        if (settings.motionHz != 0 && afmeAvailable) {
            SettingRow("Engine") {}
            Segmented(
                listOf("Luwes" to false, "AFME 2×" to true),
                selected = settings.afme, onSelect = { commit(settings.copy(afme = it)) },
            )
        }
        if (settings.motionHz != 0 && !settings.afme) {
            SettingRow("Smoothness", value = "%d%%".format((settings.smoothness * 100f).toInt()))
            Slider(
                settings.smoothness, { settings = settings.copy(smoothness = it) },
                range = 0f..1f, onCommit = { commit(settings.copy(smoothness = it)) },
            )
            Text(
                "Less keeps some of the film's cadence.", style = label(11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Block(padding = 10.dp) {
        SwitchRow("Upscale to the screen", settings.upscale, { commit(settings.copy(upscale = it)) }, tag = { LiveTag() })
    }
}
