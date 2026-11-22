/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.yasvara.pentas.R
import org.yasvara.pentas.kelir.KelirCodec
import org.yasvara.pentas.kelir.KelirSlots
import org.yasvara.pentas.kelir.VideoStatus
import org.yasvara.pentas.overlay.LocalOverlay
import org.yasvara.pentas.overlay.TunerTab
import org.yasvara.pentas.ui.kit.Glyph
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num
import org.yasvara.pentas.ui.tuning.KindGlyph
import org.yasvara.pentas.ui.tuning.rememberTuning
import org.yasvara.pentas.video.VideoSettings

private const val STATUS_POLL_MS = 500L
private const val FULL_RATE = 120

/**
 * What the app's video goes through right now. The line above the tiles is read back from the
 * decoder, so it stays away while nothing plays through the path. Tap a tile to turn it on or off,
 * hold it for the Video tab; hold the button below to see the video as decoded.
 */
@Composable
internal fun VideoCard() {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val pkg = o.pkg ?: return
    val tuning = rememberTuning()
    val kelir = tuning.kelir()
    val path = tuning.videoPath()
    val stored by kelir.stored.collectAsState()
    var slots by remember(pkg) { mutableStateOf(kelir.slots(pkg)) }
    var settings by remember(pkg) { mutableStateOf(path.settings(pkg)) }
    var video by remember(pkg) { mutableStateOf<VideoStatus?>(null) }
    LaunchedEffect(pkg, stored) { slots = kelir.slots(pkg) }
    LaunchedEffect(pkg) {
        while (true) {
            video = withContext(Dispatchers.IO) { kelir.videoStatus(pkg) }
            settings = withContext(Dispatchers.IO) { path.settings(pkg) }
            delay(STATUS_POLL_MS)
        }
    }
    val rate = remember { path.rates().lastOrNull() ?: FULL_RATE }
    val running = slots.running
    val coloured = running != null && running.nodes.isNotEmpty()
    fun apply(next: VideoSettings) {
        settings = next
        path.set(pkg, next)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        video?.let { v ->
            val text = when (v.state) {
                VideoStatus.State.RUNNING -> statusLine(v)
                VideoStatus.State.DECLINED -> "Not applied: ${v.reason.text}"
                else -> null
            }
            if (text != null) {
                Text(
                    text, Modifier.padding(horizontal = 4.dp), style = num(11.sp),
                    color = if (v.state == VideoStatus.State.DECLINED) cs.error else cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val fg = { on: Boolean -> if (on) cs.onSecondaryContainer else cs.onSurface }
            VideoTile(
                "Luwes", if (settings.motionHz != 0) "${settings.motionHz} fps" else "Off", settings.motionHz != 0,
                onClick = { apply(settings.copy(motionHz = if (settings.motionHz != 0) 0 else rate)) },
                onHold = { o.state.tuner(TunerTab.KELIR) },
            ) { on -> Cadence(if (on) framesPerReal(video) else 1, fg(on)) }
            VideoTile(
                "Jernih", if (settings.upscale) video?.takeIf { it.upscaled }?.let { "${minOf(it.width, it.height)}p" } ?: "Auto" else "Off",
                settings.upscale,
                onClick = { apply(settings.copy(upscale = !settings.upscale)) },
                onHold = { o.state.tuner(TunerTab.KELIR) },
            ) { on -> Sizes(on && video?.upscaled == true, fg(on)) }
            VideoTile(
                "Kelir", if (slots.active == KelirSlots.OFF) "Off" else "Slot ${slots.active + 1}", coloured,
                onClick = {
                    if (slots.slots.all { it.nodes.isEmpty() }) o.state.tuner(TunerTab.KELIR)
                    else slots = kelir.cycle(pkg)
                },
                onHold = { o.state.tuner(TunerTab.KELIR) },
            ) { on ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (on) running?.nodes?.take(3)?.forEach { KindGlyph(it.kind, size = 14.dp) }
                    else Glyph(R.drawable.sym_palette, fg(false), 16.dp)
                }
            }
        }
        if (coloured || settings.on) {
            val currentSlots by rememberUpdatedState(slots)
            val currentSettings by rememberUpdatedState(settings)
            var held by remember { mutableStateOf(false) }
            val hold by animateColorAsState(if (held) cs.primary else cs.surfaceContainer, label = "holdbg")
            Box(
                Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(12.dp)).background(hold)
                    .pointerInput(pkg) {
                        detectTapGestures(onPress = {
                            held = true
                            val kept = currentSettings
                            path.set(pkg, VideoSettings(smoothness = kept.smoothness))
                            kelir.preview(pkg, currentSlots, KelirCodec.View(peek = true))
                            tryAwaitRelease()
                            held = false
                            path.set(pkg, kept)
                            kelir.preview(pkg, currentSlots, KelirCodec.View())
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Hold to compare with the original", style = label(11.sp, 550),
                    color = if (held) cs.onPrimary else cs.onSurfaceVariant,
                )
            }
        }
    }
}

private fun statusLine(v: VideoStatus): String {
    val size = if (v.upscaled) "${v.sourceWidth}×${v.sourceHeight} → ${v.width}×${v.height}" else "${v.width}×${v.height}"
    val shown = v.presentedFps.roundToInt()
    val real = v.fps.roundToInt()
    val rate = if (shown > real + 1) "$real → $shown fps" else "$real fps"
    val neural = if (v.superres) " · SR" else ""
    return "$size$neural · $rate · %.1f ms".format(v.gpuMicros / 1000f)
}

/** Presents per real frame, from what the decoder reports. */
private fun framesPerReal(v: VideoStatus?): Int =
    if (v == null || v.fps <= 1f) 1 else (v.presentedFps / v.fps).roundToInt().coerceIn(1, 6)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.VideoTile(
    name: String,
    value: String,
    on: Boolean,
    onClick: () -> Unit,
    onHold: () -> Unit,
    art: @Composable (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (on) cs.secondaryContainer else cs.surfaceContainer, label = "tilebg")
    Column(
        Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(14.dp)).background(bg)
            .combinedClickable(onLongClick = onHold, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.height(16.dp), contentAlignment = Alignment.CenterStart) { art(on) }
        Column {
            Text(name, style = label(12.sp, 600), color = if (on) cs.onSecondaryContainer else cs.onSurface, maxLines = 1)
            Text(
                value, style = label(10.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (on) cs.onSecondaryContainer else cs.onSurfaceVariant,
            )
        }
    }
}

/** A real frame, filled, and the frames generated after it, hollow: as many as there are. */
@Composable
private fun Cadence(perReal: Int, color: Color) {
    Canvas(Modifier.size(56.dp, 10.dp)) {
        val side = size.height
        val gap = side * 0.5f
        val shown = (size.width / (side + gap)).toInt()
        for (i in 0 until shown) {
            val x = i * (side + gap)
            if (i % perReal == 0) {
                drawRect(color, Offset(x, 0f), Size(side, side))
            } else {
                drawRect(color.copy(alpha = 0.6f), Offset(x, 0f), Size(side, side), style = Stroke(1.2.dp.toPx()))
            }
        }
    }
}

/** The frame's size against the size it is drawn at. */
@Composable
private fun Sizes(up: Boolean, color: Color) {
    Canvas(Modifier.size(56.dp, 14.dp)) {
        val small = size.height * 0.55f
        drawRect(color.copy(alpha = 0.6f), Offset(0f, size.height - small), Size(small * 1.6f, small), style = Stroke(1.2.dp.toPx()))
        if (up) drawRect(color, Offset(small * 2.2f, 0f), Size(size.height * 1.6f, size.height))
    }
}
