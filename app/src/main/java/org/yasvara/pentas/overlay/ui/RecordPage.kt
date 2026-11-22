/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.ui

import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalContext
import org.yasvara.pentas.overlay.LocalOverlay
import org.yasvara.rekam.controls.RekamControls

/** Rekam's own controller in the sidebar; only the choice of what to record is Pentas's. */
@Composable
fun RecordPage() {
    val o = LocalOverlay.current
    val ctx = LocalContext.current
    SubPage("Record") {
        RekamControls(
            client = o.record.client,
            target = { select ->
                var game by remember { mutableStateOf(o.record.gameMode) }
                LaunchedEffect(game) { select(o.record.target(game)) }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(true to "This game", false to "Full screen").forEachIndexed { i, (g, name) ->
                        SegmentedButton(game == g, { game = g }, SegmentedButtonDefaults.itemShape(i, 2)) {
                            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
            },
            onDone = { o.state.home() },
            onEdit = {
                ctx.startService(Intent().setClassName("org.yasvara.rekam", "org.yasvara.rekam.ui.StudioService"))
            },
        )
    }
}
