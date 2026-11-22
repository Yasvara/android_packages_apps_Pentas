/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.kelir

/** One filter in a stack: its kind, whether it runs, how strongly, and its parameters. */
data class KelirNode(
    val kind: Int,
    val enabled: Boolean = true,
    val intensity: Float = 1f,
    val values: List<Float> = KelirKinds[kind].params.map { it.default },
) {
    val info: Kind get() = KelirKinds[kind]

    fun with(param: Int, value: Float): KelirNode =
        copy(values = values.toMutableList().also { it[param] = info.params[param].clamp(value) })
}

/**
 * A slot's filters in presentation order: the bands 3D, Detail, Colour, Screen in turn, free order
 * inside each. The limits are the engine's cost envelope (framefx kelir/section.cpp): 8 nodes,
 * 3 detail of which 1 style, 4 screen of which 1 Retro, and only colour kinds repeat.
 */
data class KelirStack(val nodes: List<KelirNode> = emptyList()) {

    enum class Refusal(val text: String) {
        FULL("8 filters at most"),
        DETAIL_FULL("3 detail filters at most"),
        STYLE_TAKEN("One painting style at a time"),
        SCREEN_FULL("4 screen filters at most"),
        ONCE("Already in the stack"),
    }

    /** Why `kind` cannot be added, or null when it can. */
    fun refusal(kind: Int): Refusal? {
        val k = KelirKinds[kind]
        if (nodes.size >= MAX_NODES) return Refusal.FULL
        if (k.band != Band.COLOUR && nodes.any { it.kind == kind }) return Refusal.ONCE
        if (k.band == Band.DETAIL && count(Band.DETAIL) >= MAX_DETAIL) return Refusal.DETAIL_FULL
        if (k.style && nodes.any { it.info.style }) return Refusal.STYLE_TAKEN
        if (k.band == Band.SCREEN && count(Band.SCREEN) >= MAX_SCREEN) return Refusal.SCREEN_FULL
        return null
    }

    fun count(band: Band): Int = nodes.count { it.info.band == band }

    /** Adds at the end of its band; unchanged when refused. */
    fun add(node: KelirNode): KelirStack {
        if (refusal(node.kind) != null) return this
        val at = nodes.indexOfLast { it.info.band.ordinal <= node.info.band.ordinal } + 1
        return copy(nodes = nodes.toMutableList().also { it.add(at, node) })
    }

    fun add(kind: Int): KelirStack = add(KelirNode(kind))

    /** Moves a node within its band; a move across bands springs back. */
    fun move(from: Int, to: Int): KelirStack {
        if (from !in nodes.indices || to !in nodes.indices || from == to) return this
        if (nodes[from].info.band != nodes[to].info.band) return this
        return copy(nodes = nodes.toMutableList().also { it.add(to, it.removeAt(from)) })
    }

    fun remove(index: Int): KelirStack =
        if (index in nodes.indices) copy(nodes = nodes.filterIndexed { i, _ -> i != index }) else this

    fun replace(index: Int, node: KelirNode): KelirStack =
        if (index in nodes.indices && node.kind == nodes[index].kind) {
            copy(nodes = nodes.toMutableList().also { it[index] = node })
        } else {
            this
        }

    companion object {
        const val MAX_NODES = 8
        const val MAX_DETAIL = 3
        const val MAX_SCREEN = 4
    }
}

/** A game's three slots and which one runs; Off is [OFF]. */
data class KelirSlots(
    val slots: List<KelirStack> = List(COUNT) { KelirStack() },
    val active: Int = OFF,
) {
    val running: KelirStack? get() = slots.getOrNull(active)

    fun select(slot: Int): KelirSlots = copy(active = if (slot in 0 until COUNT) slot else OFF)

    /** The sidebar's one-tap cycle: the next slot holding filters, then Off. */
    fun cycle(): KelirSlots {
        val next = (active + 1 until COUNT).firstOrNull { slots[it].nodes.isNotEmpty() }
        return copy(active = next ?: OFF)
    }

    fun edit(slot: Int, stack: KelirStack): KelirSlots =
        copy(slots = slots.toMutableList().also { it[slot] = stack })

    fun reset(slot: Int): KelirSlots = edit(slot, KelirStack())

    fun duplicate(from: Int, to: Int): KelirSlots = edit(to, slots[from])

    /** Another game's slot into one of these. */
    fun copyFrom(other: KelirSlots, from: Int, to: Int): KelirSlots = edit(to, other.slots[from])

    companion object {
        const val COUNT = 3
        const val OFF = -1
    }
}
