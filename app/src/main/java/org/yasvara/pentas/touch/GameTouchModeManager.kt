/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.touch

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import org.yasvara.pentas.data.settings.TouchSettingsStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the game touch profile on the vendor touch HAL: game mode for the whole session, the
 * super-report boost, the response level and the game edge rejection level. Orientation comes from
 * [TouchOrientationRelay].
 */
@Singleton
class GameTouchModeManager @Inject constructor(
    private val touchSettings: TouchSettingsStore,
) {

    companion object {
        private const val TAG = "GameTouchModeManager"

        const val EDGE_OFF = 0
        const val EDGE_LOW = 1
        const val EDGE_MEDIUM = 2
        const val EDGE_HIGH = 3

        /** Panel rate in game mode. */
        const val REPORT_RATE_GAME = 240

        /** Panel rate otherwise — also the NT36532's power-on default. */
        const val REPORT_RATE_NORMAL = 120
    }

    private val client = XiaomiTouchFeatureClient
    private var currentPackage: String? = null
    private var isSessionActive = false

    private val main = Handler(Looper.getMainLooper())

    init {
        client.addRestartListener { main.post { if (isSessionActive) applyUserSettings() } }
    }

    val isAvailable: Boolean get() = client.isAvailable

    /** Whether this device's HAL reports support for boosted panel sampling. */
    val supportsSuperReport: Boolean
        get() = isAvailable && client.isModeSupported(XiaomiTouchFeatureClient.MODE_SUPER_REPORT_RATE)

    /** Session mirrors of the per-app values, shared by Tuner and quick controls. */
    val superReportEnabled: MutableState<Boolean> = mutableStateOf(false)
    val edgeFilterLevel = mutableIntStateOf(EDGE_OFF)
    val fastResponse: MutableState<Boolean> = mutableStateOf(true)

    /** The actual panel rate, read from the driver rather than inferred. */
    val reportRateHz = mutableIntStateOf(REPORT_RATE_NORMAL)

    // ──────────────────────────────────────────────────────────────────
    // Session lifecycle
    // ──────────────────────────────────────────────────────────────────

    fun onGameStart(packageName: String) {
        currentPackage = packageName
        isSessionActive = true
        if (!isAvailable) {
            Log.w(TAG, "Touch HAL unavailable")
            return
        }
        client.setModePackageName(XiaomiTouchFeatureClient.MODE_GAME_PACKAGE_NAME, packageName)
        client.setTouchMode(XiaomiTouchFeatureClient.MODE_GAME_START, 1)
        applyUserSettings()
        Log.i(TAG, "Game touch session started: $packageName")
    }

    fun onGameStop() {
        if (!isSessionActive) return
        if (isAvailable) {
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_GAME_START, 0)
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_TUNE_EDGE_FILTER, EDGE_OFF)
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_MOVE_JITTER, 0)
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_SUPER_REPORT_RATE, 0)
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_GAME_MODE, 0)
            refreshReportRate()
        }
        superReportEnabled.value = false
        edgeFilterLevel.intValue = EDGE_OFF
        fastResponse.value = true
        currentPackage = null
        isSessionActive = false
        Log.i(TAG, "Game touch session stopped")
    }

    /** Re-arm the HAL after the panel resumes. */
    fun onScreenOn() {
        if (!isSessionActive) return
        applyUserSettings()
        Log.d(TAG, "Re-armed touch state after resume")
    }

    /**
     * Applies the current package's values. Game mode goes first: the HAL lays out its game edge
     * zones only when the edge level or orientation arrives while game mode is on.
     */
    fun applyUserSettings() {
        val pkg = currentPackage ?: return
        superReportEnabled.value = touchSettings.superReport(pkg)
        edgeFilterLevel.intValue = touchSettings.edgeFilter(pkg)
        fastResponse.value = !touchSettings.stable(pkg)
        if (!isAvailable) return
        client.setTouchMode(XiaomiTouchFeatureClient.MODE_GAME_MODE, 1)
        client.setTouchMode(XiaomiTouchFeatureClient.MODE_SUPER_REPORT_RATE, if (superReportEnabled.value) 1 else 0)
        client.setTouchMode(XiaomiTouchFeatureClient.MODE_TUNE_EDGE_FILTER, edgeFilterLevel.intValue)
        pushResponse()
        refreshReportRate()
    }

    // ──────────────────────────────────────────────────────────────────
    // Super report
    // ──────────────────────────────────────────────────────────────────

    fun superReport(pkg: String): Boolean = touchSettings.superReport(pkg)

    /** Quick-toggle entry point — applies to whichever game is in session. */
    fun toggleSuperReport(enabled: Boolean) {
        val pkg = currentPackage ?: run {
            Log.w(TAG, "toggleSuperReport with no active session")
            return
        }
        setSuperReport(pkg, enabled)
    }

    fun setSuperReport(pkg: String, enabled: Boolean) {
        touchSettings.setSuperReport(pkg, enabled)
        if (pkg != currentPackage || !isSessionActive) return
        superReportEnabled.value = enabled
        if (isAvailable) {
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_SUPER_REPORT_RATE, if (enabled) 1 else 0)
            refreshReportRate()
        }
        Log.i(TAG, "Super report ${if (enabled) "ON" else "OFF"} for $pkg -> ${reportRateHz.intValue}Hz")
    }

    /** Publish the panel's real scan rate. */
    fun refreshReportRate() {
        if (!isAvailable) {
            reportRateHz.intValue = REPORT_RATE_NORMAL
            return
        }
        val reported = client.getModeCurValue(XiaomiTouchFeatureClient.MODE_REPORT_RATE)
        reportRateHz.intValue = when {
            reported > 0 -> reported
            isSessionActive -> REPORT_RATE_GAME
            else -> REPORT_RATE_NORMAL
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Response
    // ──────────────────────────────────────────────────────────────────

    fun stable(pkg: String): Boolean = touchSettings.stable(pkg)

    /** Quick-toggle entry point — applies to whichever game is in session. */
    fun toggleFastResponse(enabled: Boolean) {
        currentPackage?.let { setStable(it, !enabled) }
    }

    fun setStable(pkg: String, stable: Boolean) {
        touchSettings.setStable(pkg, stable)
        if (pkg != currentPackage || !isSessionActive) return
        fastResponse.value = !stable
        if (isAvailable) pushResponse()
    }

    /** The fast locks live in the panel config; the HAL's level 1 widens them to the stock distances. */
    private fun pushResponse() {
        client.setTouchMode(XiaomiTouchFeatureClient.MODE_MOVE_JITTER, if (fastResponse.value) 0 else 1)
    }

    // ──────────────────────────────────────────────────────────────────
    // Edge rejection
    // ──────────────────────────────────────────────────────────────────

    fun edgeFilter(pkg: String): Int = touchSettings.edgeFilter(pkg)

    fun setEdgeFilter(pkg: String, level: Int) {
        val normalized = level.coerceIn(EDGE_OFF, EDGE_HIGH)
        touchSettings.setEdgeFilter(pkg, normalized)
        if (pkg != currentPackage || !isSessionActive) return
        edgeFilterLevel.intValue = normalized
        if (isAvailable) client.setTouchMode(XiaomiTouchFeatureClient.MODE_TUNE_EDGE_FILTER, normalized)
    }
}
