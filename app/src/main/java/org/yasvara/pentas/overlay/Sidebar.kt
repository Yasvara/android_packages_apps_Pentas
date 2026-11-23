/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.yasvara.pentas.overlay.capture.GameScreenshotUtil
import org.yasvara.pentas.overlay.ui.Panel
import org.yasvara.pentas.overlay.ui.Rail
import org.yasvara.pentas.session.SidebarMode
import org.yasvara.pentas.ui.kit.Kit
import org.yasvara.pentas.ui.tuning.KelirCompare
import org.yasvara.rekam.controls.HideFromCapture
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The in-game sidebar's two windows: the edge handle, always there during a session, and
 * the panel window, added only while open. The panel and the rail sit on the handle's side,
 * the rail against the screen edge.
 */
@Singleton
class Sidebar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val overlay: Overlay,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val state = overlay.state
    private val settings = overlay.settings

    private val handleParams = overlayParams()
    private val panelParams = overlayParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        gravity = Gravity.CENTER,
    ).apply { flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH }

    private var handle: ComposeView? = null
    private var panel: ComposeView? = null
    private var scope: CoroutineScope? = null
    private var snapAnimator: ValueAnimator? = null

    private val onLeft = mutableStateOf(false)
    private val idle = mutableStateOf(false)
    private val handlePressed = mutableStateOf(false)
    private val handleFloating = mutableStateOf(false)
    private val closing = mutableStateOf(false)
    private var mode = SidebarMode.MODE_NONE
    private var pkg: String? = null

    private val goIdle = Runnable { idle.value = true }
    private val forceRemove = Runnable { removePanel() }

    fun start(packageName: String, sessionMode: Int) {
        removePanel()
        snapAnimator?.cancel(); snapAnimator = null
        scope?.cancel(); scope = null
        pkg = packageName
        mode = sessionMode
        state.reset()
        onLeft.value = settings.x < 0
        handleParams.y = settings.y.coerceIn(0, maxHandleY())
        dock()
        if (handle == null) handle = overlayView(context) { Handle() }.also {
            it.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height))
            }
        }
        handle?.let { h -> if (h.isAttachedToWindow) wm.updateViewLayout(h, handleParams) else runCatching { wm.addView(h, handleParams) } }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch { snapshotFlow { settings.stayAwakeState.value }.collect(::keepScreenOn) }
        }
        setGestureLock(mode == SidebarMode.MODE_GAME && settings.lockBottomGesture)
        scheduleIdle()
        GameScreenshotUtil.registerOverlay(hide = { setVisible(false) }, show = { setVisible(true) })
    }

    fun modeChanged(sessionMode: Int) {
        mode = sessionMode
        state.home()
        setGestureLock(mode == SidebarMode.MODE_GAME && settings.lockBottomGesture)
    }

    fun end() {
        snapAnimator?.cancel(); snapAnimator = null
        removePanel()
        handle?.let { runCatching { wm.removeViewImmediate(it) } }
        handle = null
        scope?.cancel(); scope = null
        main.removeCallbacksAndMessages(null)
        setGestureLock(false)
        pkg = null
        mode = SidebarMode.MODE_NONE
    }

    fun onConfigurationChanged() {
        snapAnimator?.cancel(); snapAnimator = null
        removePanel()
        handleFloating.value = false
        handlePressed.value = false
        handleParams.y = handleParams.y.coerceIn(0, maxHandleY())
        dock()
        handle?.let { runCatching { wm.updateViewLayout(it, handleParams) } }
        overlay.widgets.onConfigurationChanged()
    }

    private fun screenWidth() = wm.maximumWindowMetrics.bounds.width()
    private fun screenHeight() = wm.maximumWindowMetrics.bounds.height()
    private fun handleWidthPx() = handle?.width?.takeIf { it > 0 } ?: (32 * context.resources.displayMetrics.density).roundToInt()
    private fun handleHeightPx() = handle?.height?.takeIf { it > 0 } ?: (84 * context.resources.displayMetrics.density).roundToInt()
    private fun maxHandleX() = (screenWidth() - handleWidthPx()).coerceAtLeast(0)
    private fun maxHandleY() = (screenHeight() - handleHeightPx()).coerceAtLeast(0)

    private fun dock() {
        handleParams.gravity = Gravity.TOP or if (onLeft.value) Gravity.START else Gravity.END
        handleParams.x = 0
    }

    /** Detaches from edge gravity into absolute TOP|START coordinates so the handle floats freely. */
    private fun beginFloating() {
        snapAnimator?.cancel(); snapAnimator = null
        if (handleParams.gravity != (Gravity.TOP or Gravity.START)) {
            handleParams.x = if (onLeft.value) 0 else maxHandleX()
            handleParams.gravity = Gravity.TOP or Gravity.START
        }
        handleFloating.value = true
    }

    /** Smoothly springs the floating handle to whichever screen edge is closer, then docks. */
    private fun snapToNearestEdge(onDocked: () -> Unit) {
        val maxX = maxHandleX()
        val startX = handleParams.x.coerceIn(0, maxX)
        val snapLeft = startX + handleWidthPx() / 2 < screenWidth() / 2
        val targetX = if (snapLeft) 0 else maxX
        onLeft.value = snapLeft
        settings.x = if (snapLeft) -1 else 1
        settings.y = handleParams.y.coerceIn(0, maxHandleY())

        snapAnimator?.cancel()
        if (startX == targetX) {
            handleFloating.value = false
            dock()
            handle?.let { runCatching { wm.updateViewLayout(it, handleParams) } }
            onDocked()
            return
        }
        snapAnimator = ValueAnimator.ofInt(startX, targetX).apply {
            duration = SNAP_MS
            interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
            addUpdateListener { anim ->
                handleParams.x = anim.animatedValue as Int
                handle?.let { if (it.isAttachedToWindow) runCatching { wm.updateViewLayout(it, handleParams) } }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    handleFloating.value = false
                    dock()
                    handle?.let { if (it.isAttachedToWindow) runCatching { wm.updateViewLayout(it, handleParams) } }
                    onDocked()
                }
            })
            start()
        }
    }

    private fun keepScreenOn(on: Boolean) {
        val f = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        handleParams.flags = if (on) handleParams.flags or f else handleParams.flags and f.inv()
        handle?.let { if (it.isAttachedToWindow) runCatching { wm.updateViewLayout(it, handleParams) } }
    }

    private fun scheduleIdle() {
        idle.value = false
        main.removeCallbacks(goIdle)
        main.postDelayed(goIdle, IDLE_MS)
    }

    /** `pentas_gesture_lock = "<package>:2"` locks the home swipe for that game only. */
    fun setGestureLock(locked: Boolean) {
        state.gestureLock.value = locked
        val value = if (locked && pkg != null) "$pkg:2" else ""
        Settings.Secure.putStringForUser(context.contentResolver, KEY_GESTURE_LOCK, value, UserHandle.USER_CURRENT)
    }

    private fun setVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        handle?.visibility = if (visible && panel != null) View.GONE else v
        panel?.visibility = v
        overlay.widgets.setVisible(visible)
    }

    // ── handle ──────────────────────────────────────────────────────────

    @Composable
    private fun Handle() {
        val cs = MaterialTheme.colorScheme
        val haptic = LocalHapticFeedback.current
        val density = LocalDensity.current
        val left by onLeft
        val isIdle by idle
        val pressed by handlePressed
        val floating by handleFloating

        val alpha by animateFloatAsState(
            when {
                floating || pressed -> 1f
                isIdle -> 0.42f
                else -> 0.88f
            },
            label = "handleAlpha",
        )
        val pillWidth by animateDpAsState(
            when {
                floating -> 18.dp
                pressed -> 7.dp
                else -> 5.dp
            },
            Kit.fast(),
            label = "handleW",
        )
        val pillHeight by animateDpAsState(
            when {
                floating -> 56.dp
                pressed -> 54.dp
                else -> 48.dp
            },
            Kit.fast(),
            label = "handleH",
        )
        val pillScale by animateFloatAsState(if (floating) 1.06f else 1f, Kit.fast(), label = "handleScale")

        val tapSlopPx = with(density) { 10.dp.toPx() }
        val openSwipePx = with(density) { 22.dp.toPx() }
        val floatTriggerPx = with(density) { 14.dp.toPx() }

        Box(
            Modifier
                .size(32.dp, 84.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downTime = down.uptimeMillis
                        val startLeft = onLeft.value
                        var totalX = 0f
                        var totalY = 0f
                        var isFloating = false
                        var opened = false
                        handlePressed.value = true
                        scheduleIdle()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                handlePressed.value = false
                                when {
                                    isFloating -> snapToNearestEdge {
                                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        scheduleIdle()
                                    }
                                    !opened && abs(totalX) < tapSlopPx && abs(totalY) < tapSlopPx -> {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        showPanel()
                                    }
                                    else -> scheduleIdle()
                                }
                                break
                            }
                            val delta = change.positionChange()
                            if (delta.x != 0f || delta.y != 0f) {
                                change.consume()
                                totalX += delta.x
                                totalY += delta.y
                                val inward = if (startLeft) totalX else -totalX
                                val heldMs = change.uptimeMillis - downTime

                                if (!isFloating && !opened) {
                                    // Quick deliberate inward swipe opens the panel effortlessly.
                                    if (inward >= openSwipePx && abs(totalY) < openSwipePx && heldMs < 240L) {
                                        opened = true
                                        handlePressed.value = false
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        showPanel()
                                        continue
                                    }
                                    // Vertical drag or hold-and-move detaches into smooth floating reposition mode.
                                    if (abs(totalY) >= floatTriggerPx || (heldMs >= 220L && abs(totalX) + abs(totalY) >= tapSlopPx)) {
                                        isFloating = true
                                        beginFloating()
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                }

                                if (isFloating) {
                                    handleParams.x = (handleParams.x + delta.x.roundToInt()).coerceIn(0, maxHandleX())
                                    handleParams.y = (handleParams.y + delta.y.roundToInt()).coerceIn(0, maxHandleY())
                                    handle?.let { runCatching { wm.updateViewLayout(it, handleParams) } }
                                }
                            }
                        }
                    }
                },
            contentAlignment = when {
                floating -> Alignment.Center
                left -> Alignment.CenterStart
                else -> Alignment.CenterEnd
            },
        ) {
            Box(
                Modifier
                    .padding(horizontal = if (floating) 0.dp else 5.dp)
                    .size(pillWidth, pillHeight)
                    .graphicsLayer {
                        this.alpha = alpha
                        scaleX = pillScale
                        scaleY = pillScale
                        shadowElevation = if (floating) 10.dp.toPx() else 2.dp.toPx()
                        shape = CircleShape
                    }
                    .clip(CircleShape)
                    .background(if (floating) cs.primaryContainer else cs.primary)
                    .border(
                        width = if (floating) 1.5.dp else 0.75.dp,
                        color = if (floating) cs.primary else cs.onPrimary.copy(alpha = 0.25f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (floating) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        repeat(3) {
                            Box(Modifier.size(4.dp).clip(CircleShape).background(cs.onPrimaryContainer))
                        }
                    }
                }
            }
        }
    }

    // ── panel ───────────────────────────────────────────────────────────

    private fun showPanel() {
        if (panel != null) return
        closing.value = false
        overlay.brightness.start()
        overlay.fps.start()
        overlay.bus.start()
        overlay.media.start()
        overlay.record.connect()
        state.home()
        val v = overlayView(context) {
            CompositionLocalProvider(LocalOverlay provides overlay) { PanelWindow() }
        }
        v.setOnTouchListener { _, e ->
            // Compare and widget drag-edit leave the rest of the screen interactive.
            if (e.action == MotionEvent.ACTION_OUTSIDE &&
                !state.comparing.value &&
                !overlay.widgets.editing.value
            ) close()
            false
        }
        panelParams.width = WindowManager.LayoutParams.MATCH_PARENT
        panelParams.height = WindowManager.LayoutParams.MATCH_PARENT
        panelParams.gravity = Gravity.CENTER
        panelParams.flags = panelParams.flags or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        runCatching {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            wm.addView(v, panelParams)
            panel = v
            overlay.widgets.panelOpen.value = true
            handle?.visibility = View.GONE
        }.onFailure {
            overlay.widgets.panelOpen.value = false
            releasePanelSources()
        }
        main.removeCallbacks(goIdle)
    }

    /** Animates the panel out; the delayed removal covers a frame clock that never ticks. */
    fun close() {
        if (panel == null) return
        closing.value = true
        main.removeCallbacks(forceRemove)
        main.postDelayed(forceRemove, CLOSE_TIMEOUT_MS)
    }

    private fun removePanel() {
        main.removeCallbacks(forceRemove)
        val v = panel ?: return
        panel = null
        runCatching { wm.removeViewImmediate(v) }
        releasePanelSources()
        state.keyboard.value = false
        state.reset()
        overlay.widgets.editing.value = false
        overlay.widgets.panelOpen.value = false
        if (mode != SidebarMode.MODE_GAME) Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
        if (pkg != null) { handle?.visibility = View.VISIBLE; scheduleIdle() }
    }

    private fun releasePanelSources() {
        overlay.brightness.dispose()
        overlay.fps.dispose()
        overlay.bus.stop()
        overlay.media.stop()
        overlay.record.disconnect()
    }

    private fun updatePanelLayout(focusable: Boolean, editWidgets: Boolean, left: Boolean) {
        val v = panel ?: return
        val nf = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val wo = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        panelParams.flags = if (focusable) panelParams.flags and nf.inv() else panelParams.flags or nf
        if (editWidgets) {
            // Shrink panel to its own bounds so the entire rest of the screen stays open for
            // grabbing and dragging HUD widgets without intercepting touches or closing the panel!
            panelParams.width = WindowManager.LayoutParams.WRAP_CONTENT
            panelParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            panelParams.gravity = Gravity.CENTER_VERTICAL or if (left) Gravity.START else Gravity.END
            panelParams.flags = panelParams.flags and wo.inv()
        } else {
            panelParams.width = WindowManager.LayoutParams.MATCH_PARENT
            panelParams.height = WindowManager.LayoutParams.MATCH_PARENT
            panelParams.gravity = Gravity.CENTER
            panelParams.flags = panelParams.flags or wo
        }
        runCatching { wm.updateViewLayout(v, panelParams) }
    }

    @Composable
    private fun PanelWindow() {
        // Opening the sidebar during a full-screen recording must not show up in the file.
        HideFromCapture()
        val left by onLeft
        val shut by closing
        val keyboard by state.keyboard
        val comparing by state.comparing
        val editing by overlay.widgets.editing
        val page by state.page
        val metric by state.metric
        val np by overlay.media.now
        val compactForEdit = editing && !comparing
        LaunchedEffect(keyboard, compactForEdit, left) {
            updatePanelLayout(keyboard, compactForEdit, left)
        }
        val slide = remember { Animatable(1f) }
        LaunchedEffect(shut) {
            if (shut) {
                slide.animateTo(1f, spring(dampingRatio = 1f, stiffness = 1600f))
                main.post { removePanel() }
            } else slide.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 380f))
        }
        val game = pkg
        if (comparing && game != null) {
            KelirCompare(game, onBack = { state.comparing.value = false }, onDone = { close() })
            return
        }
        val maxScreenH = (LocalConfiguration.current.screenHeightDp - 16).coerceAtLeast(260)
        // Compact Home height when there is no active song card so there is no dead void.
        val compactHome = page == Page.HOME && metric == null && mode == SidebarMode.MODE_GAME && np == null
        val targetHeight = maxScreenH.coerceAtMost(if (compactHome) COMPACT_HOME_DP else FULL_PANEL_DP).dp
        val height by animateDpAsState(targetHeight, Kit.default(), label = "panelH")
        val rowModifier = Modifier
            .padding(horizontal = 8.dp)
            .height(height)
            .graphicsLayer {
                translationX = slide.value * 120.dp.toPx() * if (left) -1 else 1
                alpha = 1f - slide.value
            }
            .pointerInput(Unit) { detectTapGestures { } }

        if (compactForEdit) {
            Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (left) { Rail(::close); Panel(mode) } else { Panel(mode); Rail(::close) }
            }
        } else {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { close() } }) {
                Row(
                    rowModifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (left) { Rail(::close); Panel(mode) } else { Panel(mode); Rail(::close) }
                }
            }
        }
    }

    companion object {
        const val KEY_GESTURE_LOCK = "pentas_gesture_lock"
        private const val IDLE_MS = 3000L
        private const val CLOSE_TIMEOUT_MS = 500L
        private const val SNAP_MS = 240L
        private const val FULL_PANEL_DP = 388
        private const val COMPACT_HOME_DP = 330
    }
}
