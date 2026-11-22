/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.kelir

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every game's three slots, one JSON file per game in Pentas's own storage: the source of truth.
 * The broker holds only the running slot, encoded.
 */
class KelirStore(private val dir: File) {

    fun load(pkg: String): KelirSlots {
        val file = fileOf(pkg)?.takeIf { it.exists() } ?: return KelirSlots()
        return runCatching { fromJson(JSONObject(file.readText())) }.getOrDefault(KelirSlots())
    }

    /** Written beside the file and renamed over it, so a crash never leaves half a file. */
    fun save(pkg: String, slots: KelirSlots) {
        val file = fileOf(pkg) ?: return
        dir.mkdirs()
        val next = File(dir, "${file.name}.new")
        runCatching {
            next.writeText(toJson(slots).toString())
            if (!next.renameTo(file)) next.delete()
        }
    }

    fun remove(pkg: String) {
        fileOf(pkg)?.delete()
    }

    /** Every game with a stored file. */
    fun games(): List<String> =
        dir.list().orEmpty().filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.sorted()

    private fun fileOf(pkg: String): File? =
        if (pkg.isEmpty() || pkg.contains('/') || pkg.startsWith('.')) null else File(dir, "$pkg.json")

    companion object {
        fun toJson(slots: KelirSlots): JSONObject = JSONObject()
            .put("active", slots.active)
            .put("slots", JSONArray(slots.slots.map { stack ->
                JSONArray(stack.nodes.map { n ->
                    JSONObject()
                        .put("kind", n.kind)
                        .put("enabled", n.enabled)
                        .put("intensity", n.intensity.toDouble())
                        .put("values", JSONArray(n.values.map { it.toDouble() }))
                })
            }))

        /** Strict where it matters: a kind this version does not know is dropped with its node. */
        fun fromJson(json: JSONObject): KelirSlots {
            val slots = json.getJSONArray("slots")
            val stacks = List(KelirSlots.COUNT) { s ->
                var stack = KelirStack()
                val nodes = if (s < slots.length()) slots.getJSONArray(s) else JSONArray()
                for (i in 0 until nodes.length()) {
                    val o = nodes.getJSONObject(i)
                    val kind = o.getInt("kind")
                    if (!KelirKinds.known(kind)) continue
                    val params = KelirKinds[kind].params
                    val values = o.optJSONArray("values") ?: JSONArray()
                    var node = KelirNode(
                        kind,
                        enabled = o.optBoolean("enabled", true),
                        intensity = o.optDouble("intensity", 1.0).toFloat().coerceIn(0f, 1f),
                    )
                    for (p in params.indices) {
                        if (p < values.length()) node = node.with(p, values.getDouble(p).toFloat())
                    }
                    stack = stack.add(node)
                }
                stack
            }
            val active = json.optInt("active", KelirSlots.OFF)
            return KelirSlots(stacks, if (active in 0 until KelirSlots.COUNT) active else KelirSlots.OFF)
        }
    }
}
