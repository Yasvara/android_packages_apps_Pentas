/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.touch

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Client for the vendor `ITouchFeature` AIDL HAL on the primary display. */
object XiaomiTouchFeatureClient {

    private const val TAG = "XiaomiTouchFeature"

    // ──────────────────────────────────────────────────────────────────
    // Service constants
    // ──────────────────────────────────────────────────────────────────

    private const val SERVICE_NAME = "vendor.xiaomi.hw.touchfeature.ITouchFeature/default"
    private const val INTERFACE_TOKEN = "vendor.xiaomi.hw.touchfeature.ITouchFeature"

    /** AIDL transact codes (V3) */
    private const val TRANSACT_GET_MODE_CUR_VALUE = 1
    private const val TRANSACT_GET_MODE_DEFAULT_VALUE = 2
    private const val TRANSACT_GET_MODE_MAX_VALUE = 3
    private const val TRANSACT_GET_MODE_MIN_VALUE = 4
    private const val TRANSACT_GET_MODE_VALUE = 5
    private const val TRANSACT_GET_TOUCH_EVENT = 6
    private const val TRANSACT_SET_TOUCH_MODE = 9
    private const val TRANSACT_GET_MODE_WHITELIST = 11
    private const val TRANSACT_SET_MODE_PACKAGE_NAME = 14

    // ──────────────────────────────────────────────────────────────────
    // Touch Mode IDs — Complete list from RE
    // ──────────────────────────────────────────────────────────────────

    /** @section Basic Touch Modes (0-199) */
    const val MODE_WATERPROOF = 100                 // Water resistance sensitivity
    const val MODE_GLOVE = 101                      // Glove mode sensitivity
    const val MODE_SCP_TP_MISTOUCH = 103            // Mistouch prevention
    const val MODE_THP_SENSORHUB = 104              // Touch + sensor hub data
    const val MODE_TICKET_GRAB = 105                // Fast ticket grab mode
    const val MODE_TP_ACTIVE_TIME = 13              // Touch panel active scan interval (ms)

    /** @section Touch Control Modes — these match the kernel enum in
     *  touch-drivers/include/uapi/touch/xiaomi_touch.h exactly. Do not guess
     *  these values; a wrong id silently lands in the driver's `default:` branch
     *  and only shows up as a LOG_ERROR in dmesg. */
    const val MODE_GAME_MODE = 0                    // Touch_Game_Mode — game scan rate and game edge zones
    const val MODE_TUNE_TOUCH_UP_THRESHOLD = 2      // Touch-up threshold (default 2, 0 to 4)
    const val MODE_TUNE_JITTER_TOLERANCE = 3        // Jitter tolerance (default 2, 0 to 4)
    const val MODE_TUNE_AIM_SENSITIVITY = 4         // Aim sensitivity (default 2, 0 to 4)
    const val MODE_TUNE_TAP_STABILITY = 5           // Tap stability (default 2, 0 to 4)
    const val MODE_EXPERT_PRESET = 6                // Touch_Expert_Mode (1..3, default 1)
    const val MODE_TUNE_EDGE_FILTER = 7             // Game edge rejection depth, 0..3; rebuilds zones in game mode
    const val MODE_PANEL_ORIENTATION = 8            // Surface.ROTATION_*; rebuilds zones in game mode
    const val MODE_SUPER_REPORT_RATE = 202          // Touch_Super_Report — see setReportRateBoost
    const val MODE_MOVE_JITTER = 206                // 0 = config locks; 1 = 6 px move, 10 px still

    /** Live panel scan rate in Hz, computed by the HAL. */
    const val MODE_REPORT_RATE = 1011

    const val MODE_TICKET_GRAB_BOOST = 1028         // Ticket grab touch boost
    const val MODE_DYNAMIC_RATE_APPS = 1085         // HTSR: High Touch Sampling Rate
    const val MODE_HOT_AREA_SECTION = 1097          // Game UI hot area section params
    const val MODE_PROXIMITY_MODE = 1106            // Proximity-based touch on/off
    const val MODE_HOT_AREA_FREEFORM = 1109         // Freeform window touch hotspot
    const val MODE_TICKET_GRAB_MODE = 1116          // Ticket grab touch mode
    const val MODE_GAME_UI_LIST = 1118              // Game UI list scene params

    /** @section Game Control Modes (10000-10199) */
    const val MODE_GAME_SUPER_CORE = 10001          // SuperCore: frame boost/duration/buffer
    const val MODE_GAME_GAMEPAD = 10007             // Gamepad: highlight/multi-device
    const val MODE_GAME_PACKAGE_NAME = 10100        // Set current game package (use setModePackageName)
    const val MODE_GAME_START = 10101               // Game start (1) / stop (0)
    const val MODE_SUPER_TOUCH_APP = 10103          // Super touch whitelist (use setModePackageName)
    const val MODE_PENDING_BUFFER_APP = 10104       // Pending buffer whitelist (use setModePackageName)
    const val MODE_SUPER_TOUCH_LEVEL = 10105        // Super touch sensitivity level (0-100)
    const val MODE_PENDING_BUFFER_LEVEL = 10106     // Pending buffer level (0-100)
    const val MODE_GAME_SCENE = 10109               // Game scene change (loading/battle/menu)
    const val MODE_GAME_4D_SCENE = 10110            // 4D vibration scene ID
    const val MODE_NORMAL_REPORT_RATE = 10111       // Normal report rate enable
    const val MODE_NORMAL_REPORT_RATE_APP = 10112   // Normal report rate app list (use setModePackageName)

    /** @section Hot Area Modes (10500-10599) */
    const val MODE_HOT_AREA_APP_SET = 10501         // Hot area app set enable
    const val MODE_HOT_AREA_APP_SET_LIST = 10502    // Hot area app set list (use setModePackageName)
    const val MODE_HOT_AREA_ADAPT = 10503           // Hot area adaptive enable
    const val MODE_HOT_AREA_ADAPT_LIST = 10504      // Hot area adaptive list (use setModePackageName)

    /** @section Vibrator Modes (10600-10699) */
    const val MODE_VIBRATOR = 10602                 // Game vibrator enable
    const val MODE_VIBRATOR_APP = 10603             // Game vibrator app list (use setModePackageName)

    /** @section Pencil Modes (20000+) */
    const val MODE_PENCIL_POSTURE = 20036           // Pencil posture control

    // ──────────────────────────────────────────────────────────────────
    // SuperCore sub-values (sent as value param to MODE_GAME_SUPER_CORE)
    // ──────────────────────────────────────────────────────────────────

    const val SUPER_CORE_FIRSTFRAME_DURATION_ON = 101
    const val SUPER_CORE_FIRSTFRAME_DURATION_OFF = 102
    const val SUPER_CORE_FIRSTFRAME_DROPBUFFER_ON = 103
    const val SUPER_CORE_FIRSTFRAME_DROPBUFFER_OFF = 104
    const val SUPER_CORE_DYNAMIC_DURATION_ON = 105
    const val SUPER_CORE_DYNAMIC_DURATION_OFF = 106
    const val SUPER_CORE_DYNAMIC_DROPBUFFER_ON = 107
    const val SUPER_CORE_DYNAMIC_DROPBUFFER_OFF = 108
    const val SUPER_CORE_SUPERTOUCH_ON = 109
    const val SUPER_CORE_SUPERTOUCH_OFF = 110
    const val SUPER_CORE_FIRSTFRAME_BOOST_ON = 111
    const val SUPER_CORE_FIRSTFRAME_BOOST_OFF = 112
    const val SUPER_CORE_DYNAMIC_BOOST_ON = 113
    const val SUPER_CORE_DYNAMIC_BOOST_OFF = 114
    const val SUPER_CORE_PENDINGBUFFER_ON = 115
    const val SUPER_CORE_PENDINGBUFFER_OFF = 116
    const val SUPER_CORE_VSYNCBUFFER_ON = 117
    const val SUPER_CORE_VSYNCBUFFER_OFF = 118

    // ──────────────────────────────────────────────────────────────────
    // Gamepad sub-values (sent as value param to MODE_GAME_GAMEPAD)
    // ──────────────────────────────────────────────────────────────────

    const val GAMEPAD_ENABLE = 701
    const val GAMEPAD_DISABLE = 702
    const val GAMEPAD_HIGHLIGHT_ON = 703
    const val GAMEPAD_HIGHLIGHT_OFF = 704
    const val GAMEPAD_AIPREDICT_ON = 705
    const val GAMEPAD_AIPREDICT_OFF = 706
    const val GAMEPAD_MULTIDEVICE_ON = 707
    const val GAMEPAD_MULTIDEVICE_OFF = 708

    // ──────────────────────────────────────────────────────────────────
    // Hot Area sub-values
    // ──────────────────────────────────────────────────────────────────

    const val HOT_AREA_ENABLE = 501
    const val HOT_AREA_DISABLE = 502

    // ──────────────────────────────────────────────────────────────────
    // Primary display
    // ──────────────────────────────────────────────────────────────────

    private const val DISPLAY_PRIMARY = 0

    // ──────────────────────────────────────────────────────────────────
    // Capability model
    // ──────────────────────────────────────────────────────────────────

    /** `Touch_Mode_NUM`: the driver ignores modes at or above it. */
    private const val KERNEL_MODE_NUM = 38

    /** Modes above [KERNEL_MODE_NUM] the HAL acts on itself; verified on NT36532. */
    private val HAL_CONSUMED_MODES = setOf(
        MODE_SUPER_REPORT_RATE,
        MODE_MOVE_JITTER,
        MODE_REPORT_RATE,
        MODE_GAME_START,
        MODE_GAME_PACKAGE_NAME,
    )

    /** Whether [modeId] has any effect on this device; the HAL reports success either way. */
    fun isModeSupported(modeId: Int): Boolean =
        modeId < KERNEL_MODE_NUM || modeId in HAL_CONSUMED_MODES

    // ──────────────────────────────────────────────────────────────────
    // Cached service binder
    // ──────────────────────────────────────────────────────────────────

    @Volatile
    private var cachedBinder: IBinder? = null

    private val restartListeners = CopyOnWriteArrayList<() -> Unit>()

    /** Runs [listener] on a binder thread each time the HAL comes back after dying; its state is gone then. */
    fun addRestartListener(listener: () -> Unit) {
        restartListeners += listener
    }

    private fun getService(): IBinder? {
        cachedBinder?.let {
            if (it.isBinderAlive) return it
            cachedBinder = null
        }
        return ServiceManager.getService(SERVICE_NAME)?.also {
            cachedBinder = it
            try {
                it.linkToDeath(::onServiceDied, 0)
            } catch (_: RemoteException) {}
        }
    }

    private fun onServiceDied() {
        cachedBinder = null
        if (restartListeners.isEmpty()) return
        Log.w(TAG, "HAL died; waiting for restart")
        thread(name = "touchfeature-restart", isDaemon = true) {
            ServiceManager.waitForService(SERVICE_NAME)
            if (getService() != null) restartListeners.forEach { it() }
        }
    }

    /**
     * Whether the Xiaomi touch feature HAL is available on this device.
     */
    val isAvailable: Boolean
        get() = getService() != null

    // ──────────────────────────────────────────────────────────────────
    // Core API
    // ──────────────────────────────────────────────────────────────────

    fun setTouchMode(modeId: Int, value: Int): Boolean {
        return setTouchMode(DISPLAY_PRIMARY, modeId, value)
    }

    /** Sets [modeId] on [displayId]; true when the HAL call succeeded. */
    fun setTouchMode(displayId: Int, modeId: Int, value: Int): Boolean {
        val binder = getService() ?: run {
            Log.w(TAG, "setTouchMode($modeId, $value) — service unavailable")
            return false
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(displayId)
            data.writeInt(modeId)
            data.writeInt(value)
            binder.transact(TRANSACT_SET_TOUCH_MODE, data, reply, 0)
            reply.readException()
            val result = reply.readInt()
            if (result != 0) {
                Log.w(TAG, "setTouchMode($modeId, $value) failed: ret=$result")
                false
            } else {
                true
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "setTouchMode($modeId, $value) transact failed", e)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Helper to read an integer from a mode getter transact.
     */
    private fun getModeInt(transactCode: Int, displayId: Int, modeId: Int): Int {
        val binder = getService() ?: return -1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(displayId)
            data.writeInt(modeId)
            if (binder.transact(transactCode, data, reply, 0)) {
                reply.readException()
                reply.readInt()
            } else {
                -1
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "Transact $transactCode failed for mode $modeId", e)
            -1
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun getModeCurValue(modeId: Int): Int = getModeInt(TRANSACT_GET_MODE_CUR_VALUE, DISPLAY_PRIMARY, modeId)

    /**
     * Get an integer array for a specific mode value query.
     */
    fun getModeValue(modeId: Int): IntArray? {
        val binder = getService() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(DISPLAY_PRIMARY)
            data.writeInt(modeId)
            if (binder.transact(TRANSACT_GET_MODE_VALUE, data, reply, 0)) {
                reply.readException()
                reply.createIntArray()
            } else {
                null
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "getModeValue failed for mode $modeId", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Associates a package (or comma-separated list) with [modeId]. */
    fun setModePackageName(modeId: Int, packageName: String): Boolean {
        val binder = getService() ?: run {
            Log.w(TAG, "setModePackageName($modeId) — service unavailable")
            return false
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(DISPLAY_PRIMARY)
            data.writeInt(modeId)
            data.writeString(packageName)
            binder.transact(TRANSACT_SET_MODE_PACKAGE_NAME, data, reply, 0)
            reply.readException()
            Log.d(TAG, "setModePackageName($modeId, $packageName) — success")
            true
        } catch (e: RemoteException) {
            Log.e(TAG, "setModePackageName($modeId) transact failed", e)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Get touch event data from the HAL.
     *
     * @return JSON string with touch event info, or null on failure
     */
    fun getTouchEvent(): String? {
        val binder = getService() ?: return null

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            binder.transact(TRANSACT_GET_TOUCH_EVENT, data, reply, 0)
            reply.readException()
            reply.readString()
        } catch (e: RemoteException) {
            Log.e(TAG, "getTouchEvent failed", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Whitelist JSON for [modeIds], or null. */
    fun getModeWhitelist(vararg modeIds: Int): String? {
        val binder = getService() ?: return null

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(DISPLAY_PRIMARY)
            data.writeIntArray(modeIds)
            binder.transact(TRANSACT_GET_MODE_WHITELIST, data, reply, 0)
            reply.readException()
            reply.readString()
        } catch (e: RemoteException) {
            Log.e(TAG, "getModeWhitelist failed", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // High-level convenience methods
    // ──────────────────────────────────────────────────────────────────

    /**
     * Activate full game touch mode for a package.
     * Equivalent to what Joyose does when a game starts.
     */
    fun onGameStart(packageName: String) {
        setModePackageName(MODE_GAME_PACKAGE_NAME, packageName)
        setTouchMode(MODE_GAME_START, 1)
        // Enable HTSR (high touch sampling rate)
        setTouchMode(MODE_DYNAMIC_RATE_APPS, 1)
        Log.i(TAG, "Game touch mode activated: $packageName")
    }

    /**
     * Deactivate game touch mode.
     * Equivalent to what Joyose does when a game stops.
     */
    fun onGameStop() {
        setTouchMode(MODE_GAME_START, 0)
        setTouchMode(MODE_DYNAMIC_RATE_APPS, 0)
        Log.i(TAG, "Game touch mode deactivated")
    }

    /**
     * Set game scene (loading screen, battle, menu, etc.)
     * Adjusts touch sensitivity based on gameplay context.
     */
    fun setGameScene(sceneId: Int) {
        setTouchMode(MODE_GAME_SCENE, sceneId)
    }

    /**
     * Enable/disable freeform window touch mode.
     * Adjusts touch zones when game is in split/freeform window.
     */
    fun setFreeformMode(enabled: Boolean) {
        setTouchMode(MODE_HOT_AREA_FREEFORM, if (enabled) 1 else 0)
    }

    /**
     * Set game 4D vibration scene.
     */
    fun set4DScene(sceneId: Int) {
        setTouchMode(MODE_GAME_4D_SCENE, sceneId)
    }

}
