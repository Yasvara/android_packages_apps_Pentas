/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yasvara.pentas.R
import org.yasvara.pentas.overlay.LocalOverlay
import org.yasvara.pentas.telemetry.NetSnapshot
import org.yasvara.pentas.ui.kit.Block
import org.yasvara.pentas.ui.kit.Glyph
import org.yasvara.pentas.ui.kit.Kit
import org.yasvara.pentas.ui.kit.ListItem
import org.yasvara.pentas.ui.kit.Section
import org.yasvara.pentas.ui.kit.SettingRow
import org.yasvara.pentas.ui.kit.Toggle
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num

/** Re-runs [onEvent] whenever one of [actions] is broadcast, while composed. */
@Composable
private fun Broadcasts(vararg actions: String, onEvent: (Intent) -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onEvent(i)
        }
        ctx.registerReceiver(r, IntentFilter().apply { actions.forEach(::addAction) }, Context.RECEIVER_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun BluetoothPage() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val adapter = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
    var on by remember { mutableStateOf(adapter?.isEnabled == true) }
    var devices by remember { mutableStateOf(emptyList<BluetoothDevice>()) }
    var tick by remember { mutableIntStateOf(0) }
    fun sync() {
        on = adapter?.isEnabled == true
        devices = runCatching { adapter?.bondedDevices?.toList() }.getOrNull().orEmpty()
            .sortedWith(compareByDescending<BluetoothDevice> { it.isConnected }.thenBy { it.alias ?: it.name })
        tick++
    }
    LaunchedEffect(Unit) { sync() }
    Broadcasts(
        BluetoothAdapter.ACTION_STATE_CHANGED, BluetoothDevice.ACTION_ACL_CONNECTED,
        BluetoothDevice.ACTION_ACL_DISCONNECTED, BluetoothDevice.ACTION_BOND_STATE_CHANGED,
        BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED,
    ) { sync() }
    SubPage("Bluetooth", trailing = {
        Toggle(on, { want -> runCatching { if (want) adapter?.enable() else adapter?.disable() } })
    }) {
        if (!on) return@SubPage
        devices.forEachIndexed { i, d ->
            val connected = d.isConnected
            val battery = d.batteryLevel
            ListItem(
                title = d.alias ?: d.name ?: d.address,
                shape = Kit.connected(i, devices.size),
                subtitle = if (connected) "Connected" else null,
                selected = connected,
                leading = { Glyph(R.drawable.sym_bluetooth, if (connected) cs.onSecondaryContainer else cs.onSurfaceVariant, 18.dp) },
                trailing = if (connected && battery in 0..100) ({
                    Text("$battery%", style = num(11.sp, 600), color = cs.onSecondaryContainer)
                }) else null,
                onClick = { runCatching { if (connected) d.disconnect() else d.connect() } },
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun WifiPage() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val wifi = remember { ctx.getSystemService(WifiManager::class.java) }
    var on by remember { mutableStateOf(wifi.isWifiEnabled) }
    var rev by remember { mutableIntStateOf(0) }
    Broadcasts(
        WifiManager.WIFI_STATE_CHANGED_ACTION, WifiManager.SCAN_RESULTS_AVAILABLE_ACTION,
        WifiManager.NETWORK_STATE_CHANGED_ACTION,
    ) { on = wifi.isWifiEnabled; rev++ }
    LaunchedEffect(on) { if (on) runCatching { wifi.startScan() } }
    SubPage("Wi-Fi", trailing = { Toggle(on, { wifi.setWifiEnabled(it) }) }) {
        if (!on) return@SubPage
        @Suppress("DEPRECATION") val info = remember(rev) { wifi.connectionInfo }
        val current = info?.ssid?.trim('"')?.takeIf { info.networkId != -1 }
        if (current != null) Block(padding = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Glyph(R.drawable.sym_wifi, cs.primary, 18.dp)
                Text(current, style = label(13.sp, 600), color = cs.onSurface)
            }
            SettingRow("Signal", value = "${info.rssi} dBm")
            SettingRow("Link", value = "${info.linkSpeed} Mbps · %.1f GHz".format(info.frequency / 1000f))
        }
        @Suppress("DEPRECATION") val saved = remember(rev) {
            runCatching { wifi.configuredNetworks }.getOrNull().orEmpty().associateBy { it.SSID.trim('"') }
        }
        val nearby = remember(rev) {
            wifi.scanResults.filter { it.SSID.isNotEmpty() && it.SSID != current }
                .groupBy { it.SSID }.map { (_, v) -> v.maxBy { it.level } }
                .sortedWith(compareByDescending<android.net.wifi.ScanResult> { it.SSID in saved }.thenByDescending { it.level })
        }
        if (nearby.isNotEmpty()) Section("Nearby")
        nearby.forEachIndexed { i, r ->
            val config = saved[r.SSID]
            ListItem(
                title = r.SSID,
                shape = Kit.connected(i, nearby.size),
                subtitle = if (config != null) "Saved" else null,
                leading = { Glyph(R.drawable.sym_wifi, if (config != null) cs.onSurface else cs.onSurfaceVariant, 18.dp) },
                trailing = { Text("${WifiManager.calculateSignalLevel(r.level, 5)}/4", style = num(11.sp, 500), color = cs.onSurfaceVariant) },
                onClick = config?.let { c -> { wifi.connect(c.networkId, null) } },
            )
        }
    }
}

@Composable
fun PingPage() {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val net by o.network.snapshot.collectAsState()
    val wifiColor = cs.primary
    val cellColor = Color(0xFF6EE7B7)
    val txColor = Color(0xFFFBBF24)

    val transportLabel = buildString {
        if (net.hasWifi && net.hasCell) append("Wi-Fi + Mobile")
        else if (net.hasWifi) {
            append(when (net.wifiStandard) { 7 -> "Wi-Fi 7"; 6 -> "Wi-Fi 6"; 5 -> "Wi-Fi 5"; else -> "Wi-Fi" })
            if (net.wifiFreqMhz > 0) append(" · %.1f GHz".format(net.wifiFreqMhz / 1000f))
        } else if (net.hasCell) {
            append(when (net.cellGen) { 6 -> "5G+"; 5 -> "5G NR"; 4 -> "4G LTE"; 3 -> "3G"; else -> "Mobile Data" })
        } else if (net.hasEth) append("Ethernet")
        else append("Offline")
        if (net.hasVpn) append(" · VPN")
    }

    SubPage("Network") {
        // Unified 1-Graph card combining Wi-Fi + Mobile Data + Upload + Passive Kernel RTT
        Block(padding = 10.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Glyph(
                    if (net.hasWifi) R.drawable.sym_wifi else R.drawable.sym_signal_cellular_alt,
                    if (net.hasWifi) wifiColor else cellColor,
                    16.dp,
                )
                Text(transportLabel, style = label(12.sp, 600), color = cs.onSurface)
                Spacer(Modifier.weight(1f))
                val rttText = if (net.rttMs > 0) "${net.rttMs} ms ±${net.jitterMs}" else "—"
                Text(rttText, style = num(11.5.sp, 600), color = if (net.rttMs >= 120) cs.error else cs.primary)
            }

            UnifiedNetGraph(net, wifiColor, cellColor, txColor, Modifier.fillMaxWidth().height(92.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LegendDot(wifiColor, "Wi-Fi")
                LegendDot(cellColor, "Mobile")
                LegendDot(txColor, "↑ Up")
                Spacer(Modifier.weight(1f))
                Text(
                    "W ${fmtDataKb(net.wifiSessionKb)} · M ${fmtDataKb(net.cellSessionKb)}",
                    style = num(10.sp, 500),
                    color = cs.onSurfaceVariant,
                )
            }
        }

        Block(padding = 10.dp) {
            SettingRow("Download", value = fmtSpeedBps(net.rxBps))
            SettingRow("Upload", value = fmtSpeedBps(net.txBps))
            if (net.hasWifi || net.wifiRxBps > 0L) {
                SettingRow(
                    "Wi-Fi",
                    value = buildString {
                        append("↓ ${fmtSpeedBps(net.wifiRxBps)}")
                        if (net.wifiRssiDbm < 0) append(" · ${net.wifiRssiDbm} dBm")
                        if (net.wifiLinkMbps > 0) append(" · ${net.wifiLinkMbps}M")
                    },
                )
            }
            if (net.hasCell || net.cellRxBps > 0L) {
                SettingRow(
                    "Mobile Data",
                    value = buildString {
                        append("↓ ${fmtSpeedBps(net.cellRxBps)}")
                        if (net.cellRsrpDbm < 0) append(" · ${net.cellRsrpDbm} dBm")
                    },
                )
            }
            SettingRow(
                "TCP RTT",
                value = if (net.rttMs > 0) "${net.rttMs} ms (±${net.jitterMs} ms · ${net.retransPerSec} retx/s)" else "—",
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(text, style = label(10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UnifiedNetGraph(
    net: NetSnapshot,
    wifiColor: Color,
    cellColor: Color,
    txColor: Color,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Canvas(modifier.clip(RoundedCornerShape(10.dp)).background(cs.surfaceContainerHigh)) {
        val gridCol = cs.outlineVariant.copy(alpha = 0.45f)
        drawLine(gridCol, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), 1.dp.toPx())
        drawLine(gridCol, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), 1.dp.toPx())

        val n = minOf(net.wifiRxHistKbps.size, net.cellRxHistKbps.size, net.txHistKbps.size)
        if (n < 2) return@Canvas
        var peak = 64f
        for (i in 0 until n) {
            val tot = (net.wifiRxHistKbps[i] + net.cellRxHistKbps[i]).toFloat()
            val tx = net.txHistKbps[i].toFloat()
            if (tot > peak) peak = tot
            if (tx > peak) peak = tx
        }
        val dx = size.width / (n - 1).coerceAtLeast(1)
        fun y(v: Float) = size.height - 4f - (v / peak) * (size.height - 10f)

        // 1) Wi-Fi Download area + stroke (only if Wi-Fi had traffic)
        if (net.wifiRxHistKbps.any { it > 0 }) {
            val wArea = Path().apply {
                moveTo(0f, size.height)
                for (i in 0 until n) lineTo(i * dx, y(net.wifiRxHistKbps[i].toFloat()))
                lineTo((n - 1) * dx, size.height)
                close()
            }
            val wLine = Path().apply {
                for (i in 0 until n) {
                    val py = y(net.wifiRxHistKbps[i].toFloat())
                    if (i == 0) moveTo(0f, py) else lineTo(i * dx, py)
                }
            }
            drawPath(wArea, wifiColor.copy(alpha = 0.28f))
            drawPath(wLine, wifiColor, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // 2) Mobile Data stacked area + stroke (only if Mobile had traffic)
        if (net.cellRxHistKbps.any { it > 0 }) {
            val cArea = Path().apply {
                for (i in 0 until n) {
                    val py = y((net.wifiRxHistKbps[i] + net.cellRxHistKbps[i]).toFloat())
                    if (i == 0) moveTo(0f, py) else lineTo(i * dx, py)
                }
                for (i in n - 1 downTo 0) lineTo(i * dx, y(net.wifiRxHistKbps[i].toFloat()))
                close()
            }
            drawPath(cArea, cellColor.copy(alpha = 0.30f))
            val cLine = Path().apply {
                for (i in 0 until n) {
                    val py = y((net.wifiRxHistKbps[i] + net.cellRxHistKbps[i]).toFloat())
                    if (i == 0) moveTo(0f, py) else lineTo(i * dx, py)
                }
            }
            drawPath(cLine, cellColor, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // 3) Upload (Tx) dashed line
        if (net.txHistKbps.any { it > 0 }) {
            val txPath = Path().apply {
                for (i in 0 until n) {
                    val py = y(net.txHistKbps[i].toFloat())
                    if (i == 0) moveTo(0f, py) else lineTo(i * dx, py)
                }
            }
            drawPath(
                txPath,
                txColor,
                style = Stroke(
                    1.3.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                ),
            )
        }
    }
}

private fun fmtSpeedBps(bps: Long): String {
    val kbps = bps / 1024.0
    return when {
        kbps >= 1024.0 -> "%.1f MB/s".format(kbps / 1024.0)
        kbps >= 1.0 -> "%.0f KB/s".format(kbps)
        bps > 0L -> "$bps B/s"
        else -> "0 KB/s"
    }
}

private fun fmtDataKb(kb: Long): String = when {
    kb >= 1_048_576L -> "%.1f GB".format(kb / 1_048_576.0)
    kb >= 1024L -> "%.0f MB".format(kb / 1024.0)
    else -> "${kb} KB"
}
