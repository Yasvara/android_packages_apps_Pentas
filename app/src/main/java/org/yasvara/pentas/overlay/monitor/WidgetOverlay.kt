/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.monitor

import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.yasvara.pentas.overlay.overlayParams
import org.yasvara.pentas.overlay.overlayView
import org.yasvara.pentas.telemetry.FpsInteractor
import org.yasvara.pentas.telemetry.TelemetryBus
import org.yasvara.pentas.telemetry.network.NetworkTelemetry
import javax.inject.Inject
import javax.inject.Singleton

/** One process ranked by CPU share. */
data class ProcessUsage(val name: String, val usagePercent: Float)

/**
 * Owns the floating widget windows for a game session. Every data source runs only while a
 * widget that shows it is on: the telemetry bus for any widget, FPS for an FPS field, the
 * process sampler while [processes] is collected. Widgets pass touches through to the game
 * unless the Widgets page is open, when they can be dragged.
 */
@Singleton
class WidgetOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
    val settings: WidgetSettings,
    val bus: TelemetryBus,
    val fps: FpsInteractor,
    val network: NetworkTelemetry,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val views = mutableMapOf<Widget, Pair<ComposeView, WindowManager.LayoutParams>>()
    private var scope: CoroutineScope? = null
    private var busOn = false
    private var fpsOn = false

    /** True while the Widgets settings page is open: widgets become draggable. */
    val editing = mutableStateOf(false)

    /** Top processes by CPU, shared by the Processes widget and the CPU card; sampled only while collected. */
    val processes: StateFlow<List<ProcessUsage>> = flow {
        val s = ProcessCpuSampler(context, topN = 12)
        while (true) {
            s.sample().takeIf { it.isNotEmpty() }?.let { emit(it) }
            delay(1500)
        }
    }.flowOn(Dispatchers.IO).stateIn(
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        SharingStarted.WhileSubscribed(replayExpirationMillis = 0), emptyList(),
    )

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            snapshotFlow {
                Triple(Widget.entries.filter(settings::enabled), settings.needsFps, editing.value)
            }.collect { (enabled, needFps, edit) -> reconcile(enabled, needFps, edit) }
        }
    }

    private fun reconcile(enabled: List<Widget>, needFps: Boolean, edit: Boolean) {
        val any = enabled.isNotEmpty()
        if (any != busOn) { busOn = any; if (any) bus.start() else bus.stop() }
        if (needFps != fpsOn) { fpsOn = needFps; if (needFps) fps.start() else fps.dispose() }
        Widget.entries.forEach { w ->
            val existing = views[w]
            if (w !in enabled) {
                existing?.let { (v, _) -> runCatching { wm.removeViewImmediate(v) } }
                views.remove(w)
                return@forEach
            }
            val (v, p) = existing ?: create(w)
            val flags = p.flags
            p.flags = if (edit) flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (v.isAttachedToWindow) runCatching { wm.updateViewLayout(v, p) } else runCatching { wm.addView(v, p) }
        }
    }

    private fun create(w: Widget): Pair<ComposeView, WindowManager.LayoutParams> {
        val (x, y) = settings.position(w)
        val p = overlayParams(touchable = false).apply { this.x = x; this.y = y }
        lateinit var v: ComposeView
        v = overlayView(context) {
            WidgetFrame(
                widget = w, overlay = this,
                onDrag = { dx, dy -> p.x += dx.toInt(); p.y += dy.toInt(); runCatching { wm.updateViewLayout(v, p) } },
                onDragEnd = { settings.savePosition(w, p.x, p.y) },
            )
        }
        return (v to p).also { views[w] = it }
    }

    fun setVisible(visible: Boolean) {
        views.values.forEach { (v, _) -> v.visibility = if (visible) View.VISIBLE else View.GONE }
    }

    fun stop() {
        scope?.cancel(); scope = null
        views.values.forEach { (v, _) -> runCatching { wm.removeViewImmediate(v) } }
        views.clear()
        if (busOn) { busOn = false; bus.stop() }
        if (fpsOn) { fpsOn = false; fps.dispose() }
        editing.value = false
    }

    private fun CoroutineScope.cancel() = coroutineContext[Job]?.cancel()
}
