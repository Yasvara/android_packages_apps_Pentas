/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.touch

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors the default display's rotation into the touch HAL. Edge and corner rejection are laid
 * out per orientation; without this the panel keeps its portrait zones in landscape games.
 */
@Singleton
class TouchOrientationRelay @Inject constructor(@ApplicationContext context: Context) {

    private val displays = context.getSystemService(DisplayManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var sent = -1

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(id: Int) { if (id == Display.DEFAULT_DISPLAY) push(force = false) }
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
    }

    fun start() {
        if (!XiaomiTouchFeatureClient.isAvailable) return
        displays.registerDisplayListener(listener, main, DisplayManager.EVENT_TYPE_DISPLAY_CHANGED)
        XiaomiTouchFeatureClient.addRestartListener { main.post { push(force = true) } }
        push(force = true)
    }

    private fun push(force: Boolean) {
        val rotation = displays.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return
        if (!force && rotation == sent) return
        if (XiaomiTouchFeatureClient.setTouchMode(XiaomiTouchFeatureClient.MODE_PANEL_ORIENTATION, rotation)) {
            sent = rotation
        } else {
            Log.w(TAG, "Orientation $rotation not applied")
        }
    }

    private companion object {
        const val TAG = "TouchOrientation"
    }
}
