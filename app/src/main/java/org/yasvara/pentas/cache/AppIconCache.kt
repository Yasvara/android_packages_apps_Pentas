/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.cache

import android.content.Context
import android.content.pm.PackageManager
import androidx.collection.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cuts the per-second icon resolution in ProcessesMonitorWidget and the
 * repeated icon loads in pickers/docks. O(1) after first hit.
 */
@Singleton
class AppIconCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private data class Entry(val label: String, val icon: ImageBitmap?)

    private val lock = Any()
    private val keysByPkg = HashMap<String, MutableSet<String>>()

    private val cache = object : LruCache<String, Entry>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Entry): Int =
            (value.icon?.let { it.width * it.height * 4 } ?: 0) + 256
    }
    private val pm: PackageManager get() = context.packageManager

    fun label(packageName: String): String = entry(packageName, 96).label

    fun icon(packageName: String, sizePx: Int = 96): ImageBitmap? = entry(packageName, sizePx).icon

    fun isApp(packageName: String): Boolean {
        return try { pm.getApplicationInfo(packageName, 0); true }
        catch (_: Exception) { false }
    }

    private fun entry(packageName: String, sizePx: Int): Entry {
        val key = "$packageName:$sizePx"
        cache.get(key)?.let { return it }
        val built = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            val bmp = pm.getApplicationIcon(appInfo).toBitmap(sizePx, sizePx).asImageBitmap()
            Entry(label, bmp)
        } catch (_: Exception) {
            Entry(packageName, null)
        }
        synchronized(lock) {
            keysByPkg.getOrPut(packageName) { HashSet() }.add(key)
        }
        cache.put(key, built)
        return built
    }

    fun invalidate(packageName: String) {
        val keys = synchronized(lock) { keysByPkg.remove(packageName)?.toList().orEmpty() }
        keys.forEach { cache.remove(it) }
    }

    fun clear() {
        synchronized(lock) { keysByPkg.clear() }
        cache.evictAll()
    }
}
