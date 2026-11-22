/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.video

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.yasvara.pentas.framefx.FramefxBroker
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A video app's generated frames and upscaling, through system_server's framefx broker. Off stays
 * written once the app has been armed: its running process holds this channel, and removing the last
 * section would leave it on a dead one.
 */
@Singleton
class VideoPath @Inject constructor(@ApplicationContext private val context: Context) {

    fun settings(pkg: String): VideoSettings =
        VideoSettings.decode(FramefxBroker.getSection(pkg, VideoSettings.SECTION_ID))

    fun set(pkg: String, settings: VideoSettings): Boolean {
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val w = bounds.width()
        val h = bounds.height()
        return FramefxBroker.setSection(
            pkg, VideoSettings.SECTION_ID, settings.encode(max(w, h), min(w, h)),
        )
    }

    /** The display rates the panel can run, lowest first. */
    fun rates(): List<Int> =
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?.supportedModes.orEmpty().map { it.refreshRate.roundToInt() }.distinct().sorted()
}
