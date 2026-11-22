/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.kelir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KelirStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sample = KelirSlots()
        .edit(0, KelirStack().add(KelirNode(KelirKinds.COLOR, intensity = 0.6f).with(2, 45f)).add(KelirKinds.VIGNETTE))
        .edit(2, KelirStack().add(KelirNode(KelirKinds.SHARPEN, enabled = false).with(1, 1f)))
        .select(2)

    @Test
    fun persistsPerGame() {
        val store = KelirStore(tmp.root)
        store.save("com.game.a", sample)
        store.save("com.game.b", KelirSlots().select(0))
        val again = KelirStore(tmp.root)
        assertEquals(sample, again.load("com.game.a"))
        assertEquals(0, again.load("com.game.b").active)
        assertEquals(KelirSlots(), again.load("com.game.none"))
    }

    @Test
    fun listsStoredGames() {
        val store = KelirStore(tmp.root)
        assertEquals(emptyList<String>(), store.games())
        store.save("com.game.b", sample)
        store.save("com.game.a", sample)
        store.save("com.game.c", sample)
        store.remove("com.game.c")
        File(tmp.root, "com.game.d.json.new").writeText("{")
        assertEquals(listOf("com.game.a", "com.game.b"), store.games())
    }

    @Test
    fun unknownKindDroppedOnLoad() {
        val json = KelirStore.toJson(sample)
        json.getJSONArray("slots").getJSONArray(0).getJSONObject(0).put("kind", 99)
        File(tmp.root, "com.game.a.json").writeText(json.toString())
        val loaded = KelirStore(tmp.root).load("com.game.a")
        assertEquals(listOf(KelirKinds.VIGNETTE), loaded.slots[0].nodes.map { it.kind })
        assertEquals(sample.slots[2], loaded.slots[2])
    }

    @Test
    fun corruptFileFallsBackToEmpty() {
        File(tmp.root, "com.game.a.json").writeText("{\"slots\": [")
        assertEquals(KelirSlots(), KelirStore(tmp.root).load("com.game.a"))
    }

    @Test
    fun removeGameClearsKelir() {
        val store = KelirStore(tmp.root)
        store.save("com.game.a", sample)
        store.remove("com.game.a")
        assertFalse(File(tmp.root, "com.game.a.json").exists())
        assertEquals(KelirSlots(), store.load("com.game.a"))
    }

    @Test
    fun refusesPathsThatLeaveItsFolder() {
        val store = KelirStore(File(tmp.root, "kelir"))
        store.save("../escape", sample)
        assertFalse(File(tmp.root, "escape.json").exists())
    }
}
