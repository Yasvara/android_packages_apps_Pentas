/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.ui.tuning

import android.graphics.Rect
import android.graphics.Region
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yasvara.pentas.kelir.KelirCodec
import org.yasvara.pentas.ui.kit.label

/**
 * Compare over the whole game: the game as drawn left of the divider, with Kelir right of it.
 * Kelir draws the divider in the frame. Only the divider and the action bar take touches; the
 * rest of the screen stays the game's, so a scene can be played while it is compared.
 */
@Composable
fun KelirCompare(pkg: String, onBack: () -> Unit, onDone: () -> Unit) {
    val kelir = rememberTuning().kelir()
    val slots = remember(pkg) { kelir.slots(pkg) }
    var split by remember(pkg) { mutableFloatStateOf(DEFAULT_SPLIT) }
    fun show(x: Float) {
        split = x.coerceIn(0f, 1f)
        kelir.preview(pkg, slots, KelirCodec.View(split = split))
    }
    DisposableEffect(pkg) {
        kelir.preview(pkg, slots, KelirCodec.View(split = split))
        onDispose { kelir.preview(pkg, slots, KelirCodec.View()) }
    }
    val touchable = rememberTouchableRegion()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val x = maxWidth * split
        Box(
            Modifier.offset(x = x - GRAB / 2).width(GRAB).fillMaxHeight()
                .then(touchable.part("divider"))
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        show(split + drag.x / width)
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
        Side("Original", Modifier.align(Alignment.TopStart))
        Side(slots.running?.let { "Kelir · slot ${slots.active + 1}" } ?: "Kelir off", Modifier.align(Alignment.TopEnd))
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).then(touchable.part("actions"))
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Action("Back", primary = false, onBack)
            Action("Done", primary = true, onDone)
        }
    }
}

/** The window takes touches only inside the parts given to it; the rest pass to the game. */
private class TouchableRegion(private val apply: (Region?) -> Unit) {
    private val parts = mutableMapOf<String, Rect>()

    fun part(name: String): Modifier = Modifier.onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        parts[name] = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
        apply(Region().apply { parts.values.forEach { union(it) } })
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
