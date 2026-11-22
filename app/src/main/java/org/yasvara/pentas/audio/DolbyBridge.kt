/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.audio

import com.android.server.yasvara.nada.Nada
import kotlin.math.roundToInt

/**
 * Dolby through Nada: the Dolby settings of the device that is playing. A game's own Dolby
 * settings live in its Nada profile and the system applies them while it is in front.
 */
object DolbyBridge {

    private const val ON = "dolby.on"
    private const val PROFILE = "dolby.profile"

    const val PROFILE_DYNAMIC = 0
    const val PROFILE_MOVIE = 1
    const val PROFILE_MUSIC = 2
    const val PROFILE_CUSTOM = 3

    private fun nada() = runCatching { Nada.get() }.getOrNull()

    private fun values(): Map<String, String> =
        runCatching { nada()?.let { Nada.decode(it.getProfile(it.route)) } }.getOrNull() ?: emptyMap()

    /** False on a phone without the Dolby effect; the toolbox hides its Dolby controls. */
    fun isAvailable(): Boolean =
        runCatching { Nada.Schema.parse(nada()?.schema).params.containsKey(ON) }.getOrDefault(false)

    fun isEnabled(): Boolean = values()[ON] != "0"

    fun currentProfile(): Int = values()[PROFILE]?.toFloatOrNull()?.roundToInt() ?: PROFILE_DYNAMIC

    fun setEnabled(enabled: Boolean) = put(ON to if (enabled) "1" else "0")

    fun setProfile(profile: Int) = put(ON to "1", PROFILE to profile.toString())

    private fun put(vararg kv: Pair<String, String>) {
        runCatching {
            val n = nada() ?: return
            val route = n.route
            n.setProfile(route, Nada.encode(Nada.decode(n.getProfile(route)) + kv))
        }
    }
}
