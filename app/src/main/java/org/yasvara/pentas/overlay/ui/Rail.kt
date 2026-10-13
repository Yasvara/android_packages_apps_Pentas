/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import org.yasvara.pentas.R
import org.yasvara.pentas.data.model.AppRef
import org.yasvara.pentas.overlay.LocalOverlay
import org.yasvara.pentas.overlay.Page
import org.yasvara.pentas.overlay.dock.openInWindow
import org.yasvara.pentas.ui.kit.RoundButton
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num

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
            Modifier.size(40.dp).clip(CircleShape).background(if (recording) cs.errorContainer else cs.surfaceContainerHigh)
                .combinedClickable(onLongClick = { o.state.open(Page.RECORD) }, onClick = { o.record.toggle() }),
            contentAlignment = Alignment.Center,
        ) {
            if (recording && since > 0) Text(clock(now - since).takeLast(5), style = num(9.5.sp, 600), color = cs.onErrorContainer)
            else Box(Modifier.size(12.dp).clip(CircleShape).background(cs.error))
        }
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
    val ms = o.network.latencyMs.value
    val bars = when { ms <= 0 -> 0; ms < 60 -> 3; ms < 120 -> 2; else -> 1 }
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable { o.state.open(Page.PING) }.padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            listOf(4, 7, 11).forEachIndexed { i, h ->
                Box(Modifier.size(4.dp, h.dp).clip(RoundedCornerShape(1.5.dp))
                    .background(if (i < bars) (if (bars == 1) cs.error else cs.primary) else cs.outlineVariant))
            }
        }
        Text(if (ms > 0) "$ms ms" else "—", style = label(10.sp), color = cs.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutColumn(modifier: Modifier, close: () -> Unit) {
    val o = LocalOverlay.current
    val mode = o.session.mode
    var apps by remember { mutableStateOf(emptyList<AppRef>()) }
    var rev by remember { mutableLongStateOf(0) }
    LaunchedEffect(rev) { apps = o.shortcuts.load(mode, o.pkg, SHORTCUTS) }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        apps.forEach { app ->
            val bmp = remember(app.packageName) { app.icon?.toBitmap(96, 96)?.asImageBitmap() }
            Box(
                Modifier.size(40.dp).clip(CircleShape).combinedClickable(
                    onLongClick = { o.shortcuts.unpin(mode, app.packageName); rev++ },
                    onClick = { openInWindow(app.packageName); close() },
                ),
            ) { bmp?.let { Image(it, app.label, Modifier.size(40.dp)) } }
        }
    }
}

private const val SHORTCUTS = 20
