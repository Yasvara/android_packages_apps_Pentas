/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.brightness

import android.content.Context
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.Display
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MAX
import com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MIN
import com.android.settingslib.display.BrightnessUtils.convertGammaToLinearFloat
import com.android.settingslib.display.BrightnessUtils.convertLinearToGammaFloat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Display brightness in the same perceptual (gamma) scale as the QS slider, plus auto-brightness mode. */
@Singleton
class Brightness @Inject constructor(@ApplicationContext private val context: Context) {
    private val dm = context.getSystemService(DisplayManager::class.java)
    private val display: Display? get() = dm.getDisplay(Display.DEFAULT_DISPLAY)
    private var observing = false

    /** 0..1 along the slider. */
    val level = mutableFloatStateOf(0f)

    /** Whether system adaptive/auto-brightness is enabled. */
    val auto = mutableStateOf(false)

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    fun refresh() {
        auto.value = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            UserHandle.USER_CURRENT,
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        val info = display?.brightnessInfo ?: return
        val gamma = convertLinearToGammaFloat(info.brightness, info.brightnessMinimum, info.brightnessMaximum)
        level.floatValue = ((gamma - GAMMA_SPACE_MIN).toFloat() / (GAMMA_SPACE_MAX - GAMMA_SPACE_MIN)).coerceIn(0f, 1f)
    }

    fun set(fraction: Float) {
        val info = display?.brightnessInfo ?: return
        level.floatValue = fraction
        val gamma = GAMMA_SPACE_MIN + fraction * (GAMMA_SPACE_MAX - GAMMA_SPACE_MIN)
        dm.setBrightness(
            Display.DEFAULT_DISPLAY,
            convertGammaToLinearFloat(gamma.toInt(), info.brightnessMinimum, info.brightnessMaximum).coerceIn(0f, 1f),
        )
    }

    fun setAuto(enabled: Boolean) {
        auto.value = enabled
        Settings.System.putIntForUser(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (enabled) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            UserHandle.USER_CURRENT,
        )
    }

    fun toggleAuto() = setAuto(!auto.value)

    fun start() {
        if (observing) return
        observing = true
        val cr = context.contentResolver
        cr.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, observer)
        cr.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, observer)
        refresh()
    }

    fun dispose() {
        if (!observing) return
        observing = false
        context.contentResolver.unregisterContentObserver(observer)
    }
}
