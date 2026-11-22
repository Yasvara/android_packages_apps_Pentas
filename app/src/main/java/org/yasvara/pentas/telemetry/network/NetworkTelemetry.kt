/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.telemetry.network

import kotlinx.coroutines.flow.StateFlow
import org.yasvara.pentas.telemetry.NetSnapshot
import org.yasvara.pentas.telemetry.TelemetryBus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zero-overhead network telemetry facade backed directly by native Rust `pantaud`
 * (`TelemetryBus.netSnapshot` — zero background coroutines, zero duplicate state copies,
 * zero `ping` subprocesses!).
 */
@Singleton
class NetworkTelemetry @Inject constructor(
    private val bus: TelemetryBus,
) {
    val snapshot: StateFlow<NetSnapshot> get() = bus.netSnapshot

    fun start() {
        // `pantaud` samples `/proc/net/dev` + passive TCP RTT strictly on demand via TelemetryBus.
    }

    fun stop() {
        // Retains warm StateFlow snapshot until next observation; no threads or sockets to close.
    }
}
