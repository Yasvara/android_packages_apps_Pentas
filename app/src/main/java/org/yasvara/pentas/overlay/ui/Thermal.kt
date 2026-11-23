/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.ui

import android.os.UserHandle
import android.provider.Settings
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yasvara.pentas.R
import org.yasvara.pentas.overlay.LocalOverlay
import org.yasvara.pentas.thermal.ThermalProfiles
import org.yasvara.pentas.ui.kit.Glyph
import org.yasvara.pentas.ui.kit.Kit
import org.yasvara.pentas.ui.kit.label

private const val KEY_PINS = "pentas_thermal_pins"
private val DEFAULT_PINS = listOf(
    ThermalProfiles.PROFILE_AUTO, ThermalProfiles.PROFILE_BATTERY_SAVER, 3, 4, ThermalProfiles.PROFILE_PERFORMANCE,
)

/** Profiles worth offering in a game; scenes like Camera or Navigation are left out. */
private val GAME_SCENES = listOf(0, 16, 3, 4, 2, 7, 5, 6, 1)

private class ThermalModel(val options: List<ThermalProfiles.ProfileOption>, val pins: List<Int>)

@Composable
private fun rememberThermal(): Pair<ThermalModel, (List<Int>) -> Unit> {
    val o = LocalOverlay.current
    val ctx = LocalContext.current
    val rawPins by o.settings.observable.stringState(KEY_PINS, "")
    val options = remember {
        val custom = Settings.System.getStringForUser(ctx.contentResolver, ThermalProfiles.KEY_CUSTOM_PROFILES, UserHandle.USER_CURRENT)
        ThermalProfiles.options(custom, builtinIndices = GAME_SCENES)
    }
    val validIndices = remember(options) { options.mapTo(HashSet()) { it.index } }
    val pins = remember(rawPins, validIndices) {
        val parsed = rawPins.split(',').mapNotNull { it.toIntOrNull() }.ifEmpty { DEFAULT_PINS }
        parsed.filter { it in validIndices }
    }
    return ThermalModel(options, pins) to { new ->
        o.settings.observable.setString(KEY_PINS, "", new.joinToString(","))
    }
}

private fun ThermalModel.name(i: Int) = options.firstOrNull { it.index == i }?.name ?: "Auto"

/**
 * ‹ [profile ▾] ›: swipe or the arrows step through the pinned profiles, tap or long-press
 * opens the full list. The choice is per game and applied by ThermalController.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThermalSwitcher() {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val pkg = o.pkg ?: return
    val (model, _) = rememberThermal()
    var current by o.state.profile
    LaunchedEffect(pkg) { current = o.perApp.thermalProfile(pkg) }
    fun pick(i: Int) {
        current = i
        o.perApp.setThermalProfile(pkg, i)
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }
    val pins = model.pins
    val pos = pins.indexOf(current)
    fun step(d: Int) {
        if (pins.isEmpty()) return
        val from = if (pos < 0) 0 else pos
        pick(pins[(from + d + pins.size) % pins.size])
    }
    Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(18.dp, 6.dp, 6.dp, 18.dp)).background(cs.surfaceContainerHigh)
                .clickable { step(-1) },
            contentAlignment = Alignment.Center,
        ) { Glyph(R.drawable.sym_chevron_left, cs.onSurfaceVariant, 18.dp, "Previous profile") }
        var drag by remember { mutableFloatStateOf(0f) }
        Row(
            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(cs.secondaryContainer)
                .pointerInput(pins, current) {
                    detectHorizontalDragGestures(
                        onDragStart = { drag = 0f },
                        onDragEnd = { if (swiped(drag)) step(if (drag < 0) 1 else -1) },
                    ) { c, d -> drag += d; c.consume() }
                }
                .combinedClickable(
                    onLongClick = { o.state.profileList.value = true },
                    onClick = { o.state.profileList.value = true },
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Glyph(R.drawable.sym_thermostat, cs.onSecondaryContainer, 15.dp)
            // Single weighted container lets the profile name use all free width instead of splitting 50/50 with a Spacer.
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    model.name(current),
                    Modifier.weight(1f, fill = false).basicMarquee(iterations = Int.MAX_VALUE),
                    style = label(12.5.sp, 600),
                    color = cs.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Glyph(R.drawable.sym_expand_more, cs.onSecondaryContainer, 14.dp)
            }
            if (pins.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    pins.indices.forEach { i ->
                        val w by animateDpAsState(if (i == pos) 10.dp else 3.dp, Kit.fast(), label = "pin")
                        Box(
                            Modifier.size(w, 3.dp).clip(CircleShape)
                                .background(if (i == pos) cs.onSecondaryContainer else cs.onSecondaryContainer.copy(alpha = 0.35f))
                        )
                    }
                }
            }
        }
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(6.dp, 18.dp, 18.dp, 6.dp)).background(cs.surfaceContainerHigh)
                .clickable { step(1) },
            contentAlignment = Alignment.Center,
        ) { Glyph(R.drawable.sym_chevron_right, cs.onSurfaceVariant, 18.dp, "Next profile") }
    }
}

/** The listbox in front of the home: pinned profiles (the swipe cycle) above the rest. */
@Composable
fun ProfileList() {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val pkg = o.pkg ?: return
    val (model, setPins) = rememberThermal()
    val pins = model.pins
    var current by o.state.profile
    val close = { o.state.profileList.value = false }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(interactionSource = null, indication = null, onClick = close),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(10.dp).fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(16.dp))
                .background(cs.surfaceContainer).clickable(interactionSource = null, indication = null) {}
                .verticalScroll(rememberScrollState()).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            @Composable fun Heading(t: String) = Text(t, Modifier.padding(start = 10.dp, top = 4.dp, bottom = 2.dp),
                style = label(10.5.sp, 600), color = cs.primary)
            @Composable fun Item(opt: ThermalProfiles.ProfileOption) {
                val on = opt.index == current
                val pinned = opt.index in pins
                Row(
                    Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (on) cs.secondaryContainer else Color.Transparent)
                        .clickable {
                            current = opt.index
                            o.perApp.setThermalProfile(pkg, opt.index)
                            close()
                        }
                        .padding(start = 10.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (on) Glyph(R.drawable.sym_check, cs.onSecondaryContainer, 14.dp)
                    Text(
                        opt.name,
                        Modifier.weight(1f),
                        style = label(12.5.sp, if (on) 600 else 500),
                        color = if (on) cs.onSecondaryContainer else cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).clickable {
                            setPins(if (pinned) pins - opt.index else pins + opt.index)
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Glyph(
                            R.drawable.sym_keep,
                            if (pinned) cs.primary else cs.outline,
                            15.dp,
                            if (pinned) "Unpin ${opt.name}" else "Pin ${opt.name}",
                        )
                    }
                }
            }
            Heading("Pinned · swipe to cycle")
            pins.mapNotNull { p -> model.options.firstOrNull { it.index == p } }.forEach { Item(it) }
            Box(Modifier.padding(horizontal = 8.dp, vertical = 3.dp).fillMaxWidth().height(1.dp).background(cs.outlineVariant))
            Heading("Other")
            model.options.filter { it.index !in pins }.forEach { Item(it) }
        }
    }
}
