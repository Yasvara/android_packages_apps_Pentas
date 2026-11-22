/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import org.yasvara.pentas.R
import org.yasvara.pentas.data.model.AppRef
import org.yasvara.pentas.overlay.LocalOverlay
import org.yasvara.pentas.overlay.dock.openInWindow
import org.yasvara.pentas.overlay.tiles.TileAction
import org.yasvara.pentas.overlay.tiles.TileRegistry
import org.yasvara.pentas.overlay.tiles.tilePainter
import org.yasvara.pentas.ui.kit.Block
import org.yasvara.pentas.ui.kit.Glyph
import org.yasvara.pentas.ui.kit.RoundButton
import org.yasvara.pentas.ui.kit.Section
import org.yasvara.pentas.ui.kit.label

/** Pick toggles (top row, five slots) and tools (pages of four) from the pool. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditPage() {
    val o = LocalOverlay.current
    val reg = o.tiles
    var toggles by remember { mutableStateOf(reg.toggles.map { it.id }) }
    var tools by remember { mutableStateOf(reg.tools.map { it.id }) }
    val pool = reg.pool
    fun commit() { reg.setToggles(toggles); reg.setTools(tools) }
    SubPage("Edit", trailing = {
        RoundButton(R.drawable.sym_restart_alt, "Reset", {
            reg.reset(); toggles = reg.toggles.map { it.id }; tools = reg.tools.map { it.id }
        })
    }) {
        Section("Toggles · ${toggles.size}/${TileRegistry.TOGGLE_SLOTS}")
        Chips(toggles.mapNotNull { id -> pool.firstOrNull { it.id == id } }, selected = true) { t ->
            toggles = toggles - t.id; commit()
        }
        Section("Tools")
        Chips(tools.mapNotNull { id -> pool.firstOrNull { it.id == id } }, selected = true) { t ->
            tools = tools - t.id; commit()
        }
        val free = pool.filter { it.id !in toggles && it.id !in tools }
        if (free.isNotEmpty()) {
            Section("Available")
            free.groupBy { it.group }.forEach { (_, group) ->
                Chips(group, selected = false) { t ->
                    if (toggles.size < TileRegistry.TOGGLE_SLOTS && t.id != "widgets") toggles = toggles + t.id else tools = tools + t.id
                    commit()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(tiles: List<TileAction>, selected: Boolean, onTap: (TileAction) -> Unit) {
    val cs = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tiles.forEach { t ->
            Row(
                Modifier.height(32.dp).clip(RoundedCornerShape(if (selected) 16.dp else 8.dp))
                    .background(if (selected) cs.secondaryContainer else cs.surfaceContainerHigh)
                    .clickable { onTap(t) }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(tilePainter(t), null, Modifier.size(16.dp), tint = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant)
                Text(t.label, style = label(11.5.sp, 550), maxLines = 1,
                    color = if (selected) cs.onSecondaryContainer else cs.onSurface)
            }
        }
    }
}

/** Launchable apps with search: open one in a window, or pin it as a rail shortcut. */
@Composable
fun AppsPage() {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val pin by o.state.appsPin
    var all by remember { mutableStateOf(emptyList<AppRef>()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { all = o.shortcuts.launchable() }
    DisposableEffect(Unit) { onDispose { o.state.keyboard.value = false } }
    val shown = remember(all, query) {
        if (query.isBlank()) all else all.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
    }
    SubPage(if (pin) "Add shortcut" else "Open in a window") {
        Row(
            Modifier.fillMaxWidth().height(36.dp).clip(CircleShape).background(cs.surfaceContainerHigh).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Glyph(R.drawable.sym_search, cs.onSurfaceVariant, 16.dp)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search", style = label(12.5.sp), color = cs.onSurfaceVariant)
                BasicTextField(
                    query, { query = it }, Modifier.fillMaxWidth().onFocusChanged { o.state.keyboard.value = it.isFocused },
                    singleLine = true, textStyle = label(12.5.sp).copy(color = cs.onSurface), cursorBrush = SolidColor(cs.primary),
                )
            }
        }
        Block(padding = 4.dp, color = cs.surfaceContainerLow) {
            shown.forEach { app ->
                val bmp = remember(app.packageName) { app.icon?.toBitmap(72, 72)?.asImageBitmap() }
                Row(
                    Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).clickable {
                        if (pin) o.shortcuts.pin(o.session.mode, app.packageName) else openInWindow(app.packageName)
                        o.state.home()
                    }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    bmp?.let { Image(it, null, Modifier.size(28.dp)) } ?: Box(Modifier.width(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = label(12.5.sp, 550), color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
