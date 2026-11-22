/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay

import androidx.compose.runtime.staticCompositionLocalOf
import org.yasvara.pentas.audio.DolbyController
import org.yasvara.pentas.data.AppSettings
import org.yasvara.pentas.data.PerAppSettingStore
import org.yasvara.pentas.data.SystemSettings
import org.yasvara.pentas.overlay.brightness.Brightness
import org.yasvara.pentas.overlay.capture.RecordController
import org.yasvara.pentas.overlay.dock.Shortcuts
import org.yasvara.pentas.overlay.monitor.WidgetOverlay
import org.yasvara.pentas.overlay.tiles.TileRegistry
import org.yasvara.pentas.session.ActiveSessionStore
import org.yasvara.pentas.telemetry.FpsInteractor
import org.yasvara.pentas.telemetry.TelemetryBus
import org.yasvara.pentas.telemetry.network.NetworkTelemetry
import org.yasvara.pentas.thermal.PerfTuner
import org.yasvara.pentas.touch.GameTouchModeManager
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the sidebar's composables read or drive, handed down as one local. */
@Singleton
class Overlay @Inject constructor(
    val state: OverlayState,
    val session: ActiveSessionStore,
    val settings: AppSettings,
    val tiles: TileRegistry,
    val bus: TelemetryBus,
    val fps: FpsInteractor,
    val network: NetworkTelemetry,
    val brightness: Brightness,
    val perApp: PerAppSettingStore,
    val perf: PerfTuner,
    val touch: GameTouchModeManager,
    val dolby: DolbyController,
    val widgets: WidgetOverlay,
    val record: RecordController,
    val media: MediaSource,
    val shortcuts: Shortcuts,
    val system: SystemSettings,
) {
    val pkg: String? get() = session.currentPackage
}

val LocalOverlay = staticCompositionLocalOf<Overlay> { error("No overlay") }
