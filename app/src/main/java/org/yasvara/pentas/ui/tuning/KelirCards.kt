/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.ui.tuning

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import org.yasvara.pentas.kelir.Band
import org.yasvara.pentas.kelir.KelirKinds
import org.yasvara.pentas.kelir.KelirNode
import org.yasvara.pentas.kelir.KelirStack
import org.yasvara.pentas.kelir.Param
import org.yasvara.pentas.ui.kit.Kit
import org.yasvara.pentas.ui.kit.Segmented
import org.yasvara.pentas.ui.kit.SettingRow
import org.yasvara.pentas.ui.kit.Slider
import org.yasvara.pentas.ui.kit.Tag
import org.yasvara.pentas.ui.kit.Toggle
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The running slot's filters, band by band. A card shows what it changes; tap opens its controls.
 * Long-press and drag reorders within the band, a swipe removes.
 */
@Composable
internal fun KelirStackEditor(
    stack: KelirStack,
    density: KelirDensity,
    curves: List<List<Int>>?,
    depth: SceneDepth?,
    onPreview: (KelirStack) -> Unit,
    onCommit: (KelirStack) -> Unit,
) {
    var expanded by remember { mutableStateOf<Int?>(null) }
    if (expanded != null && expanded !in stack.nodes.indices) expanded = null
    for (band in Band.entries) {
        val indices = stack.nodes.indices.filter { stack.nodes[it].info.band == band }
        if (indices.isEmpty()) continue
        BandHeader(band, indices.size, if (band == Band.SCENE) depth?.text else null)
        BandList(
            stack, indices, density, curves, expanded, depth,
            onExpand = { expanded = it },
            onPreview = onPreview, onCommit = onCommit,
        )
    }
}

@Composable
private fun BandHeader(band: Band, count: Int, note: String? = null) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, top = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(band.title(), style = label(11.5.sp, 600), color = cs.primary)
        Text(
            band.cap()?.let { "$count/$it" } ?: "$count",
            Modifier.clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 6.dp, vertical = 1.dp),
            style = label(9.5.sp, 600), color = cs.onSurfaceVariant,
        )
        if (note != null) {
            Text(note, Modifier.weight(1f), style = label(10.sp, 500), color = cs.outline, maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@Composable
private fun BandList(
    stack: KelirStack,
    indices: List<Int>,
    density: KelirDensity,
    curves: List<List<Int>>?,
    expanded: Int?,
    depth: SceneDepth?,
    onExpand: (Int?) -> Unit,
    onPreview: (KelirStack) -> Unit,
    onCommit: (KelirStack) -> Unit,
) {
    val latest by rememberUpdatedState(stack)
    val open by rememberUpdatedState(expanded)
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Int, Int>() }
    val gap = with(LocalDensity.current) { 2.dp.toPx() }

    // Compose also cancels a card's detector when the card leaves, drag or not: only a real drag
    // commits, or a removed card's stale stack would come back.
    fun drop() {
        if (dragging == null) return
        dragging = null
        dragY = 0f
        onCommit(latest)
    }

    fun swap(from: Int, to: Int) {
        onPreview(latest.move(from, to))
        if (open == from) onExpand(to) else if (open == to) onExpand(from)
        dragging = to
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        indices.forEachIndexed { pos, i ->
            key(i) {
                val node = stack.nodes[i]
                val lifted = dragging == i
                NodeCard(
                    node = node,
                    index = i,
                    shape = Kit.connected(pos, indices.size, density.card, 5.dp),
                    density = density,
                    curve = curves?.getOrNull(i)?.takeIf { node.info.band == Band.COLOUR && it.any { y -> y != 0 } },
                    depthAware = node.kind == KelirKinds.SHARPEN && depth == SceneDepth.READY,
                    expanded = expanded == i,
                    canMoveUp = pos > 0,
                    canMoveDown = pos < indices.size - 1,
                    modifier = Modifier
                        .onSizeChanged { heights[i] = it.height }
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (lifted) dragY else 0f
                            if (lifted) { scaleX = 1.02f; scaleY = 1.02f }
                        },
                    reorder = Modifier.pointerInput(i) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = i; dragY = 0f },
                            onDragEnd = { drop() },
                            onDragCancel = { drop() },
                        ) { change, amount ->
                            change.consume()
                            val at = dragging ?: return@detectDragGesturesAfterLongPress
                            dragY += amount.y
                            val next = indices.getOrNull(indices.indexOf(at) + 1)
                            val prev = indices.getOrNull(indices.indexOf(at) - 1)
                            if (next != null && dragY > (heights[next] ?: 0) / 2f + gap) {
                                dragY -= (heights[next] ?: 0) + gap
                                swap(at, next)
                            } else if (prev != null && -dragY > (heights[prev] ?: 0) / 2f + gap) {
                                dragY += (heights[prev] ?: 0) + gap
                                swap(at, prev)
                            }
                        }
                    },
                    swipeEnabled = { dragging == null },
                    onExpand = { onExpand(if (expanded == i) null else i) },
                    onPreview = { onPreview(latest.replace(i, it)) },
                    onCommit = { onCommit(latest.replace(i, it)) },
                    onMove = { to -> onCommit(latest.move(i, to)); onExpand(to) },
                    onRemove = { onExpand(null); onCommit(latest.remove(i)) },
                )
            }
        }
    }
}

@Composable
private fun NodeCard(
    node: KelirNode,
    index: Int,
    shape: androidx.compose.ui.graphics.Shape,
    density: KelirDensity,
    curve: List<Int>?,
    depthAware: Boolean,
    expanded: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    modifier: Modifier,
    reorder: Modifier,
    swipeEnabled: () -> Boolean,
    onExpand: () -> Unit,
    onPreview: (KelirNode) -> Unit,
    onCommit: (KelirNode) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val swipe = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }
    val kind = node.info
    Column(
        modifier.fillMaxWidth()
            .onSizeChanged { width = it.width }
            .graphicsLayer {
                translationX = swipe.value
                alpha = 1f - (abs(swipe.value) / width).coerceIn(0f, 0.6f)
            }
            .clip(shape).background(cs.surfaceContainer)
            .animatedSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(Kit.Gap),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .then(reorder)
                .pointerInput(index) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (abs(swipe.value) > width * SWIPE_REMOVE) {
                                    swipe.animateTo(if (swipe.value > 0) width.toFloat() else -width.toFloat(), Kit.fast())
                                    onRemove()
                                    swipe.snapTo(0f)
                                } else {
                                    swipe.animateTo(0f, Kit.fast())
                                }
                            }
                        },
                        onDragCancel = { scope.launch { swipe.animateTo(0f, Kit.fast()) } },
                    ) { change, dx ->
                        if (!swipeEnabled()) return@detectHorizontalDragGestures
                        change.consume()
                        scope.launch { swipe.snapTo(swipe.value + dx) }
                    }
                }
                .clickable(onClick = onExpand),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val dim = Modifier.alpha(if (node.enabled) 1f else 0.5f)
            Box(dim) {
                when {
                    curve != null -> LumaCurve(curve, size = density.glyph, description = "${kind.name} luma curve")
                    hasMiniScreen(node.kind) -> MiniScreen(node, density.glyph * 0.72f, "${kind.name} on the screen, to scale")
                    hasDepthArt(node.kind) -> DepthArt(node, density.glyph * 0.72f, "${kind.name}, drawn from its settings")
                    else -> Box(
                        Modifier.size(density.glyph).clip(CircleShape).background(cs.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) { KindGlyph(node.kind, size = density.glyph * 0.57f) }
                }
            }
            Row(dim.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(kind.name, Modifier.weight(1f, fill = false), style = label(density.title.sp, 540), color = cs.onSurface, maxLines = 1)
                if (depthAware) Tag("Depth-aware")
            }
            Text("${(node.intensity * 100).roundToInt()}%", dim, style = num(12.sp, 500), color = cs.onSurfaceVariant)
            Toggle(node.enabled, { onCommit(node.copy(enabled = it)) })
        }
        if (expanded) {
            Controls(node, onPreview, onCommit)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canMoveUp) Chip("Move up") { onMove(index - 1) }
                if (canMoveDown) Chip("Move down") { onMove(index + 1) }
                Chip("Remove", warn = true) { onRemove() }
            }
        } else {
            Changed(node)
        }
        if (depthAware) {
            Text("Holds back at object edges, so outlines don't get halos.", style = label(11.sp), color = cs.onSurfaceVariant)
        }
    }
}

private const val SWIPE_REMOVE = 0.4f

/** The parameters moved off their defaults, read-only. */
@Composable
private fun Changed(node: KelirNode) {
    val cs = MaterialTheme.colorScheme
    node.info.params.forEachIndexed { p, param ->
        val v = node.values[p]
        if (v == param.default) return@forEachIndexed
        Row(Modifier.fillMaxWidth().alpha(if (node.enabled) 1f else 0.5f)) {
            Text(param.name, Modifier.weight(1f), style = label(11.sp), color = cs.onSurfaceVariant)
            Text(param.format(v), style = num(11.sp, 500), color = cs.onSurface)
        }
    }
}

@Composable
private fun Controls(node: KelirNode, onPreview: (KelirNode) -> Unit, onCommit: (KelirNode) -> Unit) {
    SettingRow("Intensity", value = "${(node.intensity * 100).roundToInt()}%")
    Slider(
        node.intensity,
        { onPreview(node.copy(intensity = it)) },
        onCommit = { onCommit(node.copy(intensity = it)) },
    )
    node.info.params.forEachIndexed { p, param ->
        // A parameter that only matters once another moves, e.g. tint colour without tint.
        val waits = param.needs >= 0 && node.values[param.needs] == node.info.params[param.needs].default
        Column(Modifier.alpha(if (waits) 0.45f else 1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (param.discrete) {
                SettingRow(param.name)
                Segmented(
                    param.labels.mapIndexed { i, l -> l to i },
                    selected = node.values[p].roundToInt(),
                    onSelect = { onCommit(node.with(p, it.toFloat())) },
                )
            } else {
                SettingRow(param.name, value = param.format(node.values[p]))
                Slider(
                    node.values[p],
                    { onPreview(node.with(p, it)) },
                    range = param.lo..param.hi,
                    steps = if (param.integral) (param.hi - param.lo).roundToInt() - 1 else 0,
                    marker = param.default,
                    onCommit = { onCommit(node.with(p, it)) },
                )
            }
        }
    }
}

private val METRES = setOf("Radius", "Start", "Distance")

/** Counts rather than amounts: pixel size, colour levels, dot size. */
private val Param.integral: Boolean get() = !discrete && hi >= 16f && name !in METRES

private val DEGREES = setOf("Hue", "Angle", "Tint colour")

internal fun Param.format(v: Float): String = when {
    discrete -> labels.getOrElse(v.roundToInt()) { "" }
    name in DEGREES -> "${v.roundToInt()}°"
    name == "Aspect" -> "%.2f:1".format(v)
    name in METRES -> if (v < 10f) "%.2f m".format(v) else "${v.roundToInt()} m"
    integral -> "${v.roundToInt()}"
    lo < 0f -> "%+.2f".format(v)
    else -> "%.2f".format(v)
}
