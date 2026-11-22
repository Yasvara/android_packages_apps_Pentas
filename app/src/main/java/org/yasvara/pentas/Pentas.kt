/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas

import android.app.Application
import android.content.Context
import android.os.Process
import androidx.core.content.edit
import dagger.hilt.android.HiltAndroidApp
import org.yasvara.pentas.cache.AppIconCache
import org.yasvara.pentas.cache.BannerBitmapCache
import javax.inject.Inject

/**
 * On-demand Pentas UI and game-session process. Thermal policy and touch orientation live in
 * system_server, and user clock caps are persisted to mi_thermal_engine via system properties,
 * so this process stays unloaded when no game/video session or Pentas screen is open.
 */
@HiltAndroidApp(Application::class)
class Pentas : Hilt_Pentas() {
    @Inject lateinit var banners: BannerBitmapCache
    @Inject lateinit var icons: AppIconCache

    private val recovery by lazy { getSharedPreferences("pentas_recovery", MODE_PRIVATE) }

    /** A stale resource table after an in-place update shows up here; respawn once to reload it. */
    override fun attachBaseContext(base: Context) {
        val ok = runCatching { super.attachBaseContext(base); resources.getString(android.R.string.ok) }.isSuccess
        if (!ok) respawn()
    }

    override fun onCreate() {
        super.onCreate()
        recovery.edit { putInt(KEY_RESTARTS, 0) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            banners.evictAll()
            icons.clear()
        }
    }

    private fun respawn() = runCatching {
        val n = recovery.getInt(KEY_RESTARTS, 0)
        if (n < 3) {
            recovery.edit(commit = true) { putInt(KEY_RESTARTS, n + 1) }
            Process.killProcess(Process.myPid())
        }
    }

    private companion object {
        const val KEY_RESTARTS = "restart_attempts"
    }
}
