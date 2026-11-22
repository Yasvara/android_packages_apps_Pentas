/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.telemetry

import android.app.ActivityTaskManager
import android.app.WindowConfiguration
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import android.window.TaskFpsCallback
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** The panel's active refresh rate and the distinct rates its modes offer, ascending (Hz). */
data class PanelRates(val hz: Int, val ladder: List<Int>)

/**
 * Focused task's frame rate — strict **single source of truth**:
 *
 * - On ROMs with native Rust `pantaud` (`/system_ext/bin/pantaud`), mirrors `pantaud`'s sealed
 *   shared-memory SF FPS ring exclusively (**zero** duplicate `TaskFpsCallback` listeners!).
 * - Falls back to `TaskFpsCallback` ONLY when `pantaud` is absent.
 */
@Singleton
class FpsInteractor @Inject constructor(
    context: Context,
    private val bus: TelemetryBus,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val dm = context.getSystemService(DisplayManager::class.java)
    private val buffer = ArrayDeque<Float>(HISTORY)
    private val _history = MutableStateFlow<List<Float>>(emptyList())

    /**
     * Last [HISTORY] samples, newest last.
     * When native `pantaud` is installed, exposes `bus.fpsHistory` directly with zero background
     * coroutine and zero duplicate StateFlow allocations.
     */
    val realFpsHistory: StateFlow<List<Float>>
        get() = if (bus.isNativeDaemonInstalled) bus.fpsHistory else _history

    /** The default display's rates now; the user's display setting decides them. */
    fun panelRates(): PanelRates {
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return PanelRates(0, emptyList())
        return PanelRates(
            d.mode.refreshRate.roundToInt(),
            d.supportedModes.map { it.refreshRate.roundToInt() }.distinct().sorted(),
        )
    }

    /** [panelRates] on every mode or rate change. The listener exists only while collected. */
    val panel: Flow<PanelRates> = callbackFlow {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(id: Int) { if (id == Display.DEFAULT_DISPLAY) trySend(panelRates()) }
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {}
        }
        dm.registerDisplayListener(
            listener,
            Handler(Looper.getMainLooper()),
            DisplayManager.EVENT_TYPE_DISPLAY_CHANGED or DisplayManager.EVENT_TYPE_DISPLAY_REFRESH_RATE,
        )
        send(panelRates())
        awaitClose { dm.unregisterDisplayListener(listener) }
    }.distinctUntilChanged()

    private var lastFallbackEmitMs = 0L

    // Fallback-only callback (NEVER registered when `pantaud` is installed).
    private val callback = object : TaskFpsCallback() {
        override fun onFpsReported(fps: Float) {
            if (bus.isNativeDaemonInstalled) return
            val now = SystemClock.uptimeMillis()
            if (lastFallbackEmitMs != 0L && now - lastFallbackEmitMs < 500L) return
            lastFallbackEmitMs = now
            synchronized(buffer) {
                val smooth = (buffer.lastOrNull() ?: fps) * 0.5f + fps * 0.5f
                if (buffer.size >= HISTORY) buffer.removeFirst()
                buffer.addLast(smooth)
                _history.value = buffer.toList()
            }
        }
    }

    @Volatile private var taskId = -1
    private var users = 0

    private fun activeFullscreenTask(): ActivityTaskManager.RootTaskInfo? {
        val atm = ActivityTaskManager.getService()
        val focused = runCatching { atm.focusedRootTaskInfo }.getOrNull() ?: return null
        val mode = focused.windowingMode
        if (mode != WindowConfiguration.WINDOWING_MODE_FREEFORM &&
            mode != WindowConfiguration.WINDOWING_MODE_PINNED
        ) {
            return focused
        }
        val all = runCatching { atm.getAllRootTaskInfosOnDisplay(focused.displayId) }.getOrNull()
        return all?.firstOrNull {
            it.visible && it.windowingMode == WindowConfiguration.WINDOWING_MODE_FULLSCREEN
        } ?: focused
    }

    /** Reference-counted: the panel, the widgets and stats recording each hold one. */
    @Synchronized
    fun start() {
        users++
        val topTask = activeFullscreenTask()
        val id = topTask?.taskId ?: return

        if (bus.isNativeDaemonInstalled) {
            // Strictly single FPS source (`pantaud` SfFpsTracker): never register a second
            // TaskFpsCallback listener! Only verify pantaud tracks the live fullscreen taskId.
            if (bus.nativeFpsTaskId != id) {
                val uid = topTask.topActivityInfo?.applicationInfo?.uid ?: 0
                bus.syncGameTask(id, uid)
            }
            return
        }

        // Fallback when pantaud binary is absent.
        if (id == taskId) return
        if (taskId >= 0) runCatching { wm.unregisterTaskFpsCallback(callback) }
        lastFallbackEmitMs = 0L
        taskId = if (runCatching { wm.registerTaskFpsCallback(id, Runnable::run, callback) }.isSuccess) id else -1
    }

    @Synchronized
    fun dispose() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0 || taskId < 0) return
        runCatching { wm.unregisterTaskFpsCallback(callback) }
        taskId = -1
    }

    /** Session end: drop every holder and clear history. */
    @Synchronized
    fun reset() {
        users = 1
        dispose()
        lastFallbackEmitMs = 0L
        synchronized(buffer) { buffer.clear() }
        bus.clearFpsHistory()
        _history.value = emptyList()
    }

    companion object { const val HISTORY = 120 }
}
