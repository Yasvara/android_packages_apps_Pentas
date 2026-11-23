/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.ui.tuning

import android.graphics.Rect
import android.graphics.Region
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.yasvara.pentas.R
import org.yasvara.pentas.kelir.KelirCodec
import org.yasvara.pentas.kelir.KelirPreset
import org.yasvara.pentas.kelir.KelirPresets
import org.yasvara.pentas.kelir.KelirSlots
import org.yasvara.pentas.luwes.LuwesSettings
import org.yasvara.pentas.luwes.LuwesSettings.Mode
import org.yasvara.pentas.ui.kit.Block
import org.yasvara.pentas.ui.kit.Glyph
import org.yasvara.pentas.ui.kit.Kit
import org.yasvara.pentas.ui.kit.RoundButton
import org.yasvara.pentas.ui.kit.Segmented
import org.yasvara.pentas.ui.kit.SettingRow
import org.yasvara.pentas.ui.kit.SwitchRow
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num

private enum class CompareTab { COLOR, FRAME_GEN }

/**
 * Compare over the whole game: the game as drawn left of the divider, with Kelir right of it,
 * plus an expandable right-side floating card for live color slot/preset switching and
 * Frame Gen (Luwes) ON/OFF A/B comparison. Only registered controls take touches; the rest
 * of the screen stays the game's so the scene stays playable while comparing.
 */
@Composable
fun KelirCompare(pkg: String, onBack: () -> Unit, onDone: () -> Unit) {
    val tuning = rememberTuning()
    val kelir = tuning.kelir()
    val luwes = tuning.luwes()
    val haptic = LocalHapticFeedback.current

    val initialSlots = remember(pkg) { kelir.slots(pkg) }
    var slots by remember(pkg) { mutableStateOf(initialSlots) }
    var split by remember(pkg) { mutableFloatStateOf(DEFAULT_SPLIT) }
    var cardOpen by remember(pkg) { mutableStateOf(false) }
    var tab by remember(pkg) { mutableStateOf(CompareTab.COLOR) }
    var committed by remember(pkg) { mutableStateOf(false) }

    val luwesAvailable by produceState(false) { value = luwes.available.await() }
    val hz = remember { luwes.panelHz() }
    var luwesSettings by remember(pkg) { mutableStateOf(luwes.settings(pkg)) }
    var lastOnMultiplier by remember(pkg) {
        mutableStateOf(luwesSettings.multiplier.takeIf { it >= LuwesSettings.MIN } ?: LuwesSettings.MIN)
    }
    var luwesStatusText by remember(pkg) { mutableStateOf<String?>(null) }
    var luwesRestart by remember(pkg) { mutableStateOf(false) }

    LaunchedEffect(pkg, luwesAvailable, luwesSettings, cardOpen) {
        if (!luwesAvailable || !cardOpen) return@LaunchedEffect
        while (true) {
            val (desc, needsRestart) = withContext(Dispatchers.IO) {
                if (!luwesSettings.on) return@withContext null to false
                val st = luwes.status(pkg).filterNotNull().maxByOrNull { it.state.ordinal }
                st?.describe() to luwes.needsRestart(pkg)
            }
            luwesStatusText = desc
            luwesRestart = needsRestart
            delay(1000)
        }
    }

    fun preview(nextSlots: KelirSlots = slots, nextSplit: Float = split) {
        slots = nextSlots
        split = nextSplit.coerceIn(0f, 1f)
        kelir.preview(pkg, slots, KelirCodec.View(split = split))
    }

    fun applyLuwes(next: LuwesSettings) {
        if (next.on) lastOnMultiplier = next.multiplier
        luwesSettings = next
        luwes.set(pkg, next)
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    DisposableEffect(pkg) {
        kelir.preview(pkg, slots, KelirCodec.View(split = split))
        onDispose {
            val restore = if (committed) slots else initialSlots
            kelir.preview(pkg, restore, KelirCodec.View())
        }
    }

    val touchable = rememberTouchableRegion()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val x = maxWidth * split
        val cs = MaterialTheme.colorScheme

        // Vertical split grabber
        Box(
            Modifier.offset(x = x - GRAB / 2).width(GRAB).fillMaxHeight()
                .then(touchable.part("divider"))
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        preview(nextSplit = split + drag.x / width)
                    }
                }
                .semantics { contentDescription = "Divider at ${(split * 100).toInt()}%" },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(KNOB).clip(CircleShape).background(Color.White.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) { Text("‹ ›", style = label(13.sp, 700), color = Color.Black) }
        }

        // Left badge
        Side("Original", Modifier.align(Alignment.TopStart))

        // Interactive right badge: toggles the Compare floating card
        val rightLabel = buildString {
            append(slots.running?.let { "Kelir · Slot ${slots.active + 1}" } ?: "Kelir off")
            if (luwesAvailable && luwesSettings.on) {
                append(" · FG ${LuwesSettings.effective(luwesSettings.multiplier, hz)}×")
            }
        }
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .then(touchable.part("menu_chip"))
                .clip(CircleShape)
                .background(if (cardOpen) cs.primaryContainer.copy(alpha = 0.94f) else Color.Black.copy(alpha = 0.62f))
                .border(1.dp, if (cardOpen) cs.primary else Color.White.copy(alpha = 0.2f), CircleShape)
                .clickable {
                    cardOpen = !cardOpen
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Glyph(
                R.drawable.sym_tune,
                tint = if (cardOpen) cs.onPrimaryContainer else Color.White,
                size = 14.dp,
            )
            Text(
                rightLabel,
                style = label(12.sp, 600),
                color = if (cardOpen) cs.onPrimaryContainer else Color.White,
                maxLines = 1,
            )
            Glyph(
                R.drawable.sym_expand_more,
                tint = if (cardOpen) cs.onPrimaryContainer else Color.White.copy(alpha = 0.85f),
                size = 14.dp,
            )
        }

        // Expandable right-side floating card (Color presets/slots + Frame Gen ON/OFF compare)
        AnimatedVisibility(
            visible = cardOpen,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp, top = 52.dp, bottom = 64.dp),
            enter = fadeIn(Kit.fast()) + slideInHorizontally(Kit.default()) { it / 2 },
            exit = fadeOut(Kit.fast()) + slideOutHorizontally(Kit.default()) { it / 2 },
        ) {
            DisposableEffect(Unit) {
                onDispose { touchable.remove("card") }
            }
            CompareRightCard(
                modifier = Modifier.then(touchable.part("card")),
                tab = tab,
                onTab = { tab = it },
                slots = slots,
                split = split,
                onSelectSlot = { idx ->
                    preview(nextSlots = slots.select(idx))
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                },
                onPickPreset = { preset ->
                    val targetSlot = if (slots.active in 0 until KelirSlots.COUNT) slots.active else 0
                    preview(nextSlots = slots.edit(targetSlot, preset.stack).select(targetSlot))
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                },
                onSnapSplit = { target ->
                    preview(nextSplit = target)
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                },
                luwesAvailable = luwesAvailable,
                luwesSettings = luwesSettings,
                lastOnMultiplier = lastOnMultiplier,
                hz = hz,
                luwesStatus = luwesStatusText,
                luwesRestart = luwesRestart,
                onLuwesChange = ::applyLuwes,
                onRestartGame = { luwes.restart(pkg); luwesRestart = false },
                onClose = { cardOpen = false },
            )
        }

        // Bottom Back / Done pill
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).then(touchable.part("actions"))
                .clip(CircleShape)
                .background(cs.surfaceContainerHigh.copy(alpha = 0.94f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Action("Back", primary = false) {
                committed = false
                onBack()
            }
            Action("Done", primary = true) {
                committed = true
                kelir.commit(pkg, slots, KelirCodec.View())
                onDone()
            }
        }
    }
}

@Composable
private fun CompareRightCard(
    modifier: Modifier,
    tab: CompareTab,
    onTab: (CompareTab) -> Unit,
    slots: KelirSlots,
    split: Float,
    onSelectSlot: (Int) -> Unit,
    onPickPreset: (KelirPreset) -> Unit,
    onSnapSplit: (Float) -> Unit,
    luwesAvailable: Boolean,
    luwesSettings: LuwesSettings,
    lastOnMultiplier: Int,
    hz: Float,
    luwesStatus: String?,
    luwesRestart: Boolean,
    onLuwesChange: (LuwesSettings) -> Unit,
    onRestartGame: () -> Unit,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val presets = remember { KelirPresets.usable(depth = null) }
    val shape = RoundedCornerShape(24.dp)

    Column(
        modifier
            .width(256.dp)
            .heightIn(max = 320.dp)
            .clip(shape)
            .background(cs.surface.copy(alpha = 0.94f))
            .border(1.dp, cs.outlineVariant.copy(alpha = 0.5f), shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Compare", style = label(13.5.sp, 650), color = cs.onSurface)
            Spacer(Modifier.weight(1f))
            Text("${(split * 100).toInt()}%", style = num(11.sp, 600), color = cs.primary)
            RoundButton(R.drawable.sym_close, "Close compare card", onClose, size = 26.dp)
        }

        Segmented(
            options = listOf("Color" to CompareTab.COLOR, "Frame Gen" to CompareTab.FRAME_GEN),
            selected = tab,
            onSelect = onTab,
            height = 28.dp,
        )

        when (tab) {
            CompareTab.COLOR -> {
                val slotOptions = listOf("Off" to KelirSlots.OFF) + (0 until KelirSlots.COUNT).map { "Slot ${it + 1}" to it }
                Segmented(
                    options = slotOptions,
                    selected = slots.active,
                    onSelect = onSelectSlot,
                    height = 28.dp,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("25%" to 0.25f, "50%" to 0.5f, "75%" to 0.75f).forEach { (lbl, frac) ->
                        Box(
                            Modifier.weight(1f).height(24.dp).clip(CircleShape)
                                .background(cs.surfaceContainerHigh)
                                .clickable { onSnapSplit(frac) },
                            contentAlignment = Alignment.Center,
                        ) { Text(lbl, style = num(10.5.sp, 550), color = cs.onSurfaceVariant) }
                    }
                }
                LazyColumn(
                    Modifier.weight(1f, fill = false).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(presets, key = { it.name }) { preset ->
                        val active = slots.running == preset.stack
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (active) cs.secondaryContainer else cs.surfaceContainer)
                                .clickable { onPickPreset(preset) }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    preset.name,
                                    style = label(12.sp, if (active) 650 else 550),
                                    color = if (active) cs.onSecondaryContainer else cs.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    preset.note,
                                    style = label(10.sp),
                                    color = if (active) cs.onSecondaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (active) Glyph(R.drawable.sym_check, cs.onSecondaryContainer, 14.dp)
                        }
                    }
                }
            }

            CompareTab.FRAME_GEN -> {
                if (!luwesAvailable) {
                    Block(padding = 10.dp) {
                        Text(
                            "Frame generation (Luwes) is not available on this device.",
                            style = label(11.5.sp),
                            color = cs.onSurfaceVariant,
                        )
                    }
                } else {
                    Block(padding = 8.dp) {
                        SwitchRow(
                            "Frame Gen A/B",
                            checked = luwesSettings.on,
                            onChange = { on ->
                                val mult = if (on) lastOnMultiplier.coerceAtLeast(LuwesSettings.MIN) else LuwesSettings.OFF
                                onLuwesChange(luwesSettings.copy(multiplier = mult))
                            },
                        )
                        val selected = if (luwesSettings.on) LuwesSettings.effective(luwesSettings.multiplier, hz) else LuwesSettings.OFF
                        Segmented(
                            options = listOf("Off" to LuwesSettings.OFF) + LuwesSettings.multipliers(hz).map { "$it×" to it },
                            selected = selected,
                            onSelect = { onLuwesChange(luwesSettings.copy(multiplier = it)) },
                            height = 28.dp,
                        )
                        if (luwesSettings.on) {
                            SettingRow(
                                "Cadence",
                                value = "${LuwesSettings.cadence(selected, hz)} fps",
                            )
                            Segmented(
                                options = listOf("Extrapolate" to Mode.EXTRAPOLATE, "Interpolate" to Mode.INTERPOLATE),
                                selected = luwesSettings.mode,
                                onSelect = { onLuwesChange(luwesSettings.copy(mode = it)) },
                                height = 26.dp,
                            )
                        }
                        luwesStatus?.let {
                            Text(it, style = label(10.5.sp), color = cs.onSurfaceVariant)
                        }
                        if (luwesRestart) {
                            SettingRow("Applies after restart") {
                                Text(
                                    "Restart",
                                    Modifier.clip(CircleShape).background(cs.primaryContainer)
                                        .clickable(onClick = onRestartGame)
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = label(11.sp, 600),
                                    color = cs.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The window takes touches only inside the parts given to it; the rest pass to the game. */
private class TouchableRegion(private val apply: (Region?) -> Unit) {
    private val parts = mutableMapOf<String, Rect>()

    private fun push() = apply(Region().apply { parts.values.forEach { union(it) } })

    fun part(name: String): Modifier = Modifier.onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        parts[name] = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
        push()
    }

    fun remove(name: String) {
        if (parts.remove(name) != null) push()
    }
}

@Composable
private fun rememberTouchableRegion(): TouchableRegion {
    val view = LocalView.current
    val region = remember(view) { TouchableRegion { view.rootSurfaceControl?.setTouchableRegion(it) } }
    DisposableEffect(view) { onDispose { view.rootSurfaceControl?.setTouchableRegion(null) } }
    return region
}

private const val DEFAULT_SPLIT = 0.5f
private val KNOB = 36.dp
private val GRAB = 48.dp

@Composable
private fun Side(text: String, modifier: Modifier) {
    Text(
        text,
        modifier.padding(16.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = label(12.sp, 600), color = Color.White,
    )
}

@Composable
private fun Action(text: String, primary: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        Modifier.height(40.dp).clip(CircleShape).background(if (primary) cs.primary else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 10.dp),
        style = label(14.sp, 600), color = if (primary) cs.onPrimary else cs.onSurface,
    )
}
