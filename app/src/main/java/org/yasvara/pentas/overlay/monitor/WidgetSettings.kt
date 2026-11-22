/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.monitor

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.yasvara.pentas.telemetry.PantauClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Field bitmasks mirror Rust `pantau::schema::field`. */
enum class Field(val key: String, val label: String, val bit: Int) {
    FPS("fps", "FPS", 1 shl 0),
    CPU("cpu", "CPU", 1 shl 1),
    GPU("gpu", "GPU", 1 shl 2),
    RAM("ram", "RAM", 1 shl 3),
    TEMP("temp", "Temp", 1 shl 4),
    NET("network", "Net", 1 shl 5),
    BATTERY("battery", "Bat", 1 shl 6),
    SYSTEM("system", "All", 1 shl 7),
    POWER("power", "W", 1 shl 8),
    THREADS("threads", "Threads", 1 shl 9),
}

/**
 * The 5 native Rust NEON HUD widgets (`WidgetId` 0..4 in `pantaud`).
 * `[key]` preserves existing user SharedPreferences keys.
 */
enum class Widget(val key: String, val label: String, val fields: List<Field>, val defaults: Set<Field>) {
    CLASSIC(
        "classical", "Classic",
        listOf(Field.FPS, Field.CPU, Field.GPU, Field.RAM, Field.TEMP, Field.NET, Field.POWER),
        setOf(Field.FPS, Field.CPU, Field.GPU, Field.RAM, Field.TEMP, Field.NET),
    ),
    MINI(
        "mini", "Mini",
        listOf(Field.FPS, Field.CPU, Field.GPU, Field.TEMP, Field.POWER, Field.RAM, Field.NET, Field.BATTERY),
        setOf(Field.FPS, Field.CPU, Field.GPU, Field.TEMP),
    ),
    PROCESSES(
        "processes", "Processes",
        listOf(Field.SYSTEM, Field.THREADS),
        setOf(Field.SYSTEM),
    ),
    TEMP(
        "temp", "Temperature",
        listOf(Field.CPU, Field.GPU, Field.BATTERY),
        setOf(Field.CPU, Field.GPU, Field.BATTERY),
    ),
    NETWORK(
        "network", "Network",
        listOf(Field.NET),
        setOf(Field.NET),
    ),
}

/**
 * Persists widget preferences locally AND pushes the canonical `Settings.Secure pentas_hud`
 * wire profile (`v=1;en=...;op=...;ma=...;f0=...;p0=...`) to native Rust `pantaud`.
 */
@Singleton
class WidgetSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pantau: PantauClient,
) {
    private val prefs = context.getSharedPreferences("monitor_settings", Context.MODE_PRIVATE)

    private val on = mutableStateMapOf<Widget, Boolean>().apply {
        Widget.entries.forEach { put(it, prefs.getBoolean("${it.key}_monitor_enabled", false)) }
    }
    private val shown = mutableStateMapOf<Pair<Widget, Field>, Boolean>().apply {
        Widget.entries.forEach { w ->
            w.fields.forEach { f -> put(w to f, prefs.getBoolean("${w.key}_show_${f.key}", f in w.defaults)) }
        }
    }
    private val _opacity = mutableFloatStateOf(prefs.getFloat("monitors_opacity", 0.88f))
    // 0 = Top Center (y=0 flush), 1 = Top Left (y=0), 2 = Top Right (y=0), 3 = Free drag
    private val _miniAnchor = mutableIntStateOf(prefs.getInt("mini_anchor", 0))
    private val _showInCapture = mutableStateOf(prefs.getBoolean("monitors_show_in_capture", true))
    private val _lockPosition = mutableStateOf(prefs.getBoolean("monitors_lock_position", false))
    @Volatile private var lastSyncedProfile: String? = runCatching {
        Settings.Secure.getStringForUser(context.contentResolver, KEY_HUD_PROFILE, UserHandle.USER_CURRENT)
    }.getOrNull()

    init {
        syncToNative()
    }

    fun enabled(w: Widget) = on[w] == true
    fun setEnabled(w: Widget, value: Boolean) {
        on[w] = value
        prefs.edit { putBoolean("${w.key}_monitor_enabled", value) }
        syncToNative()
    }

    fun shows(w: Widget, f: Field) = shown[w to f] == true
    fun setShows(w: Widget, f: Field, value: Boolean) {
        shown[w to f] = value
        prefs.edit { putBoolean("${w.key}_show_${f.key}", value) }
        syncToNative()
    }

    var opacity: Float
        get() = _opacity.floatValue
        set(v) {
            _opacity.floatValue = v
            prefs.edit { putFloat("monitors_opacity", v) }
            syncToNative()
        }

    var miniAnchor: Int
        get() = _miniAnchor.intValue
        set(v) {
            val c = v.coerceIn(0, 3)
            _miniAnchor.intValue = c
            prefs.edit { putInt("mini_anchor", c) }
            syncToNative()
        }

    var showInCapture: Boolean
        get() = _showInCapture.value
        set(v) {
            _showInCapture.value = v
            prefs.edit { putBoolean("monitors_show_in_capture", v) }
            syncToNative()
        }

    /** When false (default), floating HUD widgets can be dragged anytime even with sidebar closed. */
    var lockPosition: Boolean
        get() = _lockPosition.value
        set(v) {
            _lockPosition.value = v
            prefs.edit { putBoolean("monitors_lock_position", v) }
        }

    val count: Int get() = Widget.entries.count(::enabled)
    val needsFps: Boolean
        get() = (enabled(Widget.CLASSIC) && shows(Widget.CLASSIC, Field.FPS)) ||
            (enabled(Widget.MINI) && shows(Widget.MINI, Field.FPS))

    fun position(w: Widget): Pair<Int, Int> {
        val defX = when (w) {
            Widget.CLASSIC -> 24f
            Widget.MINI -> 0f
            Widget.PROCESSES -> 420f
            Widget.TEMP -> 900f
            Widget.NETWORK -> 24f
        }
        val defY = when (w) {
            Widget.MINI -> 0f
            Widget.NETWORK -> 440f
            else -> 108f
        }
        return prefs.getFloat("${w.key}_x", defX).toInt() to prefs.getFloat("${w.key}_y", defY).toInt()
    }

    fun savePosition(w: Widget, x: Int, y: Int) {
        val cx = x.coerceAtLeast(0)
        val cy = y.coerceAtLeast(0)
        prefs.edit { putFloat("${w.key}_x", cx.toFloat()); putFloat("${w.key}_y", cy.toFloat()) }
        if (w == Widget.MINI && _miniAnchor.intValue != 3) {
            _miniAnchor.intValue = 3
            prefs.edit { putInt("mini_anchor", 3) }
        }
        syncToNative()
    }

    /** Formats canonical `HudProfile` string matching Rust `pantau::schema::HudProfile::parse`. */
    fun toWireProfile(): String {
        var en = 0
        Widget.entries.forEach { w -> if (enabled(w)) en = en or (1 shl w.ordinal) }
        val op = (_opacity.floatValue * 255f).roundToInt().coerceIn(77, 255)
        val sb = StringBuilder(220)
        sb.append("v=1;en=").append(en)
            .append(";op=").append(op)
            .append(";ma=").append(_miniAnchor.intValue)
            .append(";cap=").append(if (_showInCapture.value) 1 else 0)
            .append(";soff=1;pol=close")
        Widget.entries.forEach { w ->
            var fm = 0
            w.fields.forEach { f -> if (shows(w, f)) fm = fm or f.bit }
            val (px, py) = position(w)
            sb.append(";f").append(w.ordinal).append('=').append(fm)
            sb.append(";p").append(w.ordinal).append('=')
                .append(px).append(',').append(py).append(',')
                .append(px).append(',').append(py)
        }
        return sb.toString()
    }

    fun syncToNative() {
        val raw = toWireProfile()
        if (raw == lastSyncedProfile) return
        lastSyncedProfile = raw
        runCatching {
            Settings.Secure.putStringForUser(
                context.contentResolver,
                KEY_HUD_PROFILE,
                raw,
                UserHandle.USER_CURRENT,
            )
        }
        pantau.configureHud(raw)
    }

    companion object {
        private const val KEY_HUD_PROFILE = "pentas_hud"
    }
}
