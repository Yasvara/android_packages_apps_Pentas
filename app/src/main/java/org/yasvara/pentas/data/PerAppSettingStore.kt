/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.data

import android.content.Context
import android.os.Parcel
import android.os.ServiceManager
import android.os.UserHandle
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import org.yasvara.pentas.data.settings.TouchSettingsStore
import org.yasvara.pentas.display.DisplayColorManager
import org.yasvara.pentas.luwes.Luwes
import org.yasvara.pentas.session.ActiveSessionStore
import org.yasvara.pentas.thermal.ThermalProfiles
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-game settings stored as `{"<pkg>": value}` maps in Settings.System. Setters apply to the
 * hardware at once when the package is the live session.
 */
@Singleton
class PerAppSettingStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val io: SettingsIO,
    private val session: ActiveSessionStore,
    private val touch: TouchSettingsStore,
    private val luwes: Luwes,
) {
    private val display by lazy { DisplayColorManager.get(context) }
    private fun live(pkg: String) = session.currentPackage == pkg

    /** Render scale; system_server resizes the game's buffers from this key. */
    fun resolution(pkg: String) = io.getPerAppString(KEY_RESOLUTION, pkg, "1.0")
    fun setResolution(pkg: String, factor: String) = io.putPerApp(KEY_RESOLUTION, pkg, factor.takeIf { it != "1.0" })

    fun displayStyle(pkg: String) = io.getPerAppInt(KEY_DISPLAY_STYLE, pkg, 0)
    fun setDisplayStyle(pkg: String, mode: Int) {
        io.putPerApp(KEY_DISPLAY_STYLE, pkg, mode.takeIf { it > 0 })
        if (live(pkg)) display.applyStyle(mode)
    }

    /** Hentak 4D haptics; system_server arms the session game from these keys. */
    fun hentakMode(pkg: String) = io.getPerAppInt(KEY_HENTAK, pkg, HENTAK_OFF)
    fun setHentakMode(pkg: String, mode: Int) = io.putPerApp(KEY_HENTAK, pkg, mode.takeIf { it != HENTAK_OFF })
    fun hentakSensitivity(pkg: String) = io.getPerAppString(KEY_HENTAK_SENS, pkg, "1.0").toFloatOrNull() ?: 1f
    fun setHentakSensitivity(pkg: String, sens: Float) =
        io.putPerApp(KEY_HENTAK_SENS, pkg, "%.1f".format(java.util.Locale.ROOT, sens).takeIf { it != "1.0" })

    fun thermalProfile(pkg: String): Int = io.getPerAppInt(ThermalProfiles.KEY_APP_PROFILES, pkg, 0)
    fun setThermalProfile(pkg: String, index: Int) =
        io.putPerApp(ThermalProfiles.KEY_APP_PROFILES, pkg, index.takeIf { it > 0 })

    /** Forces GPU (client) composition while the game is in front. */
    fun gpuComposition(pkg: String) = pkg in gpuCompositionApps()
    fun setGpuComposition(pkg: String, on: Boolean) {
        val apps = gpuCompositionApps().toMutableSet().apply { if (on) add(pkg) else remove(pkg) }
        Settings.Secure.putStringForUser(context.contentResolver, KEY_GPU_COMPOSITION, apps.joinToString(","), UserHandle.USER_CURRENT)
        if (live(pkg)) forceClientComposition(on)
    }

    private fun gpuCompositionApps() = Settings.Secure.getStringForUser(
        context.contentResolver, KEY_GPU_COMPOSITION, UserHandle.USER_CURRENT,
    ).orEmpty().split(',').filter { it.isNotEmpty() }.toSet()

    /** Applied at session start, cleared at session end. */
    fun applySession(pkg: String) {
        display.applyForGame(displayStyle(pkg))
        if (gpuComposition(pkg)) forceClientComposition(true)
    }

    fun clearSession(pkg: String?) {
        display.restore()
        if (pkg != null && gpuComposition(pkg)) forceClientComposition(false)
    }

    /** SurfaceFlinger debug transaction 1008: disable hardware overlays. */
    private fun forceClientComposition(on: Boolean) = runCatching {
        val sf = ServiceManager.getService("SurfaceFlinger") ?: return@runCatching
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("android.ui.ISurfaceComposer")
            data.writeInt(if (on) 1 else 0)
            sf.transact(1008, data, reply, 0)
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    fun removeAllForPackage(pkg: String) {
        listOf(KEY_RESOLUTION, KEY_DISPLAY_STYLE, ThermalProfiles.KEY_APP_PROFILES, KEY_HENTAK, KEY_HENTAK_SENS).forEach { io.putPerApp(it, pkg, null) }
        touch.removeAll(pkg)
        setGpuComposition(pkg, false)
        luwes.clear(pkg)
    }

    companion object {
        const val KEY_RESOLUTION = "pentas_game_resolution"
        const val KEY_DISPLAY_STYLE = "pentas_display_style"
        const val KEY_GPU_COMPOSITION = "disable_hw_overlays_apps"
        const val KEY_HENTAK = "pentas_hentak"
        const val KEY_HENTAK_SENS = "pentas_hentak_sens"
        const val HENTAK_OFF = 0
        const val HENTAK_ON = 1
        const val HENTAK_STRONG = 2
    }
}
