/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import org.yasvara.pentas.R
import org.yasvara.pentas.data.model.AppRef
import org.yasvara.pentas.overlay.LocalOverlay
import org.yasvara.pentas.overlay.Page
import org.yasvara.pentas.overlay.dock.openInWindow
import org.yasvara.pentas.ui.kit.Glyph
import org.yasvara.pentas.ui.kit.RoundButton
import org.yasvara.pentas.ui.kit.floatingReorderItem
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.moved
import org.yasvara.pentas.ui.kit.num
import org.yasvara.pentas.ui.kit.rememberLinearReorderState
import kotlin.math.abs

/** mm:ss, or h:mm:ss past an hour. */
internal fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

/** The 52 dp rail beside the panel: session time, ping, record, window, app shortcuts. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Rail(close: () -> Unit) {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(1000) } }
    val recording by o.state.recording
    val since by o.state.recordingSinceMs
    val paused by o.state.recordingPaused
    val recorded by o.state.recordedMs
    Column(
        Modifier.width(52.dp).fillMaxHeight().clip(RoundedCornerShape(26.dp))
            .background(cs.surface.copy(alpha = 0.96f)).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(clock(now - o.session.startElapsedMs), style = num(12.sp, 560), color = cs.onSurface)
        Ping()
        Box(Modifier.width(22.dp).height(1.dp).background(cs.outlineVariant))
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(
                when { paused -> cs.tertiaryContainer; recording -> cs.errorContainer; else -> cs.surfaceContainerHigh },
            ).combinedClickable(onLongClick = { o.state.open(Page.RECORD) }, onClick = { o.record.tap() }),
            contentAlignment = Alignment.Center,
        ) {
            when {
                paused -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Glyph(R.drawable.sym_play_arrow, cs.onTertiaryContainer, 14.dp, "Resume")
                    Text(clock(recorded).takeLast(5), style = num(9.sp, 600), color = cs.onTertiaryContainer)
                }
                recording && since > 0 -> Text(clock(now - since).takeLast(5), style = num(9.5.sp, 600), color = cs.onErrorContainer)
                else -> Box(Modifier.size(12.dp).clip(CircleShape).background(cs.error))
            }
        }
        if (recording && since > 0) RoundButton(R.drawable.sym_stop, "Stop recording", { o.record.stop() }, size = 40.dp)
        RoundButton(R.drawable.sym_picture_in_picture, "Open an app in a window", {
            o.state.appsPin.value = false; o.state.open(Page.APPS)
        }, size = 40.dp)
        ShortcutColumn(Modifier.weight(1f), close)
        RoundButton(R.drawable.sym_add, "Add shortcut", {
            o.state.appsPin.value = true; o.state.open(Page.APPS)
        }, size = 40.dp, active = false)
    }
}

@Composable
private fun Ping() {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val net by o.network.snapshot.collectAsState()
    val ms = net.rttMs
    val bars = when { ms <= 0 -> 0; ms < 60 -> 3; ms < 120 -> 2; else -> 1 }
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable { o.state.open(Page.PING) }.padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            listOf(4, 7, 11).forEachIndexed { i, h ->
                Box(
                    Modifier.size(4.dp, h.dp).clip(RoundedCornerShape(1.5.dp))
                        .background(if (i < bars) (if (bars == 1) cs.error else cs.primary) else cs.outlineVariant)
                )
            }
        }
        Text(if (ms > 0) "$ms ms" else "—", style = label(10.sp), color = cs.onSurfaceVariant)
    }
}

@Composable
private fun ShortcutColumn(modifier: Modifier, close: () -> Unit) {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val mode = o.session.mode
    var apps by remember { mutableStateOf(emptyList<AppRef>()) }
    var rev by remember { mutableLongStateOf(0) }
    LaunchedEffect(rev, mode, o.pkg) { apps = o.shortcuts.load(mode, o.pkg, SHORTCUTS) }

    val reorder = rememberLinearReorderState(horizontal = false, gap = 6.dp)
    val ids = remember(apps) { apps.map { it.packageName } }
    val scroll = rememberScrollState()
    val unpinThresholdPx = with(LocalDensity.current) { UNPIN_DRAG_DP.toPx() }

    Column(
        modifier.verticalScroll(scroll, enabled = !reorder.isDragging),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        apps.forEach { app ->
            key(app.packageName) {
                val bmp = remember(app.packageName) { app.icon?.toBitmap(96, 96)?.asImageBitmap() }
                val floating = reorder.draggingId == app.packageName && reorder.lifted
                val pullingOut = floating && abs(reorder.dragX) >= unpinThresholdPx
                Box(
                    Modifier
                        .size(40.dp)
                        .floatingReorderItem(
                            state = reorder,
                            id = app.packageName,
                            ids = ids,
                            shape = CircleShape,
                            onSwap = { from, to -> apps = apps.moved(from, to) },
                            onCommit = { o.shortcuts.setOrder(mode, apps.map { it.packageName }) },
                            crossAxisThreshold = UNPIN_DRAG_DP,
                            onCrossAxisDrop = {
                                o.shortcuts.unpin(mode, app.packageName)
                                rev++
                            },
                            floatScale = 1.14f,
                            floatElevation = 12.dp,
                        )
                        .clip(CircleShape)
                        .then(
                            when {
                                pullingOut -> Modifier.border(2.dp, cs.error, CircleShape)
                                floating -> Modifier.border(2.dp, cs.primary, CircleShape)
                                else -> Modifier
                            }
                        )
                        .clickable { openInWindow(app.packageName); close() },
                    contentAlignment = Alignment.Center,
                ) {
                    bmp?.let { Image(it, app.label, Modifier.size(40.dp)) }
                    if (pullingOut) {
                        Box(
                            Modifier.size(40.dp).background(cs.error.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) { Glyph(R.drawable.sym_close, cs.onError, 18.dp, "Unpin") }
                    }
                }
            }
        }
    }
}

private const val SHORTCUTS = 20
private val UNPIN_DRAG_DP = 38.dp
