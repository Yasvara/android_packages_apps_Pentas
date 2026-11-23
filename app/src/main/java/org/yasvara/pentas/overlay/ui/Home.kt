/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.ui

import android.content.Intent
import android.os.UserHandle
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yasvara.pentas.R
import org.yasvara.pentas.app.GameActivity
import org.yasvara.pentas.overlay.LocalOverlay
import org.yasvara.pentas.overlay.Page
import org.yasvara.pentas.overlay.tiles.TileAction
import org.yasvara.pentas.overlay.tiles.tilePainter
import org.yasvara.pentas.session.SidebarMode
import org.yasvara.pentas.thermal.KernelNodeIO
import org.yasvara.pentas.thermal.ThermalNodes
import org.yasvara.pentas.thermal.ThermalProfiles
import org.yasvara.pentas.ui.kit.Glyph
import org.yasvara.pentas.ui.kit.Kit
import org.yasvara.pentas.ui.kit.RoundButton
import org.yasvara.pentas.ui.kit.Slider
import org.yasvara.pentas.ui.kit.floatingReorderItem
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num
import org.yasvara.pentas.ui.kit.rememberLinearReorderState
import org.yasvara.pentas.ui.kit.segmentShape
import kotlin.math.abs

@Composable
fun Home(mode: Int) {
    val o = LocalOverlay.current
    val listOpen by o.state.profileList
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(Kit.Gap)) {
            Header()
            if (mode == SidebarMode.MODE_GAME) Monitor(Modifier.weight(1f)) { Controls() } else VideoControls()
        }
        if (listOpen) ProfileList()
    }
}

/** Everything under the live tiles. */
@Composable
private fun ColumnScope.Controls() {
    ThermalSwitcher()
    MediaCard()
    BrightnessRow()
    Toggles()
    Spacer(Modifier.weight(1f))
    Tools()
}

/** A video app has its own transport: what the panel adds is what its video goes through. */
@Composable
private fun ColumnScope.VideoControls() {
    ThermalSwitcher()
    BrightnessRow()
    Toggles()
    VideoCard()
    Spacer(Modifier.weight(1f))
    Tools { it.id != "kelir" && it.id != "hide_hud" }
}

@Composable
fun rememberClock(): String {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(60_000 - now % 60_000) }
    }
    return DateFormat.getTimeFormat(ctx).format(now)
}

private data class BypassLive(val state: Int = ThermalNodes.BYPASS_ST_IDLE, val drainMa: Int = 0)

/** Battery chip: tap toggles bypass charging where the kernel has it, long-press opens Battery. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Header() {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val s by o.bus.snapshot.collectAsState()
    val cr = ctx.contentResolver
    val canBypass = remember { KernelNodeIO.exists(ThermalNodes.BYPASS_CHARGE) }
    var bypassMode by remember {
        mutableIntStateOf(Settings.System.getIntForUser(cr, ThermalProfiles.KEY_BYPASS_CHARGING, 0, UserHandle.USER_CURRENT))
    }
    var bypassLive by remember { mutableStateOf(BypassLive()) }

    LaunchedEffect(canBypass) {
        if (!canBypass) return@LaunchedEffect
        while (true) {
            val (mode, live) = withContext(Dispatchers.IO) {
                val m = Settings.System.getIntForUser(cr, ThermalProfiles.KEY_BYPASS_CHARGING, 0, UserHandle.USER_CURRENT)
                val parts = KernelNodeIO.read(ThermalNodes.BYPASS_STATE)?.trim()?.split(' ')?.mapNotNull { it.toIntOrNull() }.orEmpty()
                m to BypassLive(parts.getOrElse(0) { ThermalNodes.BYPASS_ST_IDLE }, parts.getOrElse(1) { 0 })
            }
            bypassMode = mode
            bypassLive = live
            delay(1500)
        }
    }

    val bypassEnabled = bypassMode != ThermalNodes.BYPASS_MODE_OFF
    val bypassEngaged = bypassEnabled && when (bypassLive.state) {
        ThermalNodes.BYPASS_ST_ACTIVE,
        ThermalNodes.BYPASS_ST_CP_BYPASS,
        ThermalNodes.BYPASS_ST_FULL -> true
        else -> false
    }
    val bypassDraining = bypassEnabled && (
        bypassLive.state == ThermalNodes.BYPASS_ST_DEFICIT ||
            bypassLive.state == ThermalNodes.BYPASS_ST_ASSIST ||
            (!bypassEngaged && !s.batteryCharging && s.batteryPowerW > 0.2f) ||
            bypassLive.drainMa > 80
        )

    val chipBg by animateColorAsState(
        when {
            bypassDraining -> cs.tertiaryContainer
            bypassEngaged -> cs.primaryContainer
            bypassEnabled -> cs.secondaryContainer
            else -> cs.surfaceContainerHigh
        },
        label = "battBg",
    )
    val chipFg = when {
        bypassDraining -> cs.onTertiaryContainer
        bypassEngaged -> cs.onPrimaryContainer
        bypassEnabled -> cs.onSecondaryContainer
        else -> cs.onSurface
    }
    val subFg = when {
        s.batteryTempC >= 42f -> cs.error
        bypassDraining -> cs.onTertiaryContainer
        bypassEngaged -> cs.onPrimaryContainer
        bypassEnabled -> cs.onSecondaryContainer
        else -> cs.onSurfaceVariant
    }

    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(rememberClock(), style = num(13.sp, 560), color = cs.onSurface, modifier = Modifier.padding(start = 4.dp))
        Row(
            Modifier.height(24.dp).clip(CircleShape).background(chipBg)
                .then(if (bypassEnabled && !bypassEngaged) Modifier.border(1.dp, cs.primary.copy(alpha = 0.45f), CircleShape) else Modifier)
                .combinedClickable(
                    onLongClick = { o.state.open(Page.BATTERY) },
                    onClick = {
                        if (!canBypass) o.state.open(Page.BATTERY) else {
                            bypassMode = if (bypassEnabled) ThermalNodes.BYPASS_MODE_OFF else ThermalNodes.BYPASS_MODE_FORCE
                            Settings.System.putIntForUser(cr, ThermalProfiles.KEY_BYPASS_CHARGING, bypassMode, UserHandle.USER_CURRENT)
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                    },
                ).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BatteryGlyph(
                level = s.batteryCapacity,
                charging = s.batteryCharging,
                bypass = bypassEnabled,
                bypassEngaged = bypassEngaged,
                draining = bypassDraining,
            )
            Text(
                "${s.batteryCapacity}%",
                style = num(11.5.sp, 600),
                color = chipFg,
                maxLines = 1,
            )
            val flowText = when {
                bypassDraining -> "BP ↓%.1fW".format(s.batteryPowerW)
                bypassEngaged && s.batteryPowerW >= 0.3f -> "BP %s%.1fW".format(if (s.batteryCharging) "+" else "−", s.batteryPowerW)
                bypassEngaged -> if (bypassLive.state == ThermalNodes.BYPASS_ST_CP_BYPASS) "BP CP" else "BP 0W"
                bypassEnabled -> "BP · %s%.1fW".format(if (s.batteryCharging) "+" else "−", s.batteryPowerW)
                else -> (if (s.batteryCharging) "+" else "−") + "%.1f W".format(s.batteryPowerW)
            }
            Text(
                "$flowText · %.0f°".format(s.batteryTempC),
                style = label(10.5.sp, if (bypassEnabled) 600 else 500),
                color = subFg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        RoundButton(R.drawable.sym_tune, "Tuner", { o.state.tuner(o.state.tunerTab.value) })
        RoundButton(R.drawable.sym_edit, "Edit", { o.state.open(Page.EDIT) })
        RoundButton(R.drawable.sym_settings, "Settings", {
            ctx.startActivity(
                Intent(ctx, GameActivity::class.java)
                    .putExtra(GameActivity.EXTRA_PACKAGE, o.pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        })
    }
}

/** Battery outline whose fill reflects charge level, charging, and bypass state. */
@Composable
private fun BatteryGlyph(
    level: Int,
    charging: Boolean,
    bypass: Boolean = false,
    bypassEngaged: Boolean = false,
    draining: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val fill = when {
        draining -> cs.error
        bypassEngaged -> cs.primary
        bypass -> cs.secondary
        charging -> cs.primary
        level <= 15 -> cs.error
        else -> cs.onSurface
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (bypass) {
            Glyph(
                R.drawable.sym_bolt,
                tint = if (draining) cs.error else if (bypassEngaged) cs.primary else cs.onSecondaryContainer,
                size = 11.dp,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp, 10.dp).clip(RoundedCornerShape(3.dp)).background(cs.outline).padding(1.5.dp)) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(2.dp)).background(cs.surfaceContainerHigh)) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(level.coerceIn(0, 100) / 100f)
                            .background(fill)
                    )
                }
            }
            Box(Modifier.size(1.5.dp, 4.dp).background(cs.outline))
        }
    }
}

// ── media ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MediaCard() {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val np by o.media.now
    val cookie = MaterialShapes.Cookie9Sided.toShape()
    AnimatedVisibility(
        visible = np != null,
        enter = expandVertically(Kit.default()) + fadeIn(Kit.fast()),
        exit = shrinkVertically(Kit.default()) + fadeOut(Kit.fast()),
    ) {
        val n = np ?: return@AnimatedVisibility
        var t by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
        LaunchedEffect(n.playing) { while (n.playing) { t = android.os.SystemClock.elapsedRealtime(); delay(1000) } }
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainer)
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(40.dp).clip(cookie).background(cs.tertiaryContainer), contentAlignment = Alignment.Center) {
                n.art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: Glyph(R.drawable.sym_music_note, cs.onTertiaryContainer, 18.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(n.title, style = label(12.5.sp, 600), color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(n.artist, n.app).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = label(10.5.sp), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (n.durationMs > 0) {
                    val p = (n.position(t).toFloat() / n.durationMs).coerceIn(0f, 1f)
                    LinearWavyProgressIndicator(
                        progress = { p }, modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = cs.primary, trackColor = cs.outlineVariant,
                        amplitude = { if (n.playing) 1f else 0f },
                    )
                }
            }
            RoundButton(R.drawable.sym_skip_previous, "Previous", o.media::previous, size = 28.dp)
            MediaPlay(n.playing, o.media::playPause)
            RoundButton(R.drawable.sym_skip_next, "Next", o.media::next, size = 28.dp)
        }
    }
}

@Composable
private fun MediaPlay(playing: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val r by animateDpAsState(if (playing) 12.dp else 20.dp, Kit.fast(), label = "play")
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(r)).background(cs.primary).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Glyph(if (playing) R.drawable.sym_pause else R.drawable.sym_play_arrow, cs.onPrimary, 20.dp) }
}

// ── brightness, toggles, tools ────────────────────────────────────────────

@Composable
private fun BrightnessRow() {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val level by o.brightness.level
    val auto by o.brightness.auto
    val autoBg by animateColorAsState(if (auto) cs.primary else cs.surfaceContainerHigh, label = "autoBrightBg")
    val autoRadius by animateDpAsState(if (auto) 16.dp else 10.dp, Kit.fast(), label = "autoBrightR")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Slider(
            level,
            o.brightness::set,
            Modifier.weight(1f),
            height = 32.dp,
            icon = R.drawable.sym_brightness_7,
        )
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(autoRadius)).background(autoBg)
                .clickable(role = Role.Switch) {
                    o.brightness.toggleAuto()
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
                .semantics { contentDescription = if (auto) "Auto brightness on" else "Auto brightness off" },
            contentAlignment = Alignment.Center,
        ) {
            Glyph(
                R.drawable.sym_brightness_auto,
                tint = if (auto) cs.onPrimary else cs.onSurfaceVariant,
                size = 17.dp,
            )
        }
    }
}

@Composable
private fun Toggles() {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val tiles = o.tiles.toggles
    val ids = remember(tiles) { tiles.map { it.id } }
    val reorder = rememberLinearReorderState(horizontal = true, gap = 2.dp)
    Row(Modifier.fillMaxWidth().height(38.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        tiles.forEachIndexed { i, t ->
            key(t.id) {
                val on by t.observeEnabled()
                val shape = segmentShape(i, tiles.size, on, 19.dp)
                val bg by animateColorAsState(if (on) cs.primary else cs.surfaceContainerHigh, label = "tgbg")
                val floating = reorder.draggingId == t.id && reorder.lifted
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .floatingReorderItem(
                            state = reorder,
                            id = t.id,
                            ids = ids,
                            shape = shape,
                            onSwap = { from, to -> o.tiles.moveToggle(from, to) },
                            onCommit = { o.tiles.persistOrder() },
                            onLongTap = t::onLongClick,
                        )
                        .clip(shape)
                        .background(bg)
                        .then(if (floating) Modifier.border(1.5.dp, cs.onPrimary.copy(alpha = 0.6f), shape) else Modifier)
                        .clickable { t.toggle() },
                    contentAlignment = Alignment.Center,
                ) { TileIcon(t, if (on) cs.onPrimary else cs.onSurface) }
            }
        }
    }
}

@Composable
private fun TileIcon(t: TileAction, tint: Color) {
    Icon(tilePainter(t), t.label, Modifier.size(19.dp), tint = tint)
}

@Composable
private fun Tools(shown: (TileAction) -> Boolean = { true }) {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val tools = o.tiles.tools.filter(shown)
    val pages = tools.chunked(4)
    if (pages.isEmpty()) return
    val pager = rememberPagerState { pages.size }
    val reorder = rememberLinearReorderState(horizontal = true, gap = 4.dp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth(),
            pageSpacing = 8.dp,
            userScrollEnabled = !reorder.isDragging,
        ) { p ->
            val pageItems = pages.getOrNull(p).orEmpty()
            val pageIds = remember(pageItems) { pageItems.map { it.id } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pageItems.forEach { t ->
                    key(t.id) {
                        val on by t.observeEnabled()
                        val widgets = t.id == "widgets"
                        val count = o.widgets.settings.count
                        val active = on || (widgets && count > 0)
                        val shape = RoundedCornerShape(12.dp)
                        val floating = reorder.draggingId == t.id && reorder.lifted
                        Column(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .floatingReorderItem(
                                    state = reorder,
                                    id = t.id,
                                    ids = pageIds,
                                    shape = shape,
                                    onSwap = { from, to ->
                                        val fromId = pageIds.getOrNull(from) ?: return@floatingReorderItem
                                        val toId = pageIds.getOrNull(to) ?: return@floatingReorderItem
                                        o.tiles.moveTool(fromId, toId)
                                    },
                                    onCommit = { o.tiles.persistOrder() },
                                    onLongTap = t::onLongClick,
                                    onEdgeHover = { dir ->
                                        val targetPage = (p + dir).coerceIn(0, pages.lastIndex)
                                        if (targetPage != p) {
                                            val neighbor = if (dir > 0) pages[targetPage].firstOrNull() else pages[targetPage].lastOrNull()
                                            if (neighbor != null) {
                                                o.tiles.moveTool(t.id, neighbor.id)
                                                scope.launch { pager.animateScrollToPage(targetPage) }
                                            }
                                        }
                                    },
                                )
                                .clip(shape)
                                .background(if (active) cs.secondaryContainer else cs.surfaceContainer)
                                .then(if (floating) Modifier.border(1.5.dp, cs.primary.copy(alpha = 0.7f), shape) else Modifier)
                                .clickable { t.toggle() },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            TileIcon(t, if (active) cs.onSecondaryContainer else cs.onSurface)
                            Text(
                                if (widgets && count > 0) "${t.label} · $count" else t.label,
                                style = label(10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (active) cs.onSecondaryContainer else cs.onSurfaceVariant,
                            )
                        }
                    }
                }
                repeat(4 - pageItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (pages.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
            repeat(pages.size) { i ->
                val w by animateDpAsState(if (i == pager.currentPage) 12.dp else 4.dp, Kit.fast(), label = "dot")
                Box(
                    Modifier.size(w, 4.dp).clip(CircleShape)
                        .background(if (i == pager.currentPage) cs.primary else cs.outlineVariant)
                )
            }
        }
    }
}

/** Horizontal fling distance (px) that counts as a swipe on the profile switcher. */
internal const val SWIPE_PX = 48f

internal fun swiped(dx: Float) = abs(dx) >= SWIPE_PX
