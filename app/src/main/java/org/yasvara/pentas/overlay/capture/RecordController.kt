/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.capture

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.yasvara.pentas.overlay.OverlayState
import org.yasvara.pentas.session.ActiveSessionStore
import org.yasvara.pentas.session.SidebarMode
import org.yasvara.rekam.RekamState
import org.yasvara.rekam.client.RekamClient
import org.yasvara.rekam.controls.RecordTarget
import javax.inject.Inject
import javax.inject.Singleton

/** The sidebar's recorder, a client of Rekam, which keeps the settings; in a game it records only the game's task. */
@Singleton
class RecordController @Inject constructor(
    @ApplicationContext context: Context,
    private val state: OverlayState,
    private val session: ActiveSessionStore,
) {
    /** The binder the page's controls share with this rail. */
    val client = RekamClient(context)
    private val scope = MainScope()
    private var job: Job? = null

    /** Mirrors Rekam's state while the panel is open. */
    fun connect() {
        client.connect(capabilities = true)
        job?.cancel()
        job = scope.launch {
            client.state.collect { s ->
                state.recording.value = s.phase != RekamState.IDLE
                state.recordingSinceMs.longValue = s.sinceMs
                state.recordingPaused.value = s.paused
                state.recordedMs.longValue = s.recordedMs
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        client.disconnect()
    }

    /** Idle: starts with Rekam's defaults. Recording: pauses or resumes; [stop] ends it. */
    fun tap() {
        when (client.state.value.phase) {
            RekamState.IDLE -> start(session.mode == SidebarMode.MODE_GAME)
            RekamState.RECORDING -> client.setPaused(!client.state.value.paused)
        }
    }

    fun start(gameOnly: Boolean) = client.start(if (gameOnly) session.currentPackage else null)

    /** What the page records: the game, or the entire screen. */
    fun target(gameOnly: Boolean) = RecordTarget(if (gameOnly) session.currentPackage else null)

    val gameMode: Boolean get() = session.mode == SidebarMode.MODE_GAME

    fun stop() = client.stop()
}
