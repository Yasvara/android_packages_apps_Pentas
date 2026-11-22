/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.monitor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yasvara.pentas.R
import org.yasvara.pentas.cache.AppIconCache
import org.yasvara.pentas.telemetry.TelemetrySnapshot
import org.yasvara.pentas.ui.kit.Glyph
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num
import org.yasvara.pentas.utils.rememberAppIconCache

internal const val HOT_SOC = 70f
internal const val HOT_BATTERY = 42f

@Composable
internal fun WidgetFrame(widget: Widget, overlay: WidgetOverlay, onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val s = overlay.settings
    val editing by overlay.editing
    val snap by overlay.bus.snapshot.collectAsState()
    val shape = if (widget == Widget.MINI && s.miniAnchor != 3) {
        RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)
    } else {
        RoundedCornerShape(if (widget == Widget.TEMP || widget == Widget.MINI) 12.dp else 14.dp)
    }
    Box(
        Modifier
            .then(if (editing) Modifier.border(1.5.dp, cs.primary.copy(alpha = 0.6f), shape) else Modifier)
            .clip(shape).background(cs.surface.copy(alpha = s.opacity))
            .pointerInput(editing) {
                if (editing) detectDragGestures(onDragEnd = onDragEnd) { c, d -> c.consume(); onDrag(d.x, d.y) }
            }
    ) {
        when (widget) {
            Widget.CLASSIC -> Classic(snap, overlay)
            Widget.MINI -> Mini(snap, overlay)
            Widget.PROCESSES -> Processes(overlay)
            Widget.TEMP -> Temp(snap, s)
            Widget.NETWORK -> NetworkCardMini(snap)
        }
    }
}

@Composable
private fun hot(c: Float, limit: Float) =
    if (c >= limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

@Composable
private fun KV(k: String, v: @Composable () -> Unit) {
    Row(Modifier.width(118.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(k, style = label(10.5.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        v()
    }
}

@Composable
private fun Classic(s: TelemetrySnapshot, o: WidgetOverlay) {
    val cs = MaterialTheme.colorScheme
    val set = o.settings
    val w = Widget.CLASSIC
    val fps by o.fps.realFpsHistory.collectAsState()
    val v = label(10.5.sp, 550)
    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (set.shows(w, Field.FPS)) Row(verticalAlignment = Alignment.Bottom) {
            Text("${fps.lastOrNull()?.toInt() ?: 0}", style = num(18.sp, 620), color = cs.primary)
            Text(" fps", style = label(10.5.sp), color = cs.onSurfaceVariant)
        }
        val temp = set.shows(w, Field.TEMP)
        if (set.shows(w, Field.CPU)) KV("CPU") {
            Text(buildAnnotatedString {
                append("${s.cpuUsageTotal}% · ${"%.1f".format(s.cpuFreqMaxKhz / 1e6f)}G")
                if (temp) { append(" · "); withStyle(v.toSpanStyle().copy(color = hot(s.cpuTempC, HOT_SOC))) { append("%.0f°".format(s.cpuTempC)) } }
            }, style = v, color = cs.onSurface)
        }
        if (set.shows(w, Field.GPU)) KV("GPU") {
            Text(buildAnnotatedString {
                append("${s.gpuUsage}% · ${s.gpuFreqMhz}M")
                if (temp) { append(" · "); withStyle(v.toSpanStyle().copy(color = hot(s.gpuTempC, HOT_SOC))) { append("%.0f°".format(s.gpuTempC)) } }
            }, style = v, color = cs.onSurface)
        }
        if (set.shows(w, Field.RAM)) KV("RAM") {
            Text("%.1f / %.0f G".format(s.ramUsedGb, s.ramTotalGb), style = v, color = cs.onSurface)
        }
        if (set.shows(w, Field.NET)) KV("NET") {
            Text("↓ ${speed(s.net.rxKbps)} ↑ ${speed(s.net.txKbps)}", style = v, color = cs.onSurface)
        }
        if (set.shows(w, Field.POWER)) KV("PWR") {
            Text("%.1fW".format(s.batteryPowerW), style = v, color = cs.onSurface)
        }
    }
}

private fun speed(kbps: Float) = if (kbps >= 1024) "%.1fM".format(kbps / 1024) else "${kbps.toInt()}K"

@Composable
private fun Mini(s: TelemetrySnapshot, o: WidgetOverlay) {
    val cs = MaterialTheme.colorScheme
    val set = o.settings
    val w = Widget.MINI
    val fps by o.fps.realFpsHistory.collectAsState()
    val v = num(10.sp, 600)
    @Composable fun Cell(k: String, value: String, col: Color = cs.onSurface) {
        Text(buildAnnotatedString {
            if (k.isNotEmpty()) withStyle(label(9.sp, 600).toSpanStyle().copy(color = cs.onSurfaceVariant)) { append(k) }
            withStyle(v.toSpanStyle().copy(color = col)) { append(value) }
        }, style = v)
    }
    Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        if (set.shows(w, Field.FPS)) Cell("", "${fps.lastOrNull()?.toInt() ?: 0}", cs.primary)
        if (set.shows(w, Field.CPU)) Cell("C", "${s.cpuUsageTotal}%")
        if (set.shows(w, Field.GPU)) Cell("G", "${s.gpuUsage}%")
        if (set.shows(w, Field.TEMP)) Cell("", "%.0f°".format(s.cpuTempC), hot(s.cpuTempC, HOT_SOC))
        if (set.shows(w, Field.POWER)) Cell("", "%.1fW".format(s.batteryPowerW), cs.primary)
        if (set.shows(w, Field.RAM)) Cell("R", "%.1fG".format(s.ramUsedGb))
        if (set.shows(w, Field.NET)) Cell("↓", speed(s.net.rxKbps), cs.tertiary)
        if (set.shows(w, Field.BATTERY)) Cell("", "${s.batteryCapacity}%", cs.tertiary)
    }
}

@Composable
private fun Temp(s: TelemetrySnapshot, set: WidgetSettings) {
    val cs = MaterialTheme.colorScheme
    val w = Widget.TEMP
    @Composable fun Cell(k: String, c: Float, limit: Float) {
        Text(buildAnnotatedString {
            withStyle(label(10.sp, 600).toSpanStyle().copy(color = cs.onSurfaceVariant)) { append("$k ") }
            withStyle(num(11.sp, 600).toSpanStyle().copy(color = hot(c, limit))) { append("%.0f°".format(c)) }
        })
    }
    Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (set.shows(w, Field.CPU)) Cell("CPU", s.cpuTempC, HOT_SOC)
        if (set.shows(w, Field.GPU)) Cell("GPU", s.gpuTempC, HOT_SOC)
        if (set.shows(w, Field.BATTERY)) Cell("BAT", s.batteryTempC, HOT_BATTERY)
    }
}

@Composable
private fun NetworkCardMini(s: TelemetrySnapshot) {
    val cs = MaterialTheme.colorScheme
    val n = s.net
    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    n.hasWifi && n.hasCell -> "Wi-Fi+Cell"
                    n.hasWifi -> "Wi-Fi"
                    n.hasCell -> if (n.cellGen >= 5) "5G" else "4G"
                    else -> "Net"
                },
                style = label(10.sp, 600),
                color = cs.primary,
            )
            Text(if (n.rttMs > 0) "${n.rttMs}ms" else "—", style = num(10.sp, 600), color = cs.onSurfaceVariant)
        }
        Text("↓ ${speed(n.rxKbps)}  ↑ ${speed(n.txKbps)}", style = num(10.5.sp, 600), color = cs.onSurface)
    }
}

@Composable
private fun Processes(o: WidgetOverlay) {
    val procs by o.processes.collectAsState()
    val icons = rememberAppIconCache()
    val system = o.settings.shows(Widget.PROCESSES, Field.SYSTEM)
    val threads = o.settings.shows(Widget.PROCESSES, Field.THREADS)
    val rows = procs.filter { threads || system || it.kind == 0 }.take(6)
    Column(Modifier.width(200.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        rows.forEach { ProcessRow(it, icons) }
        if (rows.isEmpty()) Text("—", style = label(11.sp), color = Color.Unspecified)
    }
}

@Composable
internal fun ProcessRow(p: ProcessUsage, icons: AppIconCache, size: Dp = 16.dp, text: TextUnit = 11.sp) =
    AppRow(p.name, "%.1f%%".format(p.usagePercent), icons, p.usagePercent >= 50f, size, text, kind = p.kind)

/** Shortens long Android HAL / kernel worker names so rows stay strictly 1-line. */
private fun shortenName(raw: String): String = when {
    raw.startsWith("vendor.") || raw.startsWith("android.hardware.") ->
        raw.substringAfterLast('.').ifEmpty { raw }
    raw.startsWith("kworker/") && '-' in raw ->
        "kworker/" + raw.substringAfter('-')
    else -> raw
}

/** Icon (real app icon OR monochrome Material You kernel/HAL/thread icon), label & value. */
@Composable
internal fun AppRow(
    name: String,
    value: String,
    icons: AppIconCache,
    warn: Boolean = false,
    size: Dp = 16.dp,
    text: TextUnit = 11.sp,
    kind: Int = 0,
) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val icon = if (kind == 0) icons.icon(name, ICON_PX) else null
        if (icon != null) {
            Image(icon, null, Modifier.size(size).clip(RoundedCornerShape(4.dp)))
        } else {
            val isKernel = name.startsWith("kworker") || name.startsWith("irq/") || name.startsWith("kswapd")
            val glyphRes = when {
                kind == 2 -> R.drawable.sym_bolt
                isKernel -> R.drawable.sym_memory
                else -> R.drawable.sym_developer_board
            }
            val tint = if (kind == 2 || isKernel) cs.primary else cs.onSurfaceVariant
            Box(
                Modifier.size(size).clip(RoundedCornerShape(5.dp)).background(cs.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(glyphRes, tint, size * 0.72f)
            }
        }
        val labelStr = if (kind == 0) {
            val l = icons.label(name)
            if (l == name) shortenName(name) else l
        } else shortenName(name)
        Text(
            labelStr,
            Modifier.weight(1f),
            style = label(text),
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(value, style = num(text, 600), color = if (warn) cs.error else cs.onSurfaceVariant)
    }
}

/** One bitmap size for every row icon: [AppIconCache] keeps the first size it renders. */
internal const val ICON_PX = 56
