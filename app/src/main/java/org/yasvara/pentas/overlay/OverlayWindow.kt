/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.yasvara.pentas.ui.theme.sansTypography
import org.yasvara.rekam.controls.OverlayOwner

/** A ComposeView ready for an overlay window, themed with the dynamic dark scheme. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun overlayView(context: Context, content: @Composable () -> Unit): ComposeView {
    val owner = OverlayOwner()
    return ComposeView(context).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        setContent {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                MaterialExpressiveTheme(
                    colorScheme = dynamicDarkColorScheme(context),
                    motionScheme = MotionScheme.expressive(),
                    typography = sansTypography(),
                    content = content,
                )
            }
        }
    }
}


/** Overlay window params: not focusable, laid out over cutouts, minimal post-processing. */
fun overlayParams(
    width: Int = WindowManager.LayoutParams.WRAP_CONTENT,
    height: Int = WindowManager.LayoutParams.WRAP_CONTENT,
    touchable: Boolean = true,
    gravity: Int = Gravity.TOP or Gravity.START,
) = WindowManager.LayoutParams(
    width, height,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
    PixelFormat.TRANSLUCENT,
).apply {
    this.gravity = gravity
    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    preferMinimalPostProcessing = true
}
