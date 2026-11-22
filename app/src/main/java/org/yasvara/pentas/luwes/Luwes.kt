/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.luwes

import android.content.Context
import android.hardware.display.DisplayManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES20
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.yasvara.pentas.framefx.FramefxBroker
import org.yasvara.pentas.framefx.GameProcess
import org.yasvara.pentas.lapis.LapisSettings
import org.yasvara.pentas.lapis.LapisStatus
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Luwes frame generation, per game, through system_server's framefx broker: it keeps each game's
 * settings, arms the game for its next start, and hands live changes to a game already armed.
 */
@Singleton
class Luwes @Inject constructor(@ApplicationContext private val context: Context) {

    /** The engine is on the system and the GPU has a motion source. Probed once. */
    val available: Deferred<Boolean> by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).async { probe() }
    }

    fun settings(pkg: String): LuwesSettings =
        LuwesSettings.decode(FramefxBroker.getSection(pkg, LuwesSettings.SECTION_ID))

    fun set(pkg: String, settings: LuwesSettings): Boolean =
        FramefxBroker.setSection(pkg, LuwesSettings.SECTION_ID, settings.encode())

    fun clear(pkg: String) = set(pkg, LuwesSettings())

    /** Qualcomm's AFME is on the system; whether the GPU takes it, the game reports. */
    val afmeAvailable: Boolean by lazy { File(AFME).exists() }

    fun lapis(pkg: String): LapisSettings =
        LapisSettings.decode(FramefxBroker.getSection(pkg, LapisSettings.SECTION_ID))

    fun setLapis(pkg: String, settings: LapisSettings): Boolean =
        FramefxBroker.setSection(pkg, LapisSettings.SECTION_ID, settings.encode())

    /** Hide HUD for [pkg]; false where framefx doesn't run the game, which Lapis can't change. */
    fun setHideHud(pkg: String, hide: Boolean): Boolean {
        if (!hide) heldHideHud.edit().remove(pkg).apply()
        val now = lapis(pkg)
        return now.hideHud == hide || setLapis(pkg, now.copy(hideHud = hide))
    }

    /**
     * Hide HUD asked while [pkg] ran unarmed: its section is what arms the next start, so it is
     * kept through this session's end instead of cleared with it.
     */
    fun holdHideHudForRestart(pkg: String) = heldHideHud.edit().putBoolean(pkg, true).apply()

    /** A game session starts: a held Hide HUD now belongs to it, and ends with it. */
    fun onSessionStart(pkg: String) = heldHideHud.edit().remove(pkg).apply()

    /** A game session ends: Hide HUD lasts one session, unless it waits for the next start. */
    fun onSessionEnd(pkg: String) {
        if (!heldHideHud.getBoolean(pkg, false)) setHideHud(pkg, false)
    }

    private val heldHideHud get() = context.getSharedPreferences("hide_hud_held", Context.MODE_PRIVATE)

    fun setWholeScreen(pkg: String, whole: Boolean): Boolean {
        val now = lapis(pkg)
        return now.wholeScreen == whole || setLapis(pkg, now.copy(wholeScreen = whole))
    }

    fun setTextTap(pkg: String, on: Boolean): Boolean {
        val now = lapis(pkg)
        return now.textTap == on || setLapis(pkg, now.copy(textTap = on))
    }

    fun lapisStatus(pkg: String): List<LapisStatus?> =
        FramefxBroker.statusPages(pkg).map(LapisStatus::decode)

    /** Status of every process of [pkg] that opened its channel, i.e. runs armed. */
    fun status(pkg: String): List<LuwesStatus?> = FramefxBroker.statusPages(pkg).map(LuwesStatus::decode)

    fun needsRestart(pkg: String): Boolean = GameProcess.needsRestart(context, pkg)

    fun restart(pkg: String) = GameProcess.restart(context, pkg)

    /** The panel's live refresh rate, which Luwes fills. */
    fun panelHz(): Float = context.getSystemService(DisplayManager::class.java)
        .getDisplay(Display.DEFAULT_DISPLAY)?.mode?.refreshRate ?: 60f

    private fun probe(): Boolean {
        if (!File(ENGINE).exists()) return false
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) {
            return false
        }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val wanted = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
        )
        if (!EGL14.eglChooseConfig(display, wanted, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            return false
        }
        val gl = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        val surface = EGL14.eglCreatePbufferSurface(
            display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        return try {
            EGL14.eglMakeCurrent(display, surface, surface, gl) &&
                GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty().let {
                    "GL_QCOM_motion_estimation" in it || "GL_QCOM_frame_extrapolation" in it
                }
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, gl)
        }
    }

    private companion object {
        const val ENGINE = "/system_ext/lib64/libframefx.so"
        const val AFME = "/system_ext/lib64/libframeestimationVK.so"
    }
}
