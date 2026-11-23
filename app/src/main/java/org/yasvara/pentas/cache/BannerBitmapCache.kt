/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.cache

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.collection.LruCache
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Byte-budgeted (24 MB) banner cache; decodes downsampled to RGB_565 off the main thread. */
@Singleton
class BannerBitmapCache @Inject constructor() {

    private val lock = Any()
    private val keysByPath = HashMap<String, MutableSet<String>>()

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /** Sampled fetch. [reqW] is the max width needed on screen (px). */
    fun get(path: String, reqW: Int = 600): Bitmap? {
        val f = File(path)
        if (!f.exists()) return null
        val abs = f.absolutePath
        val key = "$abs:${f.lastModified()}:$reqW"
        cache.get(key)?.let { return it }
        val bmp = decodeSampled(abs, reqW) ?: return null
        synchronized(lock) {
            keysByPath.getOrPut(abs) { HashSet() }.add(key)
        }
        cache.put(key, bmp)
        return bmp
    }

    fun invalidate(path: String) {
        val abs = File(path).absolutePath
        val keys = synchronized(lock) {
            (keysByPath.remove(path).orEmpty() + keysByPath.remove(abs).orEmpty()).toList()
        }
        keys.forEach { cache.remove(it) }
    }

    fun evictAll() {
        synchronized(lock) { keysByPath.clear() }
        cache.evictAll()
    }

    fun trimToSize(maxBytes: Int) = cache.trimToSize(maxBytes)

    private fun decodeSampled(absPath: String, reqW: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(absPath, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) {
                return runCatching { BitmapFactory.decodeFile(absPath) }.getOrNull()
            }
            var sample = 1
            while (w / (sample * 2) >= reqW && h / (sample * 2) >= reqW / 2) sample *= 2
            val exactSample = maxOf(1, minOf(w / reqW.coerceAtLeast(1), h / (reqW / 2).coerceAtLeast(1)))
            if (exactSample > sample) sample = exactSample
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.RGB_565
                inJustDecodeBounds = false
            }
            runCatching { BitmapFactory.decodeFile(absPath, opts) }.getOrNull()
                ?: runCatching { BitmapFactory.decodeFile(absPath) }.getOrNull()
        } catch (_: OutOfMemoryError) {
            try { cache.trimToSize(cache.maxSize() / 2) } catch (_: Exception) {}
            null
        } catch (_: Exception) { null }
    }
}
