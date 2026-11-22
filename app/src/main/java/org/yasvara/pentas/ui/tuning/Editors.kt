/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.ui.tuning

import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.yasvara.pentas.audio.DolbyBridge
import org.yasvara.pentas.data.AngleDriver
import org.yasvara.pentas.data.PerAppSettingStore
import org.yasvara.pentas.thermal.PerfTuner
import org.yasvara.pentas.touch.GameTouchModeManager
import org.yasvara.pentas.touch.RateMeter
import org.yasvara.pentas.ui.kit.Block
import org.yasvara.pentas.ui.kit.RangeSlider
import org.yasvara.pentas.ui.kit.Section
import org.yasvara.pentas.ui.kit.Segmented
import org.yasvara.pentas.ui.kit.SettingRow
import org.yasvara.pentas.ui.kit.Slider
import org.yasvara.pentas.ui.kit.SwitchRow
import org.yasvara.pentas.ui.kit.Tag
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private fun factor(f: Float): String =
    if (abs(f - 1f) < 0.001f) "1.0" else "%.2f".format(f).trimEnd('0').trimEnd('.').let { if ('.' in it) it else "$it.0" }

private val SSAA = listOf(1.25f, 1.5f, 2f)
// Stops of the slider below, named as upscalers name them.
private val RENDER_PRESETS = listOf(
    "Native" to 1f, "Quality" to 0.75f, "Balanced" to 0.65f, "Performance" to 0.5f, "Ultra" to 0.35f,
)
private const val ALIH_TARGET = "alih_target_language"

@Composable fun LiveTag() = Tag("Live")
@Composable fun RestartTag() = Tag("Restart", warn = true)

// ── graphics ──────────────────────────────────────────────────────────────

@Composable
fun GraphicsEditor(pkg: String, game: Boolean = true) {
    val t = rememberTuning()
    val ctx = LocalContext.current
    var scale by remember(pkg) { mutableFloatStateOf(t.perApp().resolution(pkg).toFloatOrNull() ?: 1f) }
    val screen = remember {
        val b = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        max(b.width(), b.height()) to min(b.width(), b.height())
    }
    fun commit(f: Float) { scale = f; t.perApp().setResolution(pkg, factor(f)) }

    if (game) Block(padding = 10.dp) {
        SettingRow("Render resolution", tag = { LiveTag() }, value = "%.2f×".format(scale))
        ScaleFigure(scale, screen)
        Segmented(
            RENDER_PRESETS,
            selected = RENDER_PRESETS.firstOrNull { abs(it.second - scale) < 0.005f }?.second ?: 0f,
            onSelect = ::commit,
        )
        Slider(min(scale, 1f), { scale = it }, range = 0.25f..1f, steps = 14, onCommit = ::commit)
        SettingRow("Supersampling") {}
        Segmented(
            listOf("Off" to 1f) + SSAA.map { "${factor(it)}×" to it },
            selected = SSAA.firstOrNull { it == scale } ?: 1f,
            onSelect = { if (it > 1f || scale > 1f) commit(it) },
        )
    }
    if (game) UpscaleEditor(pkg)
    if (game) TextureEditor(pkg)
    if (game) ShadingEditor(pkg)
    Block(padding = 10.dp) {
        var style by remember(pkg) { mutableIntStateOf(t.perApp().displayStyle(pkg)) }
        SettingRow("Display color", tag = { LiveTag() })
        Segmented(
            listOf("Original" to 0, "Vivid" to 1, "Saturated" to 2, "P3" to 3, "sRGB" to 4),
            selected = style, onSelect = { style = it; t.perApp().setDisplayStyle(pkg, it) },
        )
    }
    if (!game) return
    LepasEditor(pkg)
    LuwesEditor(pkg)
    TranslateEditor(pkg)
    Section("Advanced")
    Block(padding = 10.dp) {
        val angle = t.angle()
        if (angle.available) {
            var api by remember(pkg) { mutableStateOf(angle.get(pkg)) }
            SettingRow("GLES driver", tag = { RestartTag() })
            Segmented(
                listOf("Default" to AngleDriver.DEFAULT, "ANGLE" to AngleDriver.ANGLE, "Native" to AngleDriver.NATIVE),
                selected = api, onSelect = { api = it; angle.set(pkg, it) },
            )
        }
        var gpuComp by remember(pkg) { mutableStateOf(t.perApp().gpuComposition(pkg)) }
        SwitchRow("GPU composition", gpuComp, { gpuComp = it; t.perApp().setGpuComposition(pkg, it) }, tag = { LiveTag() })
    }
}

/** Alih: the game's text in another language, in the game's own draws. Needs Vulkan, so ANGLE for GLES. */
@Composable
private fun TranslateEditor(pkg: String) {
    val t = rememberTuning()
    val resolver = LocalContext.current.contentResolver
    val luwes = t.luwes()
    val angle = t.angle()
    var on by remember(pkg) { mutableStateOf(luwes.lapis(pkg).textTap) }
    var target by remember { mutableStateOf(Settings.Global.getString(resolver, ALIH_TARGET).orEmpty()) }
    Block(padding = 10.dp) {
        SwitchRow("Translate text", on, { want ->
            if (luwes.setTextTap(pkg, want)) {
                on = want
                if (want && angle.available && angle.get(pkg) == AngleDriver.DEFAULT) {
                    angle.set(pkg, AngleDriver.ANGLE)
                }
            }
        }, tag = { RestartTag() })
        if (on) {
            Segmented(
                listOf("System" to "", "Indonesian" to "id", "English" to "en", "Japanese" to "ja"),
                selected = target,
                onSelect = { target = it; Settings.Global.putString(resolver, ALIH_TARGET, it) },
            )
            Text("Replaces text in the game. On-screen text goes to Google Translate. Off for online competitive games.",
                style = label(11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Screen and drawn buffer as nested rectangles, sized to scale. */
@Composable
private fun ScaleFigure(scale: Float, screen: Pair<Int, Int>) {
    val cs = MaterialTheme.colorScheme
    val (w, h) = screen
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.height(54.dp).fillMaxWidth(0.42f).clip(RoundedCornerShape(6.dp))
            .border(1.5.dp, cs.outline, RoundedCornerShape(6.dp)), contentAlignment = Alignment.TopStart) {
            val s = scale.coerceAtMost(1f)
            Box(Modifier.fillMaxWidth(s).fillMaxHeight(s).clip(RoundedCornerShape(4.dp)).background(cs.primary.copy(alpha = 0.55f)))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Screen  ${w}×$h", style = num(11.sp, 500), color = cs.onSurfaceVariant)
            Text("Drawn  ${(w * scale).roundToInt()}×${(h * scale).roundToInt()}", style = num(11.sp, 600), color = cs.onSurface)
        }
    }
}

// ── clocks ────────────────────────────────────────────────────────────────

/** Per-game clocks: own limits or the global baseline. */
@Composable
fun GameClocks(pkg: String) {
    val perf = rememberTuning().perf()
    var scope by remember(pkg) { mutableIntStateOf(perf.clockScope(pkg)) }
    var profile by remember(pkg) { mutableStateOf(perf.loadProfile(pkg) ?: PerfTuner.PerfProfile()) }
    Block(padding = 10.dp) {
        Segmented(
            listOf("This game" to PerfTuner.ClockScope.PER_APP, "Global" to PerfTuner.ClockScope.GLOBAL),
            selected = scope, onSelect = { scope = it; perf.setClockScope(pkg, it) },
        )
    }
    val global = scope == PerfTuner.ClockScope.GLOBAL
    ClockEditor(
        perf, if (global) perf.loadGlobalProfile() else profile, editable = !global,
        onCommit = { profile = it; perf.saveProfileFor(pkg, it) },
    )
}

/** Min/max clock per CPU cluster and GPU, snapped to the kernel tables. */
@Composable
fun ClockEditor(perf: PerfTuner, profile: PerfTuner.PerfProfile, editable: Boolean = true, onCommit: (PerfTuner.PerfProfile) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var live by remember { mutableStateOf(emptyMap<Int, Long>()) }
    var gpuCap by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            live = perf.cpuClusters.associate { it.policyIndex to perf.cpuLiveMaxKhz(it) }
            gpuCap = perf.gpuLiveMaxHz()
            delay(1500)
        }
    }
    Block(padding = 10.dp) {
        perf.cpuClusters.filter { it.availableKhz.size > 1 }.forEach { c ->
            val t = profile.clusters[c.policyIndex] ?: PerfTuner.ClusterTuning()
            ClockRange(
                c.name, c.availableKhz, t.minKhz ?: c.hwMinKhz, t.maxKhz ?: c.hwMaxKhz, live[c.policyIndex] ?: 0L,
                editable, format = { "%.2f".format(it / 1e6) }, unit = "GHz",
            ) { lo, hi ->
                val tuned = t.copy(minKhz = lo.takeIf { it != c.hwMinKhz }, maxKhz = hi.takeIf { it != c.hwMaxKhz })
                onCommit(profile.copy(clusters = profile.clusters + (c.policyIndex to tuned)))
            }
        }
    }
    val gpu = perf.gpuInfo
    if (gpu.availableHz.size > 1) Block(padding = 10.dp) {
        ClockRange(
            "GPU", gpu.availableHz.sorted(), profile.gpuMinHz ?: gpu.hwMinHz, profile.gpuMaxHz ?: gpu.hwMaxHz, gpuCap,
            editable, format = { "${it / 1_000_000}" }, unit = "MHz",
        ) { lo, hi ->
            onCommit(profile.copy(gpuMinHz = lo.takeIf { it != gpu.hwMinHz }, gpuMaxHz = hi.takeIf { it != gpu.hwMaxHz }))
        }
    }
    if (editable && !profile.isDefault) Text(
        "Reset to stock",
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onCommit(PerfTuner.PerfProfile()) }.padding(8.dp),
        style = label(12.sp, 600), color = cs.error,
    )
}

/** One clock domain: floor and ceiling over [table]; a tag shows a lower live ceiling. */
@Composable
private fun ClockRange(
    name: String,
    table: List<Long>,
    min: Long,
    max: Long,
    liveCap: Long,
    editable: Boolean,
    format: (Long) -> String,
    unit: String,
    onCommit: (Long, Long) -> Unit,
) {
    var lo by remember(name, min) { mutableIntStateOf(table.indexOfNearest(min)) }
    var hi by remember(name, max) { mutableIntStateOf(table.indexOfNearest(max)) }
    val ceiling = liveCap.takeIf { it in 1 until table[hi] }
    SettingRow(name, value = "${format(table[lo])} – ${format(table[hi])} $unit",
        tag = ceiling?.let { { Tag(format(it), warn = true) } })
    RangeSlider(
        lo, hi, table.size,
        onChange = { a, b -> if (editable) { lo = a; hi = b } },
        onCommit = { a, b -> if (editable) onCommit(table[a], table[b]) },
        marker = ceiling?.let { table.indexOfNearest(it) },
    )
}

private fun List<Long>.indexOfNearest(v: Long): Int = indices.minByOrNull { abs(this[it] - v) } ?: 0

// ── touch ─────────────────────────────────────────────────────────────────

@Composable
fun TouchEditor(pkg: String, pad: Boolean) {
    val touch = rememberTuning().touch()
    if (pad) Block(padding = 6.dp) { TouchPad() }
    if (touch.supportsSuperReport) Block(padding = 10.dp) {
        var on by remember(pkg) { mutableStateOf(touch.superReport(pkg)) }
        LaunchedEffect(on) { touch.refreshReportRate() }
        SwitchRow("Super touch", on, { on = it; touch.setSuperReport(pkg, it) }, tag = { LiveTag() })
        SettingRow("Scan rate", value = "${touch.reportRateHz.intValue} Hz")
    }
    if (touch.isAvailable) Block(padding = 10.dp) {
        var stable by remember(pkg) { mutableStateOf(touch.stable(pkg)) }
        SettingRow("Touch response")
        Segmented(listOf("Fast" to false, "Stable" to true), stable, { stable = it; touch.setStable(pkg, it) })
    }
    if (touch.isAvailable) Block(padding = 10.dp) {
        val levels = listOf("Off" to GameTouchModeManager.EDGE_OFF, "Low" to GameTouchModeManager.EDGE_LOW,
            "Mid" to GameTouchModeManager.EDGE_MEDIUM, "High" to GameTouchModeManager.EDGE_HIGH)
        var edge by remember(pkg) { mutableIntStateOf(touch.edgeFilter(pkg)) }
        SettingRow("Edge rejection")
        Segmented(levels, edge, { edge = it; touch.setEdgeFilter(pkg, it) })
    }
}

/** Draw here to read the panel's real sample rate, history samples included. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TouchPad() {
    val cs = MaterialTheme.colorScheme
    val meter = remember { RateMeter() }
    var hz by remember { mutableIntStateOf(0) }
    var peak by remember { mutableIntStateOf(0) }
    val points = remember { mutableStateListOf<Offset>() }
    Box(
        Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerHigh)
            .pointerInteropFilter { e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { meter.reset(); points.clear() }
                    MotionEvent.ACTION_MOVE -> {
                        for (h in 0 until e.historySize) meter.addSample(e.getHistoricalEventTime(h))
                        meter.addSample(e.eventTime)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> meter.stop()
                }
                meter.update(e.eventTime)
                hz = meter.instantHz; peak = meter.peakHz
                points.clear()
                for (i in 0 until e.pointerCount) points += Offset(e.getX(i), e.getY(i))
                true
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) { points.forEach { drawCircle(cs.primary.copy(alpha = 0.5f), 18.dp.toPx(), it) } }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (hz > 0) "$hz" else "—", style = num(28.sp, 620), color = cs.onSurface)
            Text(if (peak > 0) "Hz · peak $peak" else "Hz", style = label(11.sp), color = cs.onSurfaceVariant)
        }
    }
}

// ── sound ─────────────────────────────────────────────────────────────────

/** Hentak: the game's shots, hits and blasts felt on the motor, read from the game's own audio. */
@Composable
private fun HentakEditor(pkg: String) {
    val store = rememberTuning().perApp()
    var mode by remember(pkg) { mutableIntStateOf(store.hentakMode(pkg)) }
    var sens by remember(pkg) { mutableFloatStateOf(store.hentakSensitivity(pkg)) }
    Block(padding = 10.dp) {
        SettingRow("4D haptics", tag = { LiveTag() })
        Segmented(
            listOf("Off" to PerAppSettingStore.HENTAK_OFF, "On" to PerAppSettingStore.HENTAK_ON,
                "Strong" to PerAppSettingStore.HENTAK_STRONG),
            selected = mode, onSelect = { mode = it; store.setHentakMode(pkg, it) },
        )
        if (mode == PerAppSettingStore.HENTAK_OFF) return@Block
        SettingRow("Sensitivity", value = "%.1f×".format(sens))
        Slider(sens, { sens = it }, range = 0.5f..1.5f, steps = 9, onCommit = { store.setHentakSensitivity(pkg, it) })
        if (mode == PerAppSettingStore.HENTAK_STRONG) {
            Text("Strong drives the motor past its limit: harder hits, more heat and some rattle, and the battery drains faster. " +
                "Haptics ease off on their own when the motor gets hot.",
                style = label(11.sp), color = MaterialTheme.colorScheme.tertiary)
        } else {
            Text("Follows the game's sound, so music with heavy beats can trigger it too.",
                style = label(11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SoundEditor(pkg: String?) {
    if (pkg != null) HentakEditor(pkg)
    val dolby = rememberTuning().dolby()
    LaunchedEffect(Unit) { dolby.refresh() }
    val st = dolby.state
    if (st.available) {
        Block(padding = 10.dp) {
            SwitchRow("Dolby Atmos", st.enabled, dolby::setEnabled)
            Segmented(
                listOf("Dynamic" to DolbyBridge.PROFILE_DYNAMIC, "Movie" to DolbyBridge.PROFILE_MOVIE,
                    "Music" to DolbyBridge.PROFILE_MUSIC, "Custom" to DolbyBridge.PROFILE_CUSTOM),
                selected = st.profile, onSelect = dolby::setProfile,
            )
            Text("For the device that is playing now. A game's own profile below goes over it.",
                style = label(11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (pkg != null) NadaEditor(pkg)
}

