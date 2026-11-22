/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.HiltAndroidApp
import org.yasvara.pentas.cache.AppIconCache
import org.yasvara.pentas.cache.BannerBitmapCache
import org.yasvara.pentas.thermal.PerfTuner
import org.yasvara.pentas.thermal.ThermalController
import org.yasvara.pentas.touch.TouchOrientationRelay
import javax.inject.Inject

/**
 * The persistent main process runs only the thermal, clock and touch-orientation control plane;
 * screens and the session sidebar live in ":ui".
 */
@HiltAndroidApp(Application::class)
class Pentas : Hilt_Pentas() {
    @Inject lateinit var thermal: dagger.Lazy<ThermalController>
    @Inject lateinit var perf: dagger.Lazy<PerfTuner>
    @Inject lateinit var banners: BannerBitmapCache
    @Inject lateinit var icons: AppIconCache
    @Inject lateinit var touchOrientation: dagger.Lazy<TouchOrientationRelay>

    private val recovery by lazy { getSharedPreferences("pentas_recovery", MODE_PRIVATE) }

    /** A stale resource table after an in-place update shows up here; respawn once to reload it. */
    override fun attachBaseContext(base: Context) {
        val ok = runCatching { super.attachBaseContext(base); resources.getString(android.R.string.ok) }.isSuccess
        if (!ok) respawn()
    }

    override fun onCreate() {
        super.onCreate()
        recovery.edit { putInt(KEY_RESTARTS, 0) }
        if (getProcessName() != packageName) return
        thermal.get().start()
        touchOrientation.get().start()
        applyGlobalClocks()
    }

    /**
     * Applies the global clock baseline. Two boots in a row that die before [STABLE_MS] drop
     * the baseline, so a bad tuning cannot lock the user out.
     */
    private fun applyGlobalClocks() {
        val attempts = recovery.getInt(KEY_PERF_ATTEMPTS, 0)
        if (attempts >= 2) {
            recovery.edit { putInt(KEY_PERF_ATTEMPTS, 0) }
            perf.get().saveGlobalProfile(PerfTuner.PerfProfile())
            Log.e(TAG, "Global clocks reset after $attempts unstable boots")
            return
        }
        recovery.edit { putInt(KEY_PERF_ATTEMPTS, attempts + 1) }
        perf.get().applyGlobal()
        Handler(Looper.getMainLooper()).postDelayed({ recovery.edit { putInt(KEY_PERF_ATTEMPTS, 0) } }, STABLE_MS)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            banners.evictAll()
            icons.clear()
            Runtime.getRuntime().gc()
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
        const val TAG = "Pentas"
        const val STABLE_MS = 90_000L
        const val KEY_RESTARTS = "restart_attempts"
        const val KEY_PERF_ATTEMPTS = "perf_apply_attempts"
    }
}
