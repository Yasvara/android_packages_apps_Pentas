/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.ui.tuning

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import org.yasvara.pentas.ui.kit.Segmented
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.yasvara.pentas.kelir.Band
import org.yasvara.pentas.kelir.Kelir
import org.yasvara.pentas.kelir.KelirCodec
import org.yasvara.pentas.kelir.KelirSlots
import org.yasvara.pentas.kelir.KelirStack
import org.yasvara.pentas.kelir.KelirStatus
import org.yasvara.pentas.lapis.LapisStatus
import org.yasvara.pentas.ui.kit.Block
import org.yasvara.pentas.ui.kit.Kit
import org.yasvara.pentas.ui.kit.SettingRow
import org.yasvara.pentas.ui.kit.SwitchRow
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num
import org.yasvara.pentas.ui.kit.segmentShape
import kotlin.math.roundToInt

/** Sizes of the two densities: the sidebar's compact and the app's comfortable. */
internal class KelirDensity(val slot: Dp, val card: Dp, val title: Float, val glyph: Dp) {
    companion object {
        val Compact = KelirDensity(52.dp, Kit.Card, 13f, 28.dp)
        val Comfortable = KelirDensity(64.dp, 24.dp, 15f, 36.dp)
    }
}

/**
 * What the 3D band can count on in the running game, from its Lapis status; null while the game
 * isn't running, when nothing is known.
 */
internal enum class SceneDepth(val text: String, val ready: Boolean = false) {
    READY("the game's depth · Depth ✓ Camera ✓", ready = true),
    LEARNING("Lapis is still learning this game"),
    NONE("No depth from this game"),
    VIDEO("Video has no depth"),
    OFF("Lapis is off for this game");

    companion object {
        fun of(lapisOn: Boolean, status: LapisStatus?): SceneDepth = when {
            !lapisOn -> OFF
            status == null || status.state != LapisStatus.State.ENGAGED -> LEARNING
            status.has(LapisStatus.SCENE) && status.has(LapisStatus.DEPTH) && status.has(LapisStatus.CAMERA) -> READY
            else -> NONE
        }
    }
}

private sealed interface KelirPage {
    data object Stack : KelirPage
    data object Add : KelirPage
    data object Presets : KelirPage
    data class CopyInto(val slot: Int) : KelirPage
}

/**
 * Kelir for one game: the three slots and Off, what the running slot costs, compare, and the
 * running slot's filters by band. The game reports swatches, curves and cost only while this is
 * open; when it isn't running they are left out, never guessed. [onCompare] opens the
 * fullscreen compare, where the game can be seen.
 */
@Composable
fun KelirEditor(pkg: String, comfortable: Boolean = false, onCompare: (() -> Unit)? = null) {
    val tuning = rememberTuning()
    val kelir = tuning.kelir()
    val luwes = tuning.luwes()
    var depth by remember(pkg) { mutableStateOf<SceneDepth?>(null) }
    // Lapis has the game's 3D before its HUD, so the HUD can be hidden.
    var scene by remember(pkg) { mutableStateOf(false) }
    var hideHud by remember(pkg) { mutableStateOf(false) }
    var wholeScreen by remember(pkg) { mutableStateOf(false) }
    // When Lapis last had the scene: a game changing screens loses it for a moment, and the
    // switch greys only once it stays gone.
    var sceneAt by remember(pkg) { mutableLongStateOf(0L) }
    val density = if (comfortable) KelirDensity.Comfortable else KelirDensity.Compact
    var slots by remember(pkg) { mutableStateOf(kelir.slots(pkg)) }
    var page by remember(pkg) { mutableStateOf<KelirPage>(KelirPage.Stack) }
    var status by remember(pkg) { mutableStateOf<KelirStatus?>(null) }
    var restart by remember(pkg) { mutableStateOf(false) }
    // What the game reported for a stack, kept while that stack is unchanged.
    val swatches = remember(pkg) { mutableStateMapOf<Int, Pair<KelirStack, List<Int>>>() }
    var curves by remember(pkg) { mutableStateOf<Pair<KelirStack, List<List<Int>>>?>(null) }

    val view = KelirCodec.View(inspect = true)
    fun preview(next: KelirSlots) {
        slots = next
        kelir.preview(pkg, next, view)
    }
    fun commit(next: KelirSlots) {
        slots = next
        kelir.commit(pkg, next, view)
    }

    val current by rememberUpdatedState(slots)
    DisposableEffect(pkg) {
        kelir.preview(pkg, current, KelirCodec.View(inspect = true))
        // What was only tried, a preset or a drag, goes with the editor.
        onDispose { kelir.preview(pkg, kelir.slots(pkg), KelirCodec.View()) }
    }
    LaunchedEffect(pkg) {
        while (true) {
            val (s, waits) = withContext(Dispatchers.IO) {
                kelir.status(pkg).filterNotNull().maxByOrNull { it.state.ordinal } to
                    (current.slots.any { it.nodes.isNotEmpty() } && kelir.needsRestart(pkg))
            }
            status = s
            restart = waits
            if (kelir.video(pkg)) {
                depth = SceneDepth.VIDEO
            } else withContext(Dispatchers.IO) {
                val pages = luwes.lapisStatus(pkg)
                val lapis = luwes.lapis(pkg)
                val best = pages.filterNotNull().maxByOrNull { it.state.ordinal }
                depth = if (pages.isEmpty()) null else SceneDepth.of(lapis.on, best)
                val now = SystemClock.uptimeMillis()
                if (best?.state == LapisStatus.State.ENGAGED && best.has(LapisStatus.SCENE)) sceneAt = now
                scene = sceneAt != 0L && now - sceneAt < SCENE_GRACE_MS
                hideHud = lapis.hideHud
                wholeScreen = lapis.wholeScreen
            }
            val running = current.running
            if (s != null && running != null && s.state == KelirStatus.State.RUNNING &&
                s.appliedRevision == kelir.published(pkg)
            ) {
                swatches[current.active] = running to s.swatch
                curves = running to s.curves
            }
            delay(STATUS_POLL_MS)
        }
    }

    when (val p = page) {
        KelirPage.Add -> KelirAdd(
            slot = slots.active,
            stack = slots.running ?: KelirStack(),
            depth = depth,
            onStart = { commit(slots.edit(slots.active, it)); page = KelirPage.Stack },
            onAdd = { kind -> commit(slots.edit(slots.active, (slots.running ?: KelirStack()).add(kind))); page = KelirPage.Stack },
            onClose = { page = KelirPage.Stack },
        )
        KelirPage.Presets -> KelirPresetBrowser(
            slots = slots,
            depth = depth,
            onTry = { preview(it) },
            onKeep = { commit(it); page = KelirPage.Stack },
            onClose = { preview(it); page = KelirPage.Stack },
        )
        is KelirPage.CopyInto -> KelirCopyFrom(
            pkg = pkg, slot = p.slot, kelir = kelir,
            onPick = { stack -> commit(slots.edit(p.slot, stack)); page = KelirPage.Stack },
            onClose = { page = KelirPage.Stack },
        )
        KelirPage.Stack -> Column(verticalArrangement = Arrangement.spacedBy(Kit.Gap)) {
            var menu by remember(pkg) { mutableStateOf<Int?>(null) }
            SlotRow(slots, swatches, density, onSelect = { menu = null; commit(slots.select(it)) }, onMenu = { menu = it.takeIf { it != menu } })
            menu?.let { m ->
                SlotMenu(
                    slots, m,
                    onDuplicate = { to -> commit(slots.duplicate(m, to)); menu = null },
                    onCopyFrom = { page = KelirPage.CopyInto(m); menu = null },
                    onReset = { commit(slots.reset(m)); menu = null },
                )
            }
            if (restart) RestartRow { kelir.restart(pkg); restart = false }
            val running = slots.running ?: return@Column
            status?.let { CostBlock(it, kelir, underHud = !wholeScreen && depth == SceneDepth.READY && it.vulkan) }
            val comparable = onCompare != null && running.nodes.isNotEmpty() && status?.state == KelirStatus.State.RUNNING
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PillButton("Presets", "Try others", Modifier.weight(1f)) { page = KelirPage.Presets }
                if (comparable) PillButton("Compare", "Before | after", Modifier.weight(1f)) { onCompare?.invoke() }
            }
            if (depth != SceneDepth.VIDEO) {
                Block(padding = 10.dp) {
                    SettingRow("Grade")
                    Segmented(
                        listOf("Game 3D" to false, "Whole screen" to true),
                        selected = wholeScreen,
                        onSelect = { whole -> if (luwes.setWholeScreen(pkg, whole)) wholeScreen = whole },
                    )
                    Text(
                        if (wholeScreen) "The finished frame, HUD and menus included, the same on every screen."
                        else "Under the HUD where Lapis has the game's 3D; the whole screen where it has not.",
                        style = label(11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val canHide = scene || hideHud
                Block(padding = 10.dp, modifier = Modifier.alpha(if (canHide) 1f else 0.38f)) {
                    SwitchRow("Hide HUD", hideHud, { on ->
                        if (luwes.setHideHud(pkg, on)) hideHud = on
                    }, enabled = canHide)
                    Text(
                        if (canHide) "The game's 3D alone, for clean footage. Back on when the game closes."
                        else "Needs the game's 3D from Lapis, which this screen does not show.",
                        style = label(11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            KelirStackEditor(
                stack = running,
                density = density,
                curves = curves?.takeIf { it.first.nodes.map { n -> n.kind } == running.nodes.map { n -> n.kind } }?.second,
                depth = depth,
                onPreview = { preview(slots.edit(slots.active, it)) },
                onCommit = { commit(slots.edit(slots.active, it)) },
            )
            AddButton(running, density) { page = KelirPage.Add }
        }
    }
}

private const val STATUS_POLL_MS = 250L
private const val SCENE_GRACE_MS = 3_000L

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SlotRow(
    slots: KelirSlots,
    swatches: Map<Int, Pair<KelirStack, List<Int>>>,
    density: KelirDensity,
    onSelect: (Int) -> Unit,
    onMenu: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ids = (0 until KelirSlots.COUNT) + KelirSlots.OFF
    Row(
        Modifier.fillMaxWidth().height(density.slot).semantics { contentDescription = "Kelir slot" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ids.forEachIndexed { i, id ->
            val on = id == slots.active
            val bg by animateColorAsState(if (on) cs.primary else cs.surfaceContainerHigh, label = "slot")
            val fg = if (on) cs.onPrimary else cs.onSurface
            val stack = slots.slots.getOrNull(id)
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(segmentShape(i, ids.size, on, density.slot / 2.6f)).background(bg)
                    .combinedClickable(
                        role = Role.RadioButton,
                        onLongClick = if (id != KelirSlots.OFF) ({ onMenu(id) }) else null,
                        onClick = { if (!on) onSelect(id) },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Text(if (id == KelirSlots.OFF) "Off" else "${id + 1}", style = label(if (id == KelirSlots.OFF) 13.sp else 14.sp, 600), color = fg)
                val swatch = swatches[id]?.takeIf { it.first == stack }?.second
                when {
                    stack == null -> Unit
                    swatch != null -> Swatch(swatch)
                    else -> Text(
                        if (stack.nodes.isEmpty()) "Empty" else "${stack.nodes.size} filters",
                        style = label(10.sp), color = fg.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SlotMenu(slots: KelirSlots, slot: Int, onDuplicate: (Int) -> Unit, onCopyFrom: () -> Unit, onReset: () -> Unit) {
    val filled = slots.slots[slot].nodes.isNotEmpty()
    Block(padding = 8.dp) {
        Text("Slot ${slot + 1}", style = label(11.5.sp, 600), color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (filled) {
                for (to in 0 until KelirSlots.COUNT) if (to != slot) Chip("Copy to ${to + 1}") { onDuplicate(to) }
            }
            Chip("From a game") { onCopyFrom() }
            if (filled) Chip("Clear", warn = true) { onReset() }
        }
    }
}

@Composable
internal fun Chip(text: String, warn: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        Modifier.height(28.dp).clip(CircleShape)
            .background(if (warn) cs.errorContainer else cs.surfaceContainerHighest)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        style = label(11.5.sp, 550), color = if (warn) cs.onErrorContainer else cs.onSurface, maxLines = 1,
    )
}

@Composable
private fun RestartRow(onRestart: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Block(padding = 10.dp) {
        SettingRow("Applies after restart", tag = { RestartTag() }) {
            Text(
                "Restart game",
                Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onRestart)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = label(12.sp, 600), color = cs.primary,
            )
        }
    }
}

/**
 * What Kelir does in the game: its output, and its measured GPU time against the frame.
 * [underHud]: Lapis has the game's 3D, so the filters go under the HUD and the HUD keeps its own
 * colours.
 */
@Composable
private fun CostBlock(s: KelirStatus, kelir: Kelir, underHud: Boolean) {
    val cs = MaterialTheme.colorScheme
    Block(padding = 10.dp) {
        val api = if (s.vulkan) "Vulkan" else "GLES"
        val depth = when (s.depth) {
            KelirStatus.Depth.SRGB8 -> "8-bit sRGB"
            else -> "${s.depth.bits}-bit"
        }
        val path = when (s.path) {
            KelirStatus.Path.FETCH -> "in-pass"
            KelirStatus.Path.TEXTURE -> "copy pass"
            KelirStatus.Path.VULKAN -> "own image"
            KelirStatus.Path.NONE -> null
        }
        val facts = listOfNotNull(
            api, depth, "${s.width}×${s.height}".takeIf { s.width > 0 }, if (underHud) "under the HUD" else path,
        ).joinToString(" · ")
        when (s.state) {
            KelirStatus.State.DECLINED -> {
                Text(facts, style = label(10.5.sp), fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
                Text("Not applied: ${s.reason.text}", style = label(12.sp, 550), color = cs.error)
            }
            KelirStatus.State.RUNNING -> {
                val hz = remember { kelir.panelHz() }
                val frameMs = 1000f / hz
                val ms = s.gpuMicros / 1000f
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(facts, Modifier.weight(1f), style = label(10.5.sp), fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant, maxLines = 1)
                    Text("%.2f".format(ms), style = num(15.sp), color = cs.onSurface)
                    Text("ms", style = label(11.sp), color = cs.onSurfaceVariant)
                }
                val share = (ms / frameMs).coerceIn(0f, 1f)
                Box(
                    Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(cs.surfaceContainerHighest)
                        .semantics { contentDescription = "Kelir takes %.2f ms of the %.2f ms frame at %d Hz".format(ms, frameMs, hz.roundToInt()) },
                ) {
                    Box(
                        Modifier.fillMaxWidth(share).height(8.dp).clip(CircleShape)
                            .background(if (share > 0.25f) cs.tertiary else cs.primary),
                    )
                }
                Row {
                    Text("0", style = label(9.5.sp), color = cs.outline)
                    Spacer(Modifier.weight(1f))
                    Text("frame at ${hz.roundToInt()} Hz · ${"%.2f".format(frameMs)} ms", style = label(9.5.sp), color = cs.outline)
                }
                if (underHud) {
                    Text("Filters go under the game's HUD; the HUD keeps its own colours.", style = label(11.sp), color = cs.onSurfaceVariant)
                }
            }
            KelirStatus.State.WAITING, KelirStatus.State.OFF ->
                Text(facts.ifEmpty { "Waiting for a frame" }, style = label(10.5.sp), fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun PillButton(title: String, hint: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.height(40.dp).clip(CircleShape).background(cs.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, Modifier.weight(1f), style = label(13.sp, 600), color = cs.onSurface, maxLines = 1)
        Text(hint, style = label(11.sp), color = cs.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun AddButton(stack: KelirStack, density: KelirDensity, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val full = stack.nodes.size >= KelirStack.MAX_NODES
    Row(
        Modifier.fillMaxWidth().height(if (density === KelirDensity.Comfortable) 48.dp else 40.dp).clip(CircleShape)
            .background(cs.primaryContainer).clickable(enabled = !full, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (full) "Stack full" else "Add filter",
            style = label(13.sp, 600), color = cs.onPrimaryContainer.copy(alpha = if (full) 0.6f else 1f),
        )
        Text(" · ${stack.nodes.size}/${KelirStack.MAX_NODES}", style = label(13.sp, 500), color = cs.onPrimaryContainer.copy(alpha = 0.75f))
    }
}

internal fun Band.title() = when (this) {
    Band.SCENE -> "3D"
    Band.DETAIL -> "Detail"
    Band.COLOUR -> "Colour"
    Band.SCREEN -> "Screen"
}

/** The band's cap, or null when only the stack's total limits it. */
internal fun Band.cap(): Int? = when (this) {
    Band.DETAIL -> KelirStack.MAX_DETAIL
    Band.SCREEN -> KelirStack.MAX_SCREEN
    Band.COLOUR, Band.SCENE -> null
}

internal fun Modifier.animatedSize() = this.animateContentSize(Kit.default())
