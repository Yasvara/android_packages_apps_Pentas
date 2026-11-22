/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.ui.tuning

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.yasvara.pentas.ui.kit.Block
import org.yasvara.pentas.ui.kit.Segmented
import org.yasvara.pentas.ui.kit.SettingRow
import org.yasvara.pentas.ui.kit.Slider
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num
import org.yasvara.pentas.upscale.UpscaleSettings
import org.yasvara.pentas.upscale.UpscaleStatus
import java.util.Locale
import kotlin.math.roundToInt

/**
 * SGSR in place of the game's own stretch of its 3D under its HUD: the switch, the filter and its
 * sharpness, the sizes it found and what it costs, while the game runs.
 */
@Composable
fun UpscaleEditor(pkg: String) {
    val upscaler = rememberTuning().upscaler()
    val cs = MaterialTheme.colorScheme
    var settings by remember(pkg) {
        mutableStateOf(
            upscaler.settings(pkg).let {
                it.copy(mode = if (upscaler.on(pkg)) UpscaleSettings.Mode.AUTO else UpscaleSettings.Mode.OFF)
            }
        )
    }
    val on = settings.on
    var sharpness by remember(pkg) { mutableFloatStateOf(settings.sharpnessShare) }
    fun commit(next: UpscaleSettings) { settings = next; upscaler.set(pkg, next) }
    var status by remember(pkg) { mutableStateOf<UpscaleStatus?>(null) }
    var restart by remember(pkg) { mutableStateOf(false) }
    LaunchedEffect(pkg, on) {
        while (true) {
            val (found, waits) = withContext(Dispatchers.IO) {
                upscaler.status(pkg).filterNotNull().maxByOrNull { it.state.ordinal } to
                    (on && upscaler.needsRestart(pkg))
            }
            status = if (on) found else null
            restart = waits
            delay(1000)
        }
    }
    val running = status?.state == UpscaleStatus.State.RUNNING
    Block(padding = 10.dp) {
        SettingRow(
            "Upscaler",
            tag = { if (restart) RestartTag() else LiveTag() },
            value = status?.takeIf { running }?.let { String.format(Locale.ROOT, "%.2f×", it.factor) },
        )
        status?.let { s ->
            Text(s.describe(), style = label(11.sp), color = cs.onSurfaceVariant)
            if ((s.sceneWidth > 0 || s.resized) && s.width > 0) SizeFigure(s)
        }
        if (status == null) {
            Text(
                "Fills what the game draws up to the screen. Render resolution sets how much it draws.",
                style = label(11.sp), color = cs.onSurfaceVariant,
            )
        }
        Segmented(
            listOf("Off" to Choice.OFF, "Auto" to Choice.AUTO, "SGSR 1" to Choice.SPATIAL),
            selected = when {
                !on -> Choice.OFF
                settings.filter == UpscaleSettings.Filter.SPATIAL -> Choice.SPATIAL
                else -> Choice.AUTO
            },
            onSelect = {
                commit(
                    settings.copy(
                        mode = if (it == Choice.OFF) UpscaleSettings.Mode.OFF else UpscaleSettings.Mode.AUTO,
                        filter = if (it == Choice.SPATIAL) UpscaleSettings.Filter.SPATIAL else UpscaleSettings.Filter.BEST,
                    )
                )
            },
        )
        if (on) {
            SettingRow(
                "Sharpness",
                value = if (settings.sharpness == 0) "Default" else "%d%%".format(Locale.ROOT, (sharpness * 100).roundToInt()),
            )
            Slider(
                sharpness, { sharpness = it }, steps = 19, marker = 0.5f,
                onCommit = { commit(settings.withSharpnessShare(it)) },
            )
        }
        status?.takeIf { running && it.gpuMs > 0f && it.realFps > 0f }?.let { CostBar(it) }
    }
}

private enum class Choice { OFF, AUTO, SPATIAL }

/** The game's 3D inside its HUD frame, to scale. */
@Composable
private fun SizeFigure(s: UpscaleStatus) {
    val cs = MaterialTheme.colorScheme
    val inner = if (s.sceneWidth > 0) s.sceneWidth else s.drawnWidth
    val share = (inner.toFloat() / s.width).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.height(54.dp).fillMaxWidth(0.42f).clip(RoundedCornerShape(6.dp))
                .border(1.5.dp, cs.tertiary, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.TopStart,
        ) {
            Box(Modifier.fillMaxWidth(share).fillMaxHeight(share).clip(RoundedCornerShape(4.dp)).background(cs.primary.copy(alpha = 0.55f)))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (s.sceneWidth > 0) Text("3D  ${s.sceneWidth}×${s.sceneHeight}", style = num(11.sp, 600), color = cs.primary)
            if (s.resized) Text("Drawn  ${s.drawnWidth}×${s.drawnHeight}", style = num(11.sp, 500), color = cs.onSurfaceVariant)
            Text("Shown  ${s.width}×${s.height}", style = num(11.sp, 500), color = cs.onSurfaceVariant)
        }
    }
}

/** Measured GPU time per real frame, against that frame's time. */
@Composable
private fun CostBar(s: UpscaleStatus) {
    val cs = MaterialTheme.colorScheme
    val frameMs = 1000f / s.realFps
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            val what = if (s.method == UpscaleStatus.Method.RESAMPLE) "Whole frame" else "${s.method.label} · HUD kept"
            Text("$what · per real frame", Modifier.weight(1f), style = num(10.5.sp, 500), color = cs.onSurfaceVariant)
            Text(String.format(Locale.ROOT, "%.2f ms", s.gpuMs), style = num(13.sp, 600), color = cs.onSurface)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(cs.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth((s.gpuMs / frameMs).coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(cs.primary))
        }
        Row {
            Text("0", Modifier.weight(1f), style = label(9.5.sp), color = cs.outline)
            Text(String.format(Locale.ROOT, "real frame at %.0f fps · %.1f ms", s.realFps, frameMs), style = label(9.5.sp), color = cs.outline)
        }
    }
}
