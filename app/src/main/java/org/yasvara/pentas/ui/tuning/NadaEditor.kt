/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.ui.tuning

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.server.yasvara.nada.Nada
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.yasvara.pentas.ui.kit.Block
import org.yasvara.pentas.ui.kit.Segmented
import org.yasvara.pentas.ui.kit.SettingRow
import org.yasvara.pentas.ui.kit.Slider
import org.yasvara.pentas.ui.kit.SwitchRow
import org.yasvara.pentas.ui.kit.label

private val nadaIo = Executors.newSingleThreadExecutor()

private fun text(v: Float) = if (v == v.roundToInt().toFloat()) v.roundToInt().toString() else "%.2f".format(Locale.ROOT, v)

/**
 * This app's own processor-effects profile. The controls come from the engine's schema, the
 * same one Laras draws, so a setting added there shows up here unchanged. The profile is in
 * force while the app is in front; without one the app follows the output device's profile.
 */
@Composable
fun NadaEditor(pkg: String) {
    val nada = remember { runCatching { Nada.get() }.getOrNull() } ?: return
    val scope = Nada.SCOPE_APP + pkg
    var schema by remember { mutableStateOf<Nada.Schema?>(null) }
    var values by remember(pkg) { mutableStateOf<Map<String, String>?>(null) }
    LaunchedEffect(pkg) {
        withContext(Dispatchers.IO) {
            runCatching {
                schema = Nada.Schema.parse(nada.schema)
                values = nada.getProfile(scope).takeIf { it.isNotEmpty() }?.let { Nada.decode(it) }
            }
        }
    }
    val s = schema?.takeIf { it.params.isNotEmpty() } ?: return
    fun commit(next: Map<String, String>?) {
        values = next
        nadaIo.execute {
            runCatching { if (next == null) nada.clearProfile(scope) else nada.setProfile(scope, Nada.encode(next)) }
        }
    }
    Block(padding = 10.dp) {
        SwitchRow("Own sound profile", values != null, { commit(if (it) mapOf("on" to "1") else null) }, tag = { LiveTag() })
        Text(
            "Runs on the processor inside the mixer, with no added delay. A game on a direct (MMAP) stream is moved to the fast mixer to be processed, about 7 ms.",
            style = label(11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val v = values ?: return
    s.groups.forEach { group ->
        val shown = group.params.filter { it.type != Nada.Param.BANDS && it.type != Nada.Param.TEXT }
        if (shown.isEmpty()) return@forEach
        Block(padding = 10.dp) {
            shown.forEach { p ->
                val cur = v[p.key]?.toFloatOrNull() ?: p.def
                if (p.type == Nada.Param.BOOL) {
                    SwitchRow(p.title, cur != 0f, { commit(v + (p.key to if (it) "1" else "0")) })
                } else if (p.type == Nada.Param.ENUM) {
                    SettingRow(p.title)
                    val all = p.options.mapIndexed { i, name -> name to i }
                    all.chunked(if (all.size > 4) 3 else 4).forEach { row ->
                        Segmented(row, selected = cur.roundToInt(), onSelect = { commit(v + (p.key to it.toString())) })
                    }
                } else {
                    var drag by remember(p.key, cur) { mutableStateOf(cur) }
                    fun snap(x: Float) = ((x - p.min) / p.step).roundToInt() * p.step + p.min
                    SettingRow(p.title, value = text(drag) + " " + p.unit)
                    Slider(
                        drag, { drag = snap(it) }, range = p.min..p.max,
                        onCommit = { commit(v + (p.key to text(snap(it)))) },
                    )
                }
            }
            if (group.params.any { it.type == Nada.Param.BANDS || it.type == Nada.Param.TEXT }) {
                Text(
                    "Bands and impulse responses are edited in Settings › Sound.", style = label(11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
