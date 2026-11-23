/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.ui.kit

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs

/**
 * Shared floating drag-and-drop state for linear reorderable rows (QS toggles, bottom tools,
 * edit chips) and vertical rails (sidebar app shortcuts).
 *
 * The dragged item elevates with scale, shadow, and high zIndex so it visibly floats over
 * siblings while dragging, swaps live with haptic ticks, and disambiguates a stationary
 * long-press from a drag so existing long-press actions stay intact.
 */
@Stable
class LinearReorderState(
    val horizontal: Boolean,
    val gapPx: Float,
    val dragThresholdPx: Float,
) {
    var draggingId by mutableStateOf<String?>(null)
        internal set
    var lifted by mutableStateOf(false)
        internal set
    var dragX by mutableFloatStateOf(0f)
        internal set
    var dragY by mutableFloatStateOf(0f)
        internal set
    internal val spans = mutableStateMapOf<String, Int>()

    val isDragging: Boolean get() = draggingId != null && lifted

    internal fun reset() {
        draggingId = null
        lifted = false
        dragX = 0f
        dragY = 0f
    }
}

@Composable
fun rememberLinearReorderState(
    horizontal: Boolean,
    gap: Dp = 4.dp,
    dragThreshold: Dp = 8.dp,
): LinearReorderState {
    val density = LocalDensity.current
    val gapPx = with(density) { gap.toPx() }
    val thresholdPx = with(density) { dragThreshold.toPx() }
    return remember(horizontal, gapPx, thresholdPx) {
        LinearReorderState(horizontal, gapPx, thresholdPx)
    }
}

/**
 * Attaches floating long-press drag-and-drop to an item identified by [id] inside a row or column.
 *
 * - Short tap is left untouched for a sibling `clickable` modifier.
 * - Long-press released without moving past [LinearReorderState.dragThresholdPx] invokes [onLongTap].
 * - Long-press + drag floats the item above siblings, swaps via [onSwap] as it crosses neighbor
 *   midpoints, and calls [onCommit] on drop.
 * - Optional [onCrossAxisDrop]: if provided and the user drags past [crossAxisThreshold] on the
 *   perpendicular axis (e.g. pulling a rail shortcut off the rail), fires instead of [onCommit].
 */
@Composable
fun Modifier.floatingReorderItem(
    state: LinearReorderState,
    id: String,
    ids: List<String>,
    shape: Shape,
    onSwap: (fromIndex: Int, toIndex: Int) -> Unit,
    onCommit: () -> Unit,
    onLongTap: (() -> Unit)? = null,
    crossAxisThreshold: Dp = 40.dp,
    onCrossAxisDrop: (() -> Unit)? = null,
    onEdgeHover: ((direction: Int) -> Unit)? = null,
    floatScale: Float = 1.08f,
    floatElevation: Dp = 12.dp,
): Modifier {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val crossPx = with(density) { crossAxisThreshold.toPx() }
    val latestIds by rememberUpdatedState(ids)
    val latestSwap by rememberUpdatedState(onSwap)
    val latestCommit by rememberUpdatedState(onCommit)
    val latestLongTap by rememberUpdatedState(onLongTap)
    val latestCrossDrop by rememberUpdatedState(onCrossAxisDrop)
    val latestEdge by rememberUpdatedState(onEdgeHover)

    val isActive = state.draggingId == id && state.lifted
    val scale by animateFloatAsState(if (isActive) floatScale else 1f, Kit.fast(), label = "dragScale")
    val elevation by animateDpAsState(if (isActive) floatElevation else 0.dp, Kit.fast(), label = "dragElev")

    return this
        .onSizeChanged { size ->
            state.spans[id] = if (state.horizontal) size.width else size.height
        }
        .zIndex(if (isActive) 10f else 0f)
        .graphicsLayer {
            if (isActive) {
                translationX = state.dragX
                translationY = state.dragY
                scaleX = scale
                scaleY = scale
                shadowElevation = elevation.toPx()
                this.shape = shape
                clip = false
                alpha = 0.96f
            } else {
                scaleX = scale
                scaleY = scale
            }
        }
        .pointerInput(id, state) {
            var moved = false
            var totalCross = 0f
            var edgeTicks = 0
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    moved = false
                    totalCross = 0f
                    edgeTicks = 0
                    state.draggingId = id
                    state.lifted = false
                    state.dragX = 0f
                    state.dragY = 0f
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDragEnd = {
                    val wasLifted = state.lifted
                    val crossTriggered = wasLifted && latestCrossDrop != null && abs(totalCross) >= crossPx
                    state.reset()
                    when {
                        crossTriggered -> latestCrossDrop?.invoke()
                        wasLifted -> latestCommit()
                        else -> latestLongTap?.invoke()
                    }
                },
                onDragCancel = {
                    val wasLifted = state.lifted
                    state.reset()
                    if (wasLifted) latestCommit()
                },
            ) { change, amount ->
                change.consume()
                if (state.draggingId != id) return@detectDragGesturesAfterLongPress
                state.dragX += amount.x
                state.dragY += amount.y
                totalCross = if (state.horizontal) state.dragY else state.dragX

                if (!moved) {
                    val dist = abs(state.dragX) + abs(state.dragY)
                    if (dist >= state.dragThresholdPx) {
                        moved = true
                        state.lifted = true
                    } else {
                        return@detectDragGesturesAfterLongPress
                    }
                }

                val list = latestIds
                val index = list.indexOf(id)
                if (index < 0) return@detectDragGesturesAfterLongPress

                val primary = if (state.horizontal) state.dragX else state.dragY
                val nextId = list.getOrNull(index + 1)
                val prevId = list.getOrNull(index - 1)
                val nextSpan = nextId?.let { state.spans[it] } ?: (state.spans[id] ?: 0)
                val prevSpan = prevId?.let { state.spans[it] } ?: (state.spans[id] ?: 0)

                if (nextId != null && nextSpan > 0 && primary > nextSpan / 2f + state.gapPx) {
                    if (state.horizontal) state.dragX -= (nextSpan + state.gapPx) else state.dragY -= (nextSpan + state.gapPx)
                    latestSwap(index, index + 1)
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    edgeTicks = 0
                } else if (prevId != null && prevSpan > 0 && primary < -(prevSpan / 2f + state.gapPx)) {
                    if (state.horizontal) state.dragX += (prevSpan + state.gapPx) else state.dragY += (prevSpan + state.gapPx)
                    latestSwap(index, index - 1)
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    edgeTicks = 0
                } else if (latestEdge != null) {
                    val mySpan = (state.spans[id] ?: 60).toFloat()
                    if (nextId == null && primary > mySpan * 0.65f) {
                        if (++edgeTicks >= 12) {
                            edgeTicks = 0
                            latestEdge?.invoke(1)
                            if (state.horizontal) state.dragX = 0f else state.dragY = 0f
                        }
                    } else if (prevId == null && primary < -mySpan * 0.65f) {
                        if (++edgeTicks >= 12) {
                            edgeTicks = 0
                            latestEdge?.invoke(-1)
                            if (state.horizontal) state.dragX = 0f else state.dragY = 0f
                        }
                    } else {
                        edgeTicks = 0
                    }
                }
            }
        }
}

/** Moves the element at [from] to [to] in a new list, returning the receiver if indices are invalid. */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
