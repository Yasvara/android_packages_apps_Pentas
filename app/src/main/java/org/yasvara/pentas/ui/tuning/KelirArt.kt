/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.ui.tuning

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.yasvara.pentas.kelir.KelirKinds
import org.yasvara.pentas.kelir.KelirNode

/** A kind's symbol, drawn on a 24-unit grid. */
@Composable
fun KindGlyph(kind: Int, modifier: Modifier = Modifier, size: Dp = 16.dp, tint: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.size(size)) {
        scale(this.size.width / 24f, pivot = Offset.Zero) { glyph(kind, tint) }
    }
}

private fun DrawScope.glyph(kind: Int, c: Color) {
    val line = Stroke(2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun path(block: Path.() -> Unit) = Path().apply(block)
    fun roundRect(l: Float, t: Float, w: Float, h: Float, r: Float) =
        drawRoundRect(c, Offset(l, t), Size(w, h), CornerRadius(r), style = line)
    when (kind) {
        KelirKinds.BRIGHTNESS_CONTRAST -> {
            drawCircle(c, 8f, Offset(12f, 12f), style = line)
            drawArc(c, -90f, 180f, true, Offset(4f, 4f), Size(16f, 16f))
        }
        KelirKinds.COLOR -> for (o in listOf(Offset(9f, 10f), Offset(15f, 10f), Offset(12f, 15f))) {
            drawCircle(c, 5f, o, style = line)
        }
        KelirKinds.LEVELS -> drawPath(path {
            moveTo(4f, 18f); lineTo(8f, 18f); lineTo(11f, 6f); lineTo(15f, 6f); lineTo(18f, 18f); lineTo(20f, 18f)
        }, c, style = line)
        KelirKinds.BLACK_WHITE -> {
            roundRect(4f, 4f, 16f, 16f, 4f)
            drawPath(path { moveTo(4f, 4f); lineTo(20f, 4f); lineTo(4f, 20f); close() }, c)
        }
        KelirKinds.SEPIA -> {
            roundRect(4f, 4f, 16f, 16f, 4f)
            drawPath(path { moveTo(8f, 15f); quadraticTo(12f, 8f, 16f, 15f) }, c, style = line)
        }
        KelirKinds.NIGHT_MODE -> {
            val moon = Path().apply {
                op(
                    Path().apply { addOval(Rect(Offset(12f, 12f), 8f)) },
                    Path().apply { addOval(Rect(Offset(17f, 7f), 7f)) },
                    PathOperation.Difference,
                )
            }
            drawPath(moon, c)
        }
        KelirKinds.COLORBLIND -> {
            drawPath(path {
                moveTo(2f, 12f); cubicTo(5f, 7f, 8f, 5f, 12f, 5f); cubicTo(16f, 5f, 19f, 7f, 22f, 12f)
                cubicTo(19f, 17f, 16f, 19f, 12f, 19f); cubicTo(8f, 19f, 5f, 17f, 2f, 12f); close()
            }, c, style = line)
            drawCircle(c, 3f, Offset(12f, 12f), style = line)
        }
        KelirKinds.SHARPEN -> drawPath(path { moveTo(12f, 3f); lineTo(21f, 19f); lineTo(3f, 19f); close() }, c, style = line)
        KelirKinds.DETAILS -> {
            drawCircle(c, 8f, Offset(12f, 12f), style = line)
            drawCircle(c, 3f, Offset(12f, 12f), style = line)
        }
        KelirKinds.TILT_SHIFT -> {
            drawLine(c, Offset(3f, 8f), Offset(21f, 8f), 2.2f, StrokeCap.Round)
            drawLine(c, Offset(3f, 16f), Offset(21f, 16f), 2.2f, StrokeCap.Round)
        }
        KelirKinds.PAINTERLY -> drawPath(path {
            moveTo(4f, 20f); cubicTo(8f, 19f, 10f, 16f, 11f, 12f); lineTo(16f, 4f); lineTo(20f, 7f); lineTo(14f, 14f)
            cubicTo(12f, 17f, 9f, 19f, 4f, 20f); close()
        }, c, style = line)
        KelirKinds.WATERCOLOR -> drawPath(path {
            moveTo(12f, 3f); cubicTo(16f, 8f, 18f, 11f, 18f, 14f); cubicTo(18f, 17.3f, 15.3f, 20f, 12f, 20f)
            cubicTo(8.7f, 20f, 6f, 17.3f, 6f, 14f); cubicTo(6f, 11f, 8f, 8f, 12f, 3f); close()
        }, c, style = line)
        KelirKinds.VIGNETTE -> roundRect(3f, 6f, 18f, 12f, 6f)
        KelirKinds.FILM_GRAIN -> for ((x, y, r) in listOf(
            Triple(6f, 7f, 1.6f), Triple(14f, 5f, 1.4f), Triple(18f, 12f, 1.6f),
            Triple(9f, 14f, 1.4f), Triple(15f, 18f, 1.6f), Triple(5f, 19f, 1.2f),
        )) drawCircle(c, r, Offset(x, y))
        KelirKinds.LETTERBOX -> {
            roundRect(3f, 4f, 18f, 16f, 3f)
            drawLine(c, Offset(3f, 8f), Offset(21f, 8f), 2.2f)
            drawLine(c, Offset(3f, 16f), Offset(21f, 16f), 2.2f)
        }
        KelirKinds.OLD_FILM -> {
            roundRect(3f, 5f, 18f, 14f, 3f)
            drawLine(c, Offset(9f, 5f), Offset(9f, 19f), 2.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 2f)))
        }
        KelirKinds.RETRO -> for ((x, y, a) in listOf(Triple(4f, 4f, 1f), Triple(14f, 4f, .5f), Triple(4f, 14f, .5f), Triple(14f, 14f, 1f))) {
            drawRoundRect(c.copy(alpha = a), Offset(x, y), Size(6f, 6f), CornerRadius(1f))
        }
        KelirKinds.HALFTONE -> for ((x, y, r) in listOf(
            Triple(6f, 6f, 2.5f), Triple(14f, 6f, 2f), Triple(6f, 14f, 2f), Triple(14f, 14f, 1.5f), Triple(20f, 20f, 1f),
        )) drawCircle(c, r, Offset(x, y))
        KelirKinds.AMBIENT_OCCLUSION -> {
            drawPath(path { moveTo(19f, 4f); lineTo(19f, 19f); lineTo(4f, 19f) }, c, style = line)
            drawPath(path { moveTo(15f, 4f); lineTo(15f, 15f); lineTo(4f, 15f) }, c,
                style = Stroke(2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 2f))))
        }
        KelirKinds.DEPTH_FOG -> {
            drawLine(c, Offset(3f, 9f), Offset(15f, 9f), 2.2f, StrokeCap.Round)
            drawLine(c, Offset(3f, 14f), Offset(21f, 14f), 2.2f, StrokeCap.Round)
            drawLine(c, Offset(9f, 19f), Offset(21f, 19f), 2.2f, StrokeCap.Round)
        }
        KelirKinds.DEPTH_OF_FIELD -> {
            drawCircle(c, 3f, Offset(12f, 12f), style = line)
            drawCircle(c, 8f, Offset(12f, 12f), style = Stroke(2.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5f, 2.5f))))
        }
        KelirKinds.GLOBAL_ILLUMINATION -> {
            // Light falls on the floor and bounces up onto the wall.
            drawPath(path { moveTo(4f, 4f); lineTo(4f, 20f); lineTo(20f, 20f) }, c, style = line)
            drawLine(c, Offset(19f, 4f), Offset(12f, 16f), 2.2f, StrokeCap.Round)
            drawLine(c, Offset(12f, 16f), Offset(7f, 10f), 2.2f, StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 2f)))
        }
        KelirKinds.TONEMAP -> {
            // A filmic curve: a toe, a straight middle, a shoulder.
            drawPath(path { moveTo(4f, 20f); cubicTo(9f, 20f, 9f, 14f, 12f, 11f); cubicTo(15f, 8f, 16f, 4f, 20f, 4f) }, c, style = line)
        }
        KelirKinds.SKY -> {
            drawArc(c, 180f, 180f, false, Offset(4f, 8f), Size(16f, 16f), style = line)
            drawLine(c, Offset(3f, 16f), Offset(21f, 16f), 2.2f, StrokeCap.Round)
        }
        KelirKinds.SUN_SHAFTS -> {
            drawCircle(c, 3f, Offset(7f, 7f), style = line)
            for (end in listOf(Offset(20f, 12f), Offset(16f, 19f), Offset(10f, 21f))) {
                drawLine(c, Offset(9f, 9f), end, 2.2f, StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 2f)))
            }
        }
        KelirKinds.OUTLINE -> {
            drawCircle(c, 7f, Offset(12f, 12f), style = Stroke(3.2f))
        }
        KelirKinds.EDGE_SMOOTHING -> {
            drawPath(path { moveTo(4f, 20f); lineTo(4f, 16f); lineTo(8f, 16f); lineTo(8f, 12f); lineTo(12f, 12f) }, c, style = line)
            drawLine(c, Offset(12f, 12f), Offset(20f, 4f), 2.2f, StrokeCap.Round)
        }
        KelirKinds.RT_SHADOWS, KelirKinds.RT_REFLECTIONS, KelirKinds.PATH_TRACED_LIGHT -> {
            // A ray leaving the eye, hitting a surface and going on.
            drawCircle(c, 2f, Offset(5f, 6f))
            drawLine(c, Offset(5f, 6f), Offset(13f, 18f), 2.2f, StrokeCap.Round)
            drawLine(c, Offset(3f, 19f), Offset(21f, 19f), 2.2f, StrokeCap.Round)
            when (kind) {
                KelirKinds.RT_SHADOWS -> drawLine(c, Offset(13f, 18f), Offset(19f, 8f), 2.2f, StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 2f)))
                KelirKinds.RT_REFLECTIONS -> drawLine(c, Offset(13f, 18f), Offset(21f, 6f), 2.2f, StrokeCap.Round)
                else -> for (end in listOf(Offset(19f, 9f), Offset(9f, 11f))) {
                    drawLine(c, Offset(13f, 18f), end, 2.2f, StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 2f)))
                }
            }
        }
    }
}

fun hasDepthArt(kind: Int) =
    kind == KelirKinds.AMBIENT_OCCLUSION || kind == KelirKinds.DEPTH_FOG ||
        kind == KelirKinds.DEPTH_OF_FIELD || kind == KelirKinds.GLOBAL_ILLUMINATION

/** The fog's colour from its hue (degrees), saturation and brightness, as the engine bakes it. */
fun fogColour(hue: Float, saturation: Float, brightness: Float): Color =
    Color.hsv(((hue % 360f) + 360f) % 360f, saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f))

/**
 * A 3D filter drawn from its own parameters: occlusion's reach from a corner, the fog thickening
 * along distance from where it starts, and blur against distance around the focus.
 */
@Composable
fun DepthArt(node: KelirNode, height: Dp = 20.dp, description: String) {
    val cs = MaterialTheme.colorScheme
    val ink = cs.primary
    val ground = cs.surfaceContainerHighest
    val v = node.values
    val strength = if (node.enabled) node.intensity else 0f
    Canvas(
        Modifier.size(height * 2, height).clip(RoundedCornerShape(4.dp)).background(ground)
            .semantics { contentDescription = description },
    ) {
        val w = size.width
        val h = size.height
        when (node.kind) {
            KelirKinds.AMBIENT_OCCLUSION -> {
                // A wall meets the floor at the corner; the shadow reaches the radius, of 3 m.
                val corner = Offset(w * 0.7f, h * 0.85f)
                val reach = (v[0] / 3f) * w * 0.6f
                val shade = Color.Black.copy(alpha = 0.85f * strength)
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, shade), corner.x - reach, corner.x),
                    Offset(corner.x - reach, h * 0.1f), Size(reach, corner.y - h * 0.1f))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, shade), corner.y - reach, corner.y),
                    Offset(w * 0.08f, corner.y - reach), Size(corner.x - w * 0.08f, reach))
                val edge = Path().apply { moveTo(corner.x, h * 0.08f); lineTo(corner.x, corner.y); lineTo(w * 0.05f, corner.y) }
                drawPath(edge, ink, style = Stroke(1.6.dp.toPx(), join = StrokeJoin.Round))
            }
            KelirKinds.DEPTH_FOG -> {
                // Distance on a square-root scale to 500 m; clear to Start, then 1 - e^(-k d).
                val colour = fogColour(v[2], v[3], v[4])
                val k = 0.02f * v[1] * v[1]
                val stops = (0..8).map { i ->
                    val t = i / 8f
                    val d = 500f * t * t
                    val a = (1f - kotlin.math.exp(-k * (d - v[0]).coerceAtLeast(0f))) * strength
                    t to colour.copy(alpha = a)
                }
                drawRect(Brush.horizontalGradient(*stops.toTypedArray()))
                val x = w * kotlin.math.sqrt(v[0] / 500f)
                drawLine(ink, Offset(x, h * 0.15f), Offset(x, h * 0.85f), 1.4.dp.toPx())
            }
            KelirKinds.DEPTH_OF_FIELD -> {
                // Blur against distance from a quarter to four times the focus, as the engine
                // spreads it: |1 - focus / z| over the range, times the blur.
                drawLine(cs.outlineVariant, Offset(w / 2f, h * 0.1f), Offset(w / 2f, h * 0.9f), 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 2f)))
                val p = Path()
                for (i in 0..24) {
                    val t = i / 24f * 4f - 2f
                    val spread = kotlin.math.abs(1f - Math.pow(2.0, -t.toDouble()).toFloat())
                    val blur = (spread / (0.05f + 0.95f * v[3])).coerceIn(0f, 1f) * v[2] * strength
                    val x = w * i / 24f
                    val y = h * 0.9f - h * 0.75f * blur
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                drawPath(p, ink, style = Stroke(1.6.dp.toPx(), join = StrokeJoin.Round))
            }
            KelirKinds.GLOBAL_ILLUMINATION -> {
                // A lit floor tints the wall above it, as high as the reach (of 20 m) and as
                // strongly as the bounce (of 3).
                val floor = h * 0.8f
                val wall = w * 0.25f
                val reach = (v[0] / 20f).coerceIn(0.1f, 1f) * floor
                val tint = ink.copy(alpha = (0.85f * v[1] / 3f * strength).coerceIn(0f, 0.85f))
                drawRect(ink.copy(alpha = 0.5f), Offset(wall, floor), Size(w - wall, h - floor))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, tint), floor - reach, floor),
                    Offset(wall, floor - reach), Size(w - wall, reach))
                drawLine(ink, Offset(wall, h * 0.08f), Offset(wall, floor), 1.6.dp.toPx())
            }
        }
    }
}

/** The display's shape, landscape: what the screen-space illustrations are drawn to. */
private const val SCREEN_ASPECT = 2772f / 1280f

fun hasMiniScreen(kind: Int) = kind == KelirKinds.VIGNETTE || kind == KelirKinds.LETTERBOX || kind == KelirKinds.TILT_SHIFT

/**
 * A screen filter drawn to scale from its own parameters: Vignette's falloff, Letterbox's bars,
 * Tilt-Shift's sharp region.
 */
@Composable
fun MiniScreen(node: KelirNode, height: Dp = 20.dp, description: String) {
    val cs = MaterialTheme.colorScheme
    val scene = cs.onSurfaceVariant
    val back = cs.surfaceContainerHighest
    val v = node.values
    val strength = if (node.enabled) node.intensity else 0f
    Canvas(
        Modifier.size(height * SCREEN_ASPECT, height).clip(RoundedCornerShape(4.dp))
            .semantics { contentDescription = description },
    ) {
        val w = size.width
        val h = size.height
        when (node.kind) {
            KelirKinds.VIGNETTE -> {
                drawRect(scene)
                // Size widens the clear centre; roundness goes from the screen's shape to a circle.
                val ry = h / 2f * (0.6f + 0.8f * v[0])
                val rx = ry * (1f + (SCREEN_ASPECT - 1f) * (1f - v[1]))
                scale(rx / ry, 1f, Offset(w / 2f, h / 2f)) {
                    drawCircle(
                        Brush.radialGradient(
                            0.55f to Color.Transparent, 1f to back.copy(alpha = strength),
                            center = Offset(w / 2f, h / 2f), radius = ry,
                        ),
                        radius = w, center = Offset(w / 2f, h / 2f),
                    )
                }
            }
            KelirKinds.LETTERBOX -> {
                drawRect(scene)
                val bar = back.copy(alpha = strength.coerceAtLeast(0.15f))
                val aspect = v[0]
                if (aspect >= SCREEN_ASPECT) {
                    val inner = w / aspect
                    val edge = (h - inner) / 2f
                    drawRect(bar, Offset.Zero, Size(w, edge))
                    drawRect(bar, Offset(0f, h - edge), Size(w, edge))
                } else {
                    val inner = h * aspect
                    val edge = (w - inner) / 2f
                    drawRect(bar, Offset.Zero, Size(edge, h))
                    drawRect(bar, Offset(w - edge, 0f), Size(edge, h))
                }
            }
            KelirKinds.TILT_SHIFT -> {
                // Outside the sharp region the scene is drawn washed out, as much as the blur.
                drawRect(back)
                drawRect(scene.copy(alpha = 1f - 0.75f * v[5] * strength))
                val sharp = scene
                val cy = h * v[1]
                if (v[0] < 0.5f) {
                    val band = h * v[2]
                    rotate(v[4], Offset(w / 2f, cy)) {
                        drawRect(sharp, Offset(-w, cy - band / 2f), Size(3 * w, band))
                    }
                } else {
                    drawCircle(sharp, h * v[2], Offset(w / 2f, cy))
                }
            }
        }
    }
}

/** A colour filter's own luma curve, from the bake the game reports. */
@Composable
fun LumaCurve(points: List<Int>, modifier: Modifier = Modifier, size: Dp = 28.dp, description: String) {
    val cs = MaterialTheme.colorScheme
    val ink = cs.primary
    val ref = cs.outlineVariant
    Canvas(
        modifier.size(size).clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainerHigh)
            .semantics { contentDescription = description },
    ) {
        val pad = this.size.width / 7f
        val span = this.size.width - 2 * pad
        drawLine(ref, Offset(pad, pad + span), Offset(pad + span, pad), 1f)
        val p = Path()
        points.forEachIndexed { i, y ->
            val x = pad + span * i / (points.size - 1)
            val py = pad + span * (1f - y / 255f)
            if (i == 0) p.moveTo(x, py) else p.lineTo(x, py)
        }
        drawPath(p, ink, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The eight reference colours after a slot's colour filters: 2 rows of 4. */
@Composable
fun Swatch(colours: List<Int>, cell: Dp = 7.dp, outline: Color = MaterialTheme.colorScheme.outline) {
    val shape = RoundedCornerShape(2.dp)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (row in colours.chunked(4)) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (argb in row) {
                    val c = Color(argb)
                    // Near-black would vanish on the dark surface.
                    val edge = if (c.red + c.green + c.blue < 0.12f) Modifier.border(1.dp, outline, shape) else Modifier
                    Box(Modifier.size(cell).clip(shape).background(c).then(edge))
                }
            }
        }
    }
}
