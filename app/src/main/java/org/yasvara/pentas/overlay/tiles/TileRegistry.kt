/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.tiles

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import dagger.hilt.android.qualifiers.ApplicationContext
import org.yasvara.pentas.R
import org.yasvara.pentas.audio.DolbyController
import org.yasvara.pentas.data.AppSettings
import org.yasvara.pentas.kelir.Kelir
import org.yasvara.pentas.luwes.Luwes
import org.yasvara.pentas.overlay.OverlayState
import org.yasvara.pentas.overlay.Page
import org.yasvara.pentas.overlay.TunerTab
import org.yasvara.pentas.overlay.capture.ScreenshotUtil
import org.yasvara.pentas.session.ActiveSessionStore
import org.yasvara.pentas.touch.GameTouchModeManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the home can show as a quick toggle (one connected row of [TOGGLE_SLOTS]) or a
 * tool (rows of four, paged). The user picks or drag-reorders both lists; defaults cover the common case.
 */
@Singleton
class TileRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
    private val state: OverlayState,
    private val dolby: DolbyController,
    private val touch: GameTouchModeManager,
    private val qsTileCatalog: QsTileCatalog,
    private val kelir: Kelir,
    private val luwes: Luwes,
    private val session: ActiveSessionStore,
) {
    private lateinit var all: List<TileAction>
    private var byId: Map<String, TileAction> = emptyMap()
    private val toggleIds = mutableStateListOf<String>()
    private val toolIds = mutableStateListOf<String>()

    val pool: List<TileAction> get() = all
    val toggles: List<TileAction> get() = toggleIds.mapNotNull(::find)
    val tools: List<TileAction> get() = toolIds.mapNotNull(::find)

    fun find(id: String): TileAction? = byId[id]

    fun init() {
        all = build()
        byId = all.associateBy { it.id }
        toggleIds.clear()
        toggleIds.addAll(appSettings.quickToggleOrder.ifEmpty { DEFAULT_TOGGLES }.filter { it in byId }.take(TOGGLE_SLOTS))
        toolIds.clear()
        toolIds.addAll(appSettings.tileOrder.ifEmpty { DEFAULT_TOOLS }.filter { it in byId })
    }

    fun setToggles(ids: List<String>) {
        toggleIds.clear()
        toggleIds.addAll(ids.distinct().filter { it in byId }.take(TOGGLE_SLOTS))
        appSettings.quickToggleOrder = toggleIds.toList()
    }

    fun setTools(ids: List<String>) {
        toolIds.clear()
        toolIds.addAll(ids.distinct().filter { it in byId })
        appSettings.tileOrder = toolIds.toList()
    }

    fun moveToggle(from: Int, to: Int, persist: Boolean = false) {
        if (from !in toggleIds.indices || to !in toggleIds.indices || from == to) return
        toggleIds.add(to, toggleIds.removeAt(from))
        if (persist) appSettings.quickToggleOrder = toggleIds.toList()
    }

    fun moveTool(fromId: String, toId: String, persist: Boolean = false) {
        val from = toolIds.indexOf(fromId)
        val to = toolIds.indexOf(toId)
        if (from < 0 || to < 0 || from == to) return
        toolIds.add(to, toolIds.removeAt(from))
        if (persist) appSettings.tileOrder = toolIds.toList()
    }

    fun persistOrder() {
        appSettings.quickToggleOrder = toggleIds.toList()
        appSettings.tileOrder = toolIds.toList()
    }

    fun reset() {
        setToggles(DEFAULT_TOGGLES.filter { it in byId })
        setTools(DEFAULT_TOOLS.filter { it in byId })
    }

    private fun build(): List<TileAction> = buildList {
        val wifi = context.getSystemService(android.net.wifi.WifiManager::class.java)
        val tel = context.getSystemService(android.telephony.TelephonyManager::class.java)
        add(WifiTile("wifi", context.getString(R.string.tile_wifi), R.drawable.sym_wifi, wifi) { state.open(Page.WIFI) })
        add(MobileDataTile("mobile_data", context.getString(R.string.tile_mobile_data), R.drawable.sym_signal_cellular_alt, tel))
        add(BluetoothTile("bluetooth", context.getString(R.string.tile_bluetooth), R.drawable.sym_bluetooth, context) {
            state.open(Page.BLUETOOTH)
        })
        if (touch.supportsSuperReport) {
            add(ToggleableTile("super_report", context.getString(R.string.tile_super_touch), R.drawable.sym_touch_app,
                touch.superReportEnabled, touch::toggleSuperReport) { state.tuner(TunerTab.TOUCH) })
        }
        if (touch.isAvailable) {
            add(ToggleableTile("fast_touch", context.getString(R.string.tile_fast_touch), R.drawable.sym_bolt,
                touch.fastResponse, touch::toggleFastResponse) { state.tuner(TunerTab.TOUCH) })
        }
        add(DndTile("dnd", context.getString(R.string.tile_dnd), R.drawable.sym_do_not_disturb_on, context))

        add(FixedActionTile("screenshot", context.getString(R.string.tile_screenshot), R.drawable.sym_screenshot) {
            ScreenshotUtil.takeScreenshot(context)
        })
        add(ToggleableTile("stay_awake", context.getString(R.string.tile_stay_awake), R.drawable.sym_coffee,
            appSettings.stayAwakeState, { on -> appSettings.stayAwake = on }))
        add(FixedActionTile("widgets", context.getString(R.string.tile_widgets), R.drawable.sym_widgets) {
            state.open(Page.WIDGETS)
        })
        add(KelirTile("kelir", context.getString(R.string.tile_kelir), R.drawable.sym_palette, kelir,
            { session.currentPackage }) { state.tuner(TunerTab.KELIR) })
        add(HideHudTile("hide_hud", context.getString(R.string.tile_hide_hud), R.drawable.sym_visibility_off, luwes,
            { session.currentPackage }) { restart ->
            val text = if (restart) R.string.hide_hud_restart else R.string.hide_hud_unavailable
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
        })
        add(ToggleableTile("lock_gesture", context.getString(R.string.tile_lock_gesture), R.drawable.sym_lock,
            state.gestureLock, { state.onGestureLock(it) }))
        if (dolby.isAvailable) {
            add(ToggleableTile("dolby", context.getString(R.string.tile_dolby), R.drawable.sym_graphic_eq,
                dolby.enabledState, dolby::setEnabled) { state.tuner(TunerTab.SOUND) })
        }
        add(ToggleableTile("stats_record", context.getString(R.string.tile_stats_record), R.drawable.sym_monitoring,
            state.statsRecording, { state.onStatsRecord(it) }))

        addSystemTiles()
        qsTileCatalog.discoveredTiles.forEach { d ->
            add(QsServiceTile("qs:" + d.componentName.flattenToString(), d.label, 0, qsTileCatalog.groupOf(d), d, context))
        }
    }

    /** Platform toggles offered in the pool; never placed by default. */
    private fun MutableList<TileAction>.addSystemTiles() {
        if (hasFlashlightSupport(context)) {
            add(FlashlightTile("flashlight", context.getString(R.string.tile_flashlight),
                R.drawable.sym_flashlight_on, context))
        }
        add(AirplaneTile("airplane", context.getString(R.string.tile_airplane_mode),
            R.drawable.sym_flight, context))
        add(AutoRotateTile("auto_rotate", context.getString(R.string.tile_auto_rotate),
            R.drawable.sym_screen_rotation_up, context))
        add(RotationLockTile("rotation_lock", context.getString(R.string.tile_rotation_lock),
            R.drawable.sym_rotate_right, context))
        add(LocationTile("location", context.getString(R.string.tile_location),
            R.drawable.sym_location_on, context))
        if (hasNfcSupport(context)) {
            add(NfcTile("nfc", context.getString(R.string.tile_nfc),
                R.drawable.sym_nfc, context))
        }
        add(HotspotTile("hotspot", context.getString(R.string.tile_hotspot),
            R.drawable.sym_wifi_tethering, context))
        add(BatterySaverTile("battery_saver", context.getString(R.string.tile_battery_saver),
            R.drawable.sym_battery_saver, context))
        add(DarkModeTile("dark_mode", context.getString(R.string.tile_dark_mode),
            R.drawable.sym_dark_mode, context))
        add(ColorInversionTile("color_inversion", context.getString(R.string.tile_color_inversion),
            R.drawable.sym_invert_colors, context))
        add(NightLightTile("night_light", context.getString(R.string.tile_night_light),
            R.drawable.sym_nights_stay, context))
        add(DataSaverTile("data_saver", context.getString(R.string.tile_data_saver),
            R.drawable.sym_data_saver_on, context))
        add(UsbTetherTile("usb_tether", context.getString(R.string.tile_usb_tether),
            R.drawable.sym_usb, context))
        add(CastTile("cast", context.getString(R.string.tile_cast),
            R.drawable.sym_cast, context))
    }

    companion object {
        const val TOGGLE_SLOTS = 5
        val DEFAULT_TOGGLES = listOf("wifi", "mobile_data", "bluetooth", "super_report", "dnd")
        val DEFAULT_TOOLS = listOf("screenshot", "stay_awake", "widgets", "kelir", "hide_hud", "lock_gesture", "dolby", "stats_record", "fast_touch")
    }
}
