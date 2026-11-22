/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.data.settings

import org.yasvara.pentas.data.SettingsIO
import javax.inject.Inject
import javax.inject.Singleton

/** Per-game touch settings for the vendor touch HAL: super report, response and edge rejection. */
@Singleton
class TouchSettingsStore @Inject constructor(private val io: SettingsIO) {

    companion object {
        const val KEY_TOUCH_SUPER_REPORT = "pentas_touch_super_report"
        const val KEY_TOUCH_EDGE_FILTER_V2 = "pentas_touch_edge_filter"
        const val KEY_TOUCH_STABLE = "pentas_touch_stable"
    }

    fun superReport(pkg: String) = io.getPerAppBoolean(KEY_TOUCH_SUPER_REPORT, pkg, true)
    fun setSuperReport(pkg: String, v: Boolean) = io.putPerApp(KEY_TOUCH_SUPER_REPORT, pkg, v)

    fun stable(pkg: String) = io.getPerAppBoolean(KEY_TOUCH_STABLE, pkg, false)
    fun setStable(pkg: String, v: Boolean) = io.putPerApp(KEY_TOUCH_STABLE, pkg, v)

    fun edgeFilter(pkg: String) =
        io.getPerAppInt(KEY_TOUCH_EDGE_FILTER_V2, pkg, 0).coerceIn(0, 3)

    fun setEdgeFilter(pkg: String, level: Int) =
        io.putPerApp(KEY_TOUCH_EDGE_FILTER_V2, pkg, level.coerceIn(0, 3))

    fun removeAll(pkg: String) =
        listOf(KEY_TOUCH_SUPER_REPORT, KEY_TOUCH_EDGE_FILTER_V2, KEY_TOUCH_STABLE).forEach { io.putPerApp(it, pkg, null) }
}
