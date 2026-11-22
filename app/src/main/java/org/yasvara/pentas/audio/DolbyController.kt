/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.audio

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/** Observable Dolby state of the playing device, shared by every surface that shows it. */
@Singleton
class DolbyController @Inject constructor() {
    data class Snapshot(val available: Boolean, val enabled: Boolean, val profile: Int)

    private val io = Executors.newSingleThreadExecutor()
    private val _state = mutableStateOf(
        Snapshot(
            available = DolbyBridge.isAvailable(),
            enabled = DolbyBridge.isEnabled(),
            profile = DolbyBridge.currentProfile(),
        )
    )

    /** Read this in a composition to subscribe; read [isAvailable] elsewhere. */
    val state: Snapshot get() = _state.value
    val isAvailable: Boolean get() = _state.value.available
    val enabledState: State<Boolean> = derivedStateOf { _state.value.enabled }

    /** Settings and the output device change it behind our back; call when a surface opens. */
    fun refresh() {
        io.execute {
            _state.value = _state.value.copy(
                enabled = DolbyBridge.isEnabled(),
                profile = DolbyBridge.currentProfile(),
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(enabled = enabled)
        io.execute { DolbyBridge.setEnabled(enabled) }
    }

    /** Selecting a profile also turns Dolby on. */
    fun setProfile(profile: Int) {
        _state.value = _state.value.copy(enabled = true, profile = profile)
        io.execute { DolbyBridge.setProfile(profile) }
    }
}
