/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.dock

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.yasvara.pentas.data.model.AppRef
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App shortcuts on the rail: the user's pinned apps first, then the most recently used apps
 * to fill the free slots.
 */
@Singleton
class Shortcuts @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val pm = context.packageManager
    private val prefs = context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)

    private fun key(mode: Int) = "pinned_$mode"

    fun pinned(mode: Int): List<String> =
        prefs.getString(key(mode), "").orEmpty().split(',').filter { it.isNotEmpty() }

    fun pin(mode: Int, pkg: String) = prefs.edit { putString(key(mode), (pinned(mode) - pkg + pkg).joinToString(",")) }
    fun unpin(mode: Int, pkg: String) = prefs.edit { putString(key(mode), (pinned(mode) - pkg).joinToString(",")) }

    suspend fun load(mode: Int, exclude: String?, slots: Int): List<AppRef> = withContext(Dispatchers.IO) {
        val skip = setOfNotNull(exclude, context.packageName)
        val pins = pinned(mode).filter { it !in skip }
        val fill = if (pins.size >= slots) emptyList() else recent().filter { it !in skip && it !in pins }
        (pins + fill).take(slots).mapNotNull(::ref)
    }

    /** Every launchable app, for the add picker. */
    suspend fun launchable(): List<AppRef> = withContext(Dispatchers.IO) {
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct()
            .filter { it != context.packageName }
            .mapNotNull(::ref).sortedBy { it.label.lowercase() }
    }

    private fun recent(): List<String> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val now = System.currentTimeMillis()
        val stats = runCatching {
            usm.queryAndAggregateUsageStats(now - WINDOW_MS, now)
        }.getOrNull() ?: return emptyList()
        return stats.values.asSequence()
            .filter { it.lastTimeUsed > 0 && pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedByDescending { it.lastTimeUsed }
            .map { it.packageName }.toList()
    }

    private fun ref(pkg: String): AppRef? = runCatching {
        val info = pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        AppRef(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info))
    }.getOrNull()

    private companion object { const val WINDOW_MS = 14L * 24 * 60 * 60 * 1000 }
}
