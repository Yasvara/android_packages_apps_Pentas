/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.charging

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Step-curve preview: X = battery cell temperature (°C), Y = max charge speed (W).
 * Displays filled area gradient, tier transitions, emergency cutoff line, labeled axes,
 * and an optional live cell-temperature marker so users see where the phone sits now.
 */
@Composable
fun ChargingCurvePreview(
    tiers: List<ChargingTier>,
    suspendMc: Long,
    modifier: Modifier = Modifier,
    liveCellC: Float? = null,
) {
    val cs = MaterialTheme.colorScheme
    val primary = cs.primary
    val secondary = cs.tertiary
    val grid = cs.outlineVariant
    val danger = cs.error

    val peakUa = tiers.maxOfOrNull { it.maxUa } ?: 0L
    val peakW = ChargingProfiles.uaToWatt(peakUa)
    val peakA = ChargingProfiles.uaToAmp(peakUa)
    val suspC = ChargingProfiles.mcToC(suspendMc)
    val xMinC = 25f
    val xMaxC = (suspC + 3f).coerceAtLeast(52f)
    val yMaxW = peakW.coerceAtLeast(10f)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Peak %.0f W (%.1f A) · Cutoff %.0f°C".format(peakW, peakA, suspC),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                )
                if (liveCellC != null) {
                    Text(
                        "Now %.1f°C".format(liveCellC),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onTertiaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(cs.tertiaryContainer)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Y-axis labels
                Column(
                    Modifier.height(132.dp).width(34.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("%.0fW".format(yMaxW), fontSize = 10.sp, color = cs.onSurfaceVariant)
                    Text("%.0fW".format(yMaxW / 2f), fontSize = 10.sp, color = cs.onSurfaceVariant)
                    Text("0W", fontSize = 10.sp, color = cs.onSurfaceVariant)
                }
                Canvas(Modifier.weight(1f).height(132.dp)) {
                    val padL = 4.dp.toPx()
                    val padR = 6.dp.toPx()
                    val padT = 8.dp.toPx()
                    val padB = 6.dp.toPx()
                    val plotW = size.width - padL - padR
                    val plotH = size.height - padT - padB

                    fun tx(c: Float) = padL + ((c - xMinC) / (xMaxC - xMinC)).coerceIn(0f, 1f) * plotW
                    fun ty(w: Float) = padT + (1f - (w / yMaxW).coerceIn(0f, 1f)) * plotH

                    // Horizontal & vertical reference grid
                    for (i in 0..4) {
                        val y = padT + plotH * i / 4f
                        drawLine(grid.copy(alpha = 0.55f), Offset(padL, y), Offset(padL + plotW, y), 1f)
                    }
                    for (t in listOf(30f, 35f, 40f, 45f, 50f)) {
                        if (t in xMinC..xMaxC) {
                            val x = tx(t)
                            drawLine(grid.copy(alpha = 0.35f), Offset(x, padT), Offset(x, padT + plotH), 1f)
                        }
                    }

                    if (tiers.isEmpty()) return@Canvas

                    val sorted = tiers.sortedBy { it.trigMc }
                    val startW = ChargingProfiles.uaToWatt(sorted.first().maxUa).coerceIn(0f, yMaxW)

                    val strokePath = Path()
                    val fillPath = Path()
                    strokePath.moveTo(tx(xMinC), ty(startW))
                    fillPath.moveTo(tx(xMinC), padT + plotH)
                    fillPath.lineTo(tx(xMinC), ty(startW))

                    var prevW = startW
                    for (t in sorted) {
                        val trigC = ChargingProfiles.mcToC(t.trigMc)
                        val w = ChargingProfiles.uaToWatt(t.maxUa).coerceIn(0f, yMaxW)
                        if (trigC <= xMinC) {
                            prevW = w
                            strokePath.lineTo(tx(xMinC), ty(w))
                            fillPath.lineTo(tx(xMinC), ty(w))
                        } else if (trigC <= xMaxC) {
                            val x = tx(trigC)
                            strokePath.lineTo(x, ty(prevW))
                            strokePath.lineTo(x, ty(w))
                            fillPath.lineTo(x, ty(prevW))
                            fillPath.lineTo(x, ty(w))
                            prevW = w
                        }
                    }
                    val cutoffX = tx(suspC.coerceIn(xMinC, xMaxC))
                    strokePath.lineTo(cutoffX, ty(prevW))
                    strokePath.lineTo(cutoffX, padT + plotH)
                    strokePath.lineTo(tx(xMaxC), padT + plotH)

                    fillPath.lineTo(cutoffX, ty(prevW))
                    fillPath.lineTo(cutoffX, padT + plotH)
                    fillPath.close()

                    drawPath(
                        fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(primary.copy(alpha = 0.24f), Color.Transparent),
                            startY = padT,
                            endY = padT + plotH,
                        ),
                    )
                    drawPath(
                        strokePath,
                        color = primary,
                        style = Stroke(
                            width = 2.5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )

                    // Emergency cutoff vertical dashed line
                    drawLine(
                        color = danger,
                        start = Offset(cutoffX, padT),
                        end = Offset(cutoffX, padT + plotH),
                        strokeWidth = 1.8.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f),
                    )

                    // Live cell temperature marker on curve
                    if (liveCellC != null && liveCellC in xMinC..xMaxC) {
                        val liveW = if (liveCellC >= suspC) {
                            0f
                        } else {
                            val active = sorted.lastOrNull { ChargingProfiles.mcToC(it.trigMc) <= liveCellC }
                                ?: sorted.first()
                            ChargingProfiles.uaToWatt(active.maxUa).coerceIn(0f, yMaxW)
                        }
                        val lx = tx(liveCellC)
                        val ly = ty(liveW)
                        drawLine(
                            color = secondary.copy(alpha = 0.6f),
                            start = Offset(lx, padT),
                            end = Offset(lx, padT + plotH),
                            strokeWidth = 1.2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f),
                        )
                        drawCircle(secondary.copy(alpha = 0.25f), 8.dp.toPx(), Offset(lx, ly))
                        drawCircle(secondary, 4.dp.toPx(), Offset(lx, ly))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            // X-axis labels
            Row(
                Modifier.fillMaxWidth().padding(start = 34.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val mid1 = ((xMinC + xMaxC) * 0.33f).roundToInt()
                val mid2 = ((xMinC + xMaxC) * 0.67f).roundToInt()
                Text("${xMinC.roundToInt()}°C", fontSize = 10.sp, color = cs.onSurfaceVariant)
                Text("${mid1}°C", fontSize = 10.sp, color = cs.onSurfaceVariant)
                Text("${mid2}°C", fontSize = 10.sp, color = cs.onSurfaceVariant)
                Text("${xMaxC.roundToInt()}°C", fontSize = 10.sp, color = cs.onSurfaceVariant)
            }
        }
    }
}
