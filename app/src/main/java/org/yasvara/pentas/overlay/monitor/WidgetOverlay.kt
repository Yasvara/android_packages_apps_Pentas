/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.yasvara.pentas.overlay.overlayParams
import org.yasvara.pentas.overlay.overlayView
import org.yasvara.pentas.telemetry.FpsInteractor
import org.yasvara.pentas.telemetry.PantauClient
import org.yasvara.pentas.telemetry.TelemetryBus
import org.yasvara.pentas.telemetry.network.NetworkTelemetry
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * One process or thread ranked by CPU share (fed directly by Rust `pantaud` cgroups — 0 `top` forks!).
 * `kind`: `0` = app, `1` = system daemon / kernel, `2` = foreground thread.
 */
data class ProcessUsage(
    val name: String,
    val usagePercent: Float,
    val uid: Int = 0,
    val kind: Int = 0,
)

/**
 * Bridges HUD widget settings & drag gestures to the native Rust NEON `pantaud` overlay.
 *
 * - Rendering is 100% native Rust NEON in `pantaud` SurfaceFlinger layers (zero Compose HUD windows,
 *   zero duplicate overlays!).
 * - Pre-mounts 5 zero-Compose, zero-draw [HudDragView] hitboxes ONCE on session start (strictly
 *   below Sidebar's panel in WindowManager Z-order so they NEVER block Sidebar scrolling/clicks!).
 * - When the sidebar is closed (or on `WidgetsPage`), unlocked floating widgets can be dragged
 *   smoothly at 120 Hz directly with `MotionEvent.rawX`/`rawY`.
 * - Quick-tapping the header of the floating `Processes` HUD cycles `All → Apps → Threads` live!
 */
@Singleton
class WidgetOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
    val settings: WidgetSettings,
    val bus: TelemetryBus,
    val fps: FpsInteractor,
    val network: NetworkTelemetry,
    private val pantau: PantauClient,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val views = mutableMapOf<Widget, Pair<View, WindowManager.LayoutParams>>()
    private var scope: CoroutineScope? = null
    private var busOn = false
    private var fpsOn = false

    /** True while the Widgets settings page is open: shows drag outline & unlocks Mini drag. */
    val editing = mutableStateOf(false)
    /** True while Sidebar panel window is visible (kept above drag hitboxes in Z-order). */
    val panelOpen = mutableStateOf(false)
    private val configRev = mutableIntStateOf(0)

    /** Top processes from Rust `pantaud` cgroup scanner (zero `top` forks, cached across tabs!). */
    val processes: StateFlow<List<ProcessUsage>> get() = bus.processes

    private data class ReconcileKey(
        val enabled: List<Widget>,
        val needFps: Boolean,
        val edit: Boolean,
        val panelOpen: Boolean,
        val locked: Boolean,
        val miniAnchor: Int,
        val fieldMaskSum: Int,
        val configRev: Int,
    )

    fun start() {
        if (scope != null) return
        // Pre-attach all 5 zero-draw drag hitboxes ONCE BEFORE Sidebar ever opens its panel so
        // Sidebar's panel window ALWAYS stays strictly above every HUD drag handle in Z-order!
        if (pantau.isNativeDaemonInstalled) {
            Widget.entries.forEach { w ->
                if (w !in views) {
                    val (v, p) = createNativeDragHandle(w)
                    runCatching { wm.addView(v, p) }
                }
            }
        }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            snapshotFlow {
                var fsum = 0
                Widget.entries.forEach { w ->
                    w.fields.forEach { f ->
                        if (settings.shows(w, f)) fsum = fsum * 31 + w.ordinal * 17 + f.ordinal + 1
                    }
                }
                ReconcileKey(
                    enabled = Widget.entries.filter(settings::enabled),
                    needFps = settings.needsFps,
                    edit = editing.value,
                    panelOpen = panelOpen.value,
                    locked = settings.lockPosition,
                    miniAnchor = settings.miniAnchor,
                    fieldMaskSum = fsum,
                    configRev = configRev.intValue,
                )
            }.collect(::reconcile)
        }
    }

    /** Re-clamps drag hitboxes to new display bounds after portrait ↔ landscape rotation. */
    fun onConfigurationChanged() {
        configRev.intValue++
    }

    private fun reconcile(k: ReconcileKey) {
        val nativeHud = pantau.isNativeDaemonInstalled || bus.usingNative || pantau.isConnected
        if (nativeHud) {
            if (busOn) { busOn = false; bus.stop() }
            if (fpsOn) { fpsOn = false; fps.dispose() }

            Widget.entries.forEach { w ->
                val (v, p) = views[w] ?: createNativeDragHandle(w).also { (nv, np) ->
                    runCatching { wm.addView(nv, np) }
                }
                val isTopDockedMini = (w == Widget.MINI && settings.miniAnchor != 3)
                // Active when widget is enabled and EITHER editing in WidgetsPage OR unlocked with
                // sidebar closed (except top-docked Mini so notification pull-down stays free).
                val active = w in k.enabled && (k.edit || (!k.panelOpen && !k.locked && !isTopDockedMini))
                if (!active) {
                    v.visibility = View.GONE
                    p.width = 0
                    p.height = 0
                    p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    (v as? HudDragView)?.setHighlight(false, isTopDockedMini)
                } else {
                    val (wPx, hPx) = nativeWidgetSizePx(w)
                    val (wx, wy) = nativeWidgetPosPx(w, wPx, hPx)
                    p.width = wPx
                    p.height = hPx
                    p.x = wx
                    p.y = wy
                    p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    v.visibility = View.VISIBLE
                    (v as? HudDragView)?.setHighlight(k.edit, isTopDockedMini)
                }
                if (v.isAttachedToWindow) {
                    runCatching { wm.updateViewLayout(v, p) }
                } else {
                    runCatching { wm.addView(v, p) }
                }
            }
            return
        }

        // Fallback path (ONLY on systems without `/system_ext/bin/pantaud`).
        val any = k.enabled.isNotEmpty()
        if (any != busOn) { busOn = any; if (any) bus.start() else bus.stop() }
        if (k.needFps != fpsOn) { fpsOn = k.needFps; if (k.needFps) fps.start() else fps.dispose() }
        Widget.entries.forEach { w ->
            val existing = views[w]
            if (w !in k.enabled) {
                existing?.let { (v, _) -> runCatching { wm.removeViewImmediate(v) } }
                views.remove(w)
                return@forEach
            }
            val (v, p) = existing ?: createFallback(w)
            val touchable = k.edit || (!k.panelOpen && !k.locked)
            val flags = p.flags
            p.flags = if (touchable) flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (v.isAttachedToWindow) runCatching { wm.updateViewLayout(v, p) } else runCatching { wm.addView(v, p) }
        }
    }

    /** Matches exact pixel dimensions computed by Rust `core/src/hud/layout.rs`. */
    private fun nativeWidgetSizePx(w: Widget): Pair<Int, Int> = when (w) {
        Widget.CLASSIC -> {
            var rows = 0
            if (settings.shows(w, Field.CPU)) rows++
            if (settings.shows(w, Field.GPU)) rows++
            if (settings.shows(w, Field.RAM)) rows++
            if (settings.shows(w, Field.NET)) rows++
            if (settings.shows(w, Field.POWER)) rows++
            val bigH = if (settings.shows(w, Field.FPS)) 64 else 0
            368 to (40 + bigH + rows * 38).coerceAtLeast(76)
        }
        Widget.MINI -> {
            var px = 36
            if (settings.shows(w, Field.FPS)) px += 52
            if (settings.shows(w, Field.CPU)) px += 66
            if (settings.shows(w, Field.GPU)) px += 66
            if (settings.shows(w, Field.TEMP)) px += 46
            if (settings.shows(w, Field.POWER)) px += 62
            if (settings.shows(w, Field.RAM)) px += 72
            if (settings.shows(w, Field.NET)) px += 82
            if (settings.shows(w, Field.BATTERY)) px += 52
            px.coerceIn(144, 760) to 54
        }
        Widget.PROCESSES -> 440 to 346
        Widget.TEMP -> {
            var cells = 0
            if (settings.shows(w, Field.CPU)) cells++
            if (settings.shows(w, Field.GPU)) cells++
            if (settings.shows(w, Field.BATTERY)) cells++
            (44 + cells.coerceAtLeast(1) * 144).coerceAtMost(520) to 64
        }
        Widget.NETWORK -> 440 to 214
    }

    /** Matches exact screen position computed by Rust `core/src/hud/layout.rs`. */
    private fun nativeWidgetPosPx(w: Widget, wPx: Int, hPx: Int): Pair<Int, Int> {
        val bounds = wm.maximumWindowMetrics.bounds
        val maxX = (bounds.width() - wPx).coerceAtLeast(0)
        val maxY = (bounds.height() - hPx).coerceAtLeast(0)
        if (w == Widget.MINI && settings.miniAnchor != 3) {
            val tx = when (settings.miniAnchor) {
                1 -> 16.coerceAtMost(maxX)
                2 -> (maxX - 16).coerceAtLeast(0)
                else -> (maxX / 2).coerceAtLeast(0)
            }
            return tx to 0
        }
        val (rx, ry) = settings.position(w)
        return rx.coerceIn(0, maxX) to ry.coerceIn(0, maxY)
    }

    /** Cycles floating `Processes` HUD mode on header tap: `All` → `Apps` → `Threads` → `All`. */
    private fun cycleProcessesMode() {
        val sys = settings.shows(Widget.PROCESSES, Field.SYSTEM)
        val thr = settings.shows(Widget.PROCESSES, Field.THREADS)
        when {
            thr -> {
                settings.setShows(Widget.PROCESSES, Field.THREADS, false)
                settings.setShows(Widget.PROCESSES, Field.SYSTEM, true)
            }
            sys -> {
                settings.setShows(Widget.PROCESSES, Field.SYSTEM, false)
                settings.setShows(Widget.PROCESSES, Field.THREADS, false)
            }
            else -> {
                settings.setShows(Widget.PROCESSES, Field.SYSTEM, false)
                settings.setShows(Widget.PROCESSES, Field.THREADS, true)
            }
        }
    }

    /**
     * Zero-Compose, zero-overhead drag handle backed by a plain [HudDragView].
     * Starts hidden (`View.GONE`, `FLAG_NOT_TOUCHABLE`) and becomes active via `updateViewLayout`
     * without changing WindowManager Z-order.
     */
    private fun createNativeDragHandle(w: Widget): Pair<View, WindowManager.LayoutParams> {
        val p = overlayParams(width = 0, height = 0, touchable = false).apply {
            fitInsetsTypes = 0
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        val v = HudDragView(context).apply { visibility = View.GONE }
        var downRawX = 0f
        var downRawY = 0f
        var downLocalY = 0f
        var origX = 0
        var origY = 0
        var dragged = false
        v.setOnTouchListener { _, ev ->
            val bounds = wm.maximumWindowMetrics.bounds
            val maxX = (bounds.width() - p.width).coerceAtLeast(0)
            val maxY = (bounds.height() - p.height).coerceAtLeast(0)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    downLocalY = ev.y
                    origX = p.x
                    origY = p.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downRawX).toInt()
                    val dy = (ev.rawY - downRawY).toInt()
                    if (!dragged && (abs(dx) > 6 || abs(dy) > 6)) {
                        dragged = true
                    }
                    if (dragged) {
                        val nx = (origX + dx).coerceIn(0, maxX)
                        val ny = (origY + dy).coerceIn(0, maxY)
                        if (nx != p.x || ny != p.y) {
                            p.x = nx
                            p.y = ny
                            runCatching { wm.updateViewLayout(v, p) }
                            pantau.moveWidget(w.ordinal, nx, ny)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragged) {
                        settings.savePosition(w, p.x, p.y)
                    } else if (ev.actionMasked == MotionEvent.ACTION_UP &&
                        w == Widget.PROCESSES &&
                        downLocalY <= 68f
                    ) {
                        // Direct tap on `Processes · All / Apps / Threads` header cycles mode live!
                        cycleProcessesMode()
                    }
                    true
                }
                else -> false
            }
        }
        return (v to p).also { views[w] = it }
    }

    private fun createFallback(w: Widget): Pair<View, WindowManager.LayoutParams> {
        val (x, y) = settings.position(w)
        val p = overlayParams(touchable = false).apply {
            this.x = x
            this.y = y
            fitInsetsTypes = 0
        }
        lateinit var v: View
        v = overlayView(context) {
            WidgetFrame(
                widget = w, overlay = this,
                onDrag = { dx, dy -> p.x += dx.toInt(); p.y += dy.toInt(); runCatching { wm.updateViewLayout(v, p) } },
                onDragEnd = { settings.savePosition(w, p.x, p.y) },
            )
        }
        return (v to p).also { views[w] = it }
    }

    private fun removeAllViews() {
        views.values.forEach { (v, _) -> runCatching { wm.removeViewImmediate(v) } }
        views.clear()
    }

    fun setVisible(visible: Boolean) {
        views.values.forEach { (v, _) ->
            if (!visible) v.visibility = View.GONE
        }
        if (visible) configRev.intValue++
    }

    fun stop() {
        scope?.cancel(); scope = null
        removeAllViews()
        if (busOn) { busOn = false; bus.stop() }
        if (fpsOn) { fpsOn = false; fps.dispose() }
        editing.value = false
        panelOpen.value = false
    }

    private fun CoroutineScope.cancel() = coroutineContext[Job]?.cancel()
}

/**
 * Ultra-lightweight transparent touch hitbox (`setWillNotDraw(true)` during gameplay = 0 draw calls).
 * In edit mode (`WidgetsPage`), draws a single static Material You rounded border via Canvas.
 */
private class HudDragView(context: Context) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val accent = runCatching {
        context.getColor(android.R.color.system_accent1_200)
    }.getOrDefault(0xFFA8C7FA.toInt())

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = (accent and 0x00FFFFFF) or 0x1A000000
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = (accent and 0x00FFFFFF) or 0xD9000000.toInt()
    }
    private val rect = RectF()
    private val path = Path()
    private var showBorder = false
    private var topDocked = false

    init {
        setWillNotDraw(true)
    }

    fun setHighlight(show: Boolean, dockedTop: Boolean) {
        if (showBorder == show && topDocked == dockedTop) return
        showBorder = show
        topDocked = dockedTop
        setWillNotDraw(!show)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!showBorder || width <= 0 || height <= 0) return
        val inset = strokePaint.strokeWidth * 0.5f
        rect.set(inset, inset, width - inset, height - inset)
        val r = (if (topDocked) 10f else 14f) * density
        path.reset()
        val radii = if (topDocked) {
            floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        } else {
            floatArrayOf(r, r, r, r, r, r, r, r)
        }
        path.addRoundRect(rect, radii, Path.Direction.CW)
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
    }
}
