/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.upscale

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.yasvara.pentas.framefx.FramefxBroker
import org.yasvara.pentas.framefx.GameProcess
import javax.inject.Inject
import javax.inject.Singleton

/** Upscaling per game, through system_server's framefx broker. */
@Singleton
class Upscaler @Inject constructor(@ApplicationContext private val context: Context) {

    fun settings(pkg: String): UpscaleSettings =
        UpscaleSettings.decode(FramefxBroker.getSection(pkg, UpscaleSettings.SECTION_ID))

    /** Whether upscaling runs at the game's next frame: auto counts only where Luwes or Kelir arms it. */
    fun on(pkg: String): Boolean {
        val s = settings(pkg)
        return if (s.written) s.on else FramefxBroker.armedFor(pkg)
    }

    /** On arms the game by itself; off is kept only while something else arms it. */
    fun set(pkg: String, settings: UpscaleSettings): Boolean = FramefxBroker.setSection(
        pkg, UpscaleSettings.SECTION_ID,
        if (settings.on || FramefxBroker.armedFor(pkg)) settings.encode() else ByteArray(0),
    )

    fun status(pkg: String): List<UpscaleStatus?> = FramefxBroker.statusPages(pkg).map(UpscaleStatus::decode)

    fun needsRestart(pkg: String): Boolean = GameProcess.needsRestart(context, pkg)
}
