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
import org.yasvara.pentas.ui.kit.SwitchRow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yasvara.pentas.framefx.FramefxBroker
import org.yasvara.pentas.texture.TextureSettings
import org.yasvara.pentas.ui.kit.Block
import org.yasvara.pentas.ui.kit.Segmented
import org.yasvara.pentas.ui.kit.SettingRow
import org.yasvara.pentas.ui.kit.label

/** Anisotropic filtering and texture detail, from the game's next start. */
@Composable
fun TextureEditor(pkg: String) {
    var settings by remember(pkg) {
        mutableStateOf(TextureSettings.decode(FramefxBroker.getSection(pkg, TextureSettings.SECTION_ID)))
    }
    fun set(next: TextureSettings) {
        if (FramefxBroker.setSection(pkg, TextureSettings.SECTION_ID, next.encode())) settings = next
    }
    Block(padding = 10.dp) {
        SettingRow("Anisotropic filtering", tag = { RestartTag() })
        Segmented(
            TextureSettings.ANISOTROPY.map { (if (it == 0) "Game" else "${it}×") to it },
            selected = settings.anisotropy,
            onSelect = { set(settings.copy(anisotropy = it)) },
        )
        SettingRow("Texture detail", tag = { RestartTag() })
        Segmented(
            listOf("Game" to 0, "Sharper" to -4, "Sharpest" to -8),
            selected = settings.lodBiasEighths,
            onSelect = { set(settings.copy(lodBiasEighths = it)) },
        )
        Text(
            "Ground and walls stay sharp at a slant. Sharper detail suits games with their own " +
                "anti-aliasing; without it, fine textures can shimmer.",
            style = label(11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Variable rate shading of the game's 3D work: the HUD, text and post-processing keep full rate.
 * Turning it on needs a restart; the rate then follows live.
 */
@Composable
fun ShadingEditor(pkg: String) {
    var settings by remember(pkg) {
        mutableStateOf(TextureSettings.decode(FramefxBroker.getSection(pkg, TextureSettings.SECTION_ID)))
    }
    fun set(next: TextureSettings) {
        if (FramefxBroker.setSection(pkg, TextureSettings.SECTION_ID, next.encode())) settings = next
    }
    Block(padding = 10.dp) {
        SettingRow("Shading rate", tag = { if (settings.shadingRate == 0) RestartTag() else LiveTag() })
        Segmented(
            listOf("Full" to 0, "2×1" to 1, "2×2" to 2, "4×4" to 3),
            selected = settings.shadingRate,
            onSelect = { set(settings.copy(shadingRate = it, shadingAdaptive = it != 0 && settings.shadingAdaptive)) },
        )
        if (settings.shadingRate != 0) {
            SwitchRow(
                "Only when the game falls behind",
                settings.shadingAdaptive,
                { set(settings.copy(shadingAdaptive = it)) },
            )
        }
        Text(
            "Shades the 3D world in blocks of pixels to free the GPU; the HUD and text stay sharp. " +
                "In Genshin 2×2 cut GPU load from 70% to 55% at the same clock.",
            style = label(11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
