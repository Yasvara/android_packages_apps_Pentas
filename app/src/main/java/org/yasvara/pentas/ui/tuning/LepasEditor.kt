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
import org.yasvara.pentas.framefx.FramefxBroker
import org.yasvara.pentas.lepas.LepasSettings
import org.yasvara.pentas.ui.kit.Block
import org.yasvara.pentas.ui.kit.Segmented
import org.yasvara.pentas.ui.kit.SettingRow
import org.yasvara.pentas.ui.kit.label

/** Panel rate: the game's own fps cap lifted to 90 or 120, from the game's next start. */
@Composable
fun LepasEditor(pkg: String) {
    var settings by remember(pkg) {
        mutableStateOf(LepasSettings.decode(FramefxBroker.getSection(pkg, LepasSettings.SECTION_ID)))
    }
    fun set(next: LepasSettings) {
        if (FramefxBroker.setSection(pkg, LepasSettings.SECTION_ID, next.encode())) settings = next
    }
    Block(padding = 10.dp) {
        SettingRow("Panel rate", tag = { RestartTag() })
        Segmented(
            LepasSettings.TARGETS.map { (if (it == 0) "Game" else "$it") to it },
            selected = settings.targetFps,
            onSelect = { set(LepasSettings(it)) },
        )
        Text(
            "Lifts the game's own frame cap. The first run of each game version learns where the " +
                "cap is: expect a few brief slowdowns for up to three minutes. Games with " +
                "anti-cheat may notice the change.",
            style = label(11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
