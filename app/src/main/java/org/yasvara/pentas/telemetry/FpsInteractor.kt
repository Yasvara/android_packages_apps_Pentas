/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.telemetry

import android.app.ActivityTaskManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import android.window.TaskFpsCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** The panel's active refresh rate and the distinct rates its modes offer, ascending (Hz). */
data class PanelRates(val hz: Int, val ladder: List<Int>)

/**
 * The focused task's frame rate from WindowManager's TaskFpsCallback, sampled at 2 Hz and
 * lightly smoothed. Registered only while something on screen shows it.
 */
@Singleton
@OptIn(FlowPreview::class)
class FpsInteractor @Inject constructor(context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val dm = context.getSystemService(DisplayManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reports = MutableSharedFlow<Float>(extraBufferCapacity = 64)
    private val buffer = ArrayDeque<Float>(HISTORY)
    private val _history = MutableStateFlow<List<Float>>(emptyList())

    /** Last [HISTORY] samples (500 ms apart), newest last. */
    val realFpsHistory: StateFlow<List<Float>> get() = _history

    /** The default display's rates now; the user's display setting decides them. */
    fun panelRates(): PanelRates {
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return PanelRates(0, emptyList())
        return PanelRates(d.mode.refreshRate.roundToInt(), d.supportedModes.map { it.refreshRate.roundToInt() }.distinct().sorted())
    }

    /** [panelRates] on every mode or rate change. The listener exists only while collected. */
    val panel: Flow<PanelRates> = callbackFlow {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(id: Int) { if (id == Display.DEFAULT_DISPLAY) trySend(panelRates()) }
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {}
        }
        // DISPLAY_CHANGED alone omits refresh-rate switches.
        dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()),
            DisplayManager.EVENT_TYPE_DISPLAY_CHANGED or DisplayManager.EVENT_TYPE_DISPLAY_REFRESH_RATE)
        send(panelRates())
        awaitClose { dm.unregisterDisplayListener(listener) }
    }.distinctUntilChanged()

    private val callback = object : TaskFpsCallback() {
        override fun onFpsReported(fps: Float) { reports.tryEmit(fps) }
    }

    @Volatile private var taskId = -1
    private var users = 0

    init {
        scope.launch {
            reports.sample(500).collect { fps ->
                val smooth = (buffer.lastOrNull() ?: fps) * 0.5f + fps * 0.5f
                if (buffer.size >= HISTORY) buffer.removeFirst()
                buffer.addLast(smooth)
                _history.value = buffer.toList()
            }
        }
    }

    /** Reference-counted: the panel, the widgets and stats recording each hold one. */
    @Synchronized
    fun start() {
        users++
        val id = runCatching { ActivityTaskManager.getService().focusedRootTaskInfo?.taskId }.getOrNull() ?: return
        if (id == taskId) return
        if (taskId >= 0) runCatching { wm.unregisterTaskFpsCallback(callback) }
        taskId = if (runCatching { wm.registerTaskFpsCallback(id, Runnable::run, callback) }.isSuccess) id else -1
    }

    @Synchronized
    fun dispose() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0 || taskId < 0) return
        runCatching { wm.unregisterTaskFpsCallback(callback) }
        taskId = -1
        buffer.clear()
        _history.value = emptyList()
    }

    /** Session end: drop every holder at once. */
    @Synchronized
    fun reset() { users = 1; dispose() }

    companion object { const val HISTORY = 120 }
}
