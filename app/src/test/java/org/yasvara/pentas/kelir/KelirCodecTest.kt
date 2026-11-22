/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.kelir

import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The bytes Pentas writes, against framefx's golden sections (tests/golden in
 * frameworks/libs/framefx, copied here and pinned by SHA-256 so a copy that drifts fails).
 */
class KelirCodecTest {

    private fun golden(name: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("kelir/$name")) { name }.readBytes()

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val pinned = mapOf(
        "colour_band.bin" to "a1aea3fedfd151d27b4b601c82be31b13fdc16a59d911a8e17097a5137dd281b",
        "detail_band.bin" to "a183b9a2a42cdd1ed8fb9a46d6340a454e08d312da0b45a9a62bfaf313431643",
        "empty.bin" to "814dd7b9784d57c15b9c2972e9b4fd6cf7e164f8162a934bdb2452a413dab1f7",
        "flags.bin" to "8b7ccdcf4fb6227bcb9da38fa2d2f11a8fe806eb20946d5829d023f8f34cfb5b",
        "inert_nodes.bin" to "8a8a7d8c8b181be7d66f7586b0024930ea90efcfee03d88b633c15c6c8d125ea",
        "max_stack.bin" to "e0d5c2264f6efce111a33e22d631bf8cae2c8dcf77488451f6a90a6fabd6ac5c",
        "screen_band.bin" to "c1ebbf583cfd7905d24962e68707e9fc93b5ddf448ab2d9ea74dcfe58a3887e2",
        "kelir_params.json" to "b89dfda4c0e412c4ab8e00294a0ce0920fe608ba86aa3bf4b0369f7cd19f140a",
    )

    private fun node(kind: Int, intensity: Float = 1f, enabled: Boolean = true, vararg values: Pair<String, Float>): KelirNode {
        var n = KelirNode(kind, enabled, intensity)
        for ((name, value) in values) {
            val i = n.info.params.indexOfFirst { it.name.lowercase().replace(' ', '_').replace('/', '_') == name }
            check(i >= 0) { name }
            n = n.with(i, value)
        }
        return n
    }

    private fun stack(vararg nodes: KelirNode) = KelirStack(nodes.toList())

    // tests/golden/kelir/make.py, VECTORS: the same stacks, in the same order.
    private val vectors = mapOf(
        "empty.bin" to Triple(stack(), KelirCodec.View(), 1),
        "flags.bin" to Triple(stack(), KelirCodec.View(peek = true, split = 0.5f, inspect = true), 0xdeadbeef.toInt()),
        "colour_band.bin" to Triple(stack(
            node(KelirKinds.BRIGHTNESS_CONTRAST, 1f, true, "exposure" to 0.5f, "contrast" to 0.2f, "shadows" to 0.3f, "gamma" to 1.2f),
            node(KelirKinds.COLOR, 0.8f, true, "temperature" to 0.3f, "vibrance" to 0.5f, "tint_colour" to 200f, "tint_intensity" to 0.25f),
            node(KelirKinds.LEVELS, 1f, true, "black" to 0.05f, "white" to 0.95f, "midtones" to 1.1f),
            node(KelirKinds.BLACK_WHITE, 0.4f),
            node(KelirKinds.SEPIA, 0.3f),
            node(KelirKinds.NIGHT_MODE, 0.5f),
            node(KelirKinds.COLORBLIND, 1f, true, "type" to 1f),
        ), KelirCodec.View(), 7),
        "detail_band.bin" to Triple(stack(
            node(KelirKinds.SHARPEN, 0.7f, true, "ignore_film_grain" to 0.5f, "quality" to 1f),
            node(KelirKinds.COLOR, 1f, true, "saturation" to 0.25f),
            node(KelirKinds.DETAILS, 1f, true, "clarity" to 0.4f, "bloom" to 0.3f, "bloom_threshold" to 0.6f),
            node(KelirKinds.PAINTERLY, 1f, true, "brush" to 0.8f, "detail" to 0.2f),
        ), KelirCodec.View(), 8),
        "screen_band.bin" to Triple(stack(
            node(KelirKinds.VIGNETTE, 0.6f, true, "size" to 0.7f, "roundness" to 1f),
            node(KelirKinds.FILM_GRAIN, 0.5f, true, "size" to 2f, "colour" to 0.3f),
            node(KelirKinds.BRIGHTNESS_CONTRAST, 1f, true, "contrast" to -0.25f),
            node(KelirKinds.LETTERBOX, 1f, true, "aspect" to 2.39f),
            node(KelirKinds.RETRO, 1f, true, "pixel_size" to 6f, "colour_levels" to 4f, "scanlines" to 0.8f),
        ), KelirCodec.View(), 9),
        "max_stack.bin" to Triple(stack(
            node(KelirKinds.TILT_SHIFT, 1f, true, "shape" to 1f, "position" to 0.4f, "size" to 0.25f, "softness" to 0.6f, "angle" to -30f, "blur" to 0.9f),
            node(KelirKinds.HALFTONE, 0.75f, true, "dot_size" to 8f, "ink" to 1f),
            node(KelirKinds.SHARPEN, 0.5f),
            node(KelirKinds.OLD_FILM, 1f, true, "dust" to 0.9f, "scratches" to 0.2f, "flicker" to 0.6f),
            node(KelirKinds.WATERCOLOR, 1f, true, "blurriness" to 0.3f, "fuzziness" to 0.7f, "pencil_detail" to 0.9f, "pencil_intensity" to 0.4f),
            node(KelirKinds.VIGNETTE),
            node(KelirKinds.LETTERBOX, 0.5f, true, "aspect" to 1.85f),
            node(KelirKinds.BRIGHTNESS_CONTRAST, 1f, true, "exposure" to -1f, "highlights" to -0.5f),
        ), KelirCodec.View(split = 0.3f), 10),
        "inert_nodes.bin" to Triple(stack(
            node(KelirKinds.BRIGHTNESS_CONTRAST),
            node(KelirKinds.COLOR, 1f, true, "tint_colour" to 120f),
            node(KelirKinds.DETAILS),
            node(KelirKinds.TILT_SHIFT, 1f, true, "blur" to 0f),
            node(KelirKinds.SEPIA, 0f),
            node(KelirKinds.VIGNETTE, 1f, false),
        ), KelirCodec.View(), 11),
    )

    @Test
    fun goldenFilesArePinned() {
        for ((name, hash) in pinned) assertEquals(name, hash, sha256(golden(name)))
    }

    @Test
    fun goldenBytes() {
        for ((name, v) in vectors) {
            val (stack, view, revision) = v
            assertArrayEquals(name, golden(name), KelirCodec.encode(stack, view, revision))
        }
    }

    @Test
    fun paramTableMatchesGoldenJson() {
        val table = JSONArray(String(golden("kelir_params.json")))
        assertEquals(KelirKinds.all.size, table.length())
        for (kind in KelirKinds.all) {
            val k = table.getJSONObject(kind.ordinal)
            assertEquals(k.getString("name"), kind.name)
            assertEquals(k.getString("band"), kind.band.name.lowercase())
            assertEquals(k.getBoolean("style"), kind.style)
            val params = k.getJSONArray("params")
            assertEquals(kind.name, params.length(), kind.params.size)
            for ((i, p) in kind.params.withIndex()) {
                val j = params.getJSONObject(i)
                assertEquals(j.getString("name"), p.name)
                assertEquals(j.getInt("choices"), p.choices)
                assertEquals(j.getDouble("lo").toFloat(), p.lo)
                assertEquals(j.getDouble("default").toFloat(), p.default)
                assertEquals(j.getDouble("hi").toFloat(), p.hi)
                assertEquals(j.getInt("needs"), p.needs)
                if (p.discrete) assertEquals(p.name, p.choices, p.labels.size)
            }
        }
    }

    @Test
    fun defaultsExact() {
        for (kind in KelirKinds.all) {
            for (p in kind.params) {
                assertEquals(0, p.encode(p.default))
                assertEquals(p.default, p.decode(0))
                if (!p.discrete) {
                    assertEquals(p.hi, p.decode(p.encode(p.hi)))
                    assertEquals(p.lo, p.decode(p.encode(p.lo)))
                }
            }
        }
    }

    @Test
    fun roundTrip() {
        for (kind in KelirKinds.all) {
            for (p in kind.params) {
                if (p.discrete) continue
                for (q in -Param.Q_MAX..Param.Q_MAX step 97) {
                    if ((q < 0 && p.lo == p.default) || (q > 0 && p.hi == p.default)) continue
                    assertEquals("${p.name} $q", q, p.encode(p.decode(q)))
                }
            }
        }
    }

    @Test
    fun sizeAtMost176() {
        var s = KelirStack()
        for (k in listOf(KelirKinds.SHARPEN, KelirKinds.DETAILS, KelirKinds.PAINTERLY, KelirKinds.COLOR,
            KelirKinds.VIGNETTE, KelirKinds.FILM_GRAIN, KelirKinds.LETTERBOX, KelirKinds.RETRO)) {
            s = s.add(k)
        }
        assertEquals(8, s.nodes.size)
        assertEquals(176, KelirCodec.encode(s, KelirCodec.View(), 1).size)
    }

    @Test
    fun revisionIncrements() {
        val a = KelirCodec.encode(KelirStack(), KelirCodec.View(), 41)
        assertEquals(41, a[8].toInt())
        assertEquals(0x12345678, KelirCodec.encode(KelirStack(), KelirCodec.View(), 0x12345678).let {
            (it[8].toInt() and 0xff) or (it[9].toInt() and 0xff shl 8) or (it[10].toInt() and 0xff shl 16) or (it[11].toInt() shl 24)
        })
    }

    @Test
    fun peekSplitInspectFlags() {
        assertEquals(0, KelirCodec.encode(KelirStack(), KelirCodec.View(), 1)[2].toInt())
        assertEquals(1, KelirCodec.encode(KelirStack(), KelirCodec.View(peek = true), 1)[2].toInt())
        assertEquals(2, KelirCodec.encode(KelirStack(), KelirCodec.View(split = 0.25f), 1)[2].toInt())
        assertEquals(4, KelirCodec.encode(KelirStack(), KelirCodec.View(inspect = true), 1)[2].toInt())
        val split = KelirCodec.encode(KelirStack(), KelirCodec.View(split = 1f), 1)
        assertTrue(split[4] == 0xff.toByte() && split[5] == 0xff.toByte())
    }
}
