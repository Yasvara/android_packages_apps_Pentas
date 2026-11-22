/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.kelir

/** A ready-made stack to start a slot from. */
data class KelirPreset(val name: String, val note: String, val stack: KelirStack) {
    /** It holds 3D kinds, which only run where Lapis has the game's depth. */
    val needsDepth: Boolean get() = stack.count(Band.SCENE) > 0

    /** It holds ray-traced kinds, which wait for framefx's ray tracing. */
    val needsRays: Boolean get() = stack.nodes.any { it.kind in KelirKinds.RAYS }
}

object KelirPresets {

    private fun node(kind: Int, intensity: Float = 1f, vararg v: Pair<Int, Float>) =
        v.fold(KelirNode(kind, intensity = intensity)) { n, (p, x) -> n.with(p, x) }

    private fun bc(vararg v: Pair<Int, Float>) = node(KelirKinds.BRIGHTNESS_CONTRAST, 1f, *v)
    private fun color(vararg v: Pair<Int, Float>) = node(KelirKinds.COLOR, 1f, *v)

    private fun stack(vararg nodes: KelirNode) = nodes.fold(KelirStack()) { s, n -> s.add(n) }

    val all: List<KelirPreset> by lazy {
        listOf(
            KelirPreset("Vivid", "Richer colour, a little more punch", stack(
                color(4 to 0.5f, 3 to 0.15f), bc(1 to 0.15f),
            )),
            KelirPreset("Crisp", "Sharper edges and fine detail", stack(
                node(KelirKinds.SHARPEN, 0.6f, 1 to 1f), node(KelirKinds.DETAILS, 1f, 0 to 0.3f), color(4 to 0.15f),
            )),
            KelirPreset("Compete", "Lifted shadows, clear targets", stack(
                node(KelirKinds.SHARPEN, 0.5f), bc(3 to 0.4f, 4 to 1.15f), color(4 to 0.3f),
            )),
            KelirPreset("Cinema", "Graded, framed, a touch of grain", stack(
                bc(1 to 0.2f, 2 to -0.2f), color(0 to 0.15f, 3 to -0.1f),
                node(KelirKinds.VIGNETTE, 1f, 0 to 0.4f), node(KelirKinds.LETTERBOX),
                node(KelirKinds.FILM_GRAIN, 0.3f),
            )),
            KelirPreset("Golden hour", "Warm light, soft highlights", stack(
                color(0 to 0.35f, 1 to 0.05f, 4 to 0.2f), bc(2 to -0.2f, 3 to 0.1f),
                node(KelirKinds.VIGNETTE, 0.6f, 0 to 0.3f),
            )),
            KelirPreset("Cold steel", "Cool, muted and hard", stack(
                color(0 to -0.3f, 3 to -0.2f), bc(1 to 0.25f),
            )),
            KelirPreset("Dreamy", "Glowing highlights, soft colour", stack(
                node(KelirKinds.DETAILS, 1f, 2 to 0.5f, 3 to 0.6f), color(3 to -0.1f, 0 to 0.1f),
                node(KelirKinds.VIGNETTE, 0.5f, 0 to 0.3f),
            )),
            KelirPreset("Faded film", "Lifted blacks, washed colour", stack(
                node(KelirKinds.LEVELS, 1f, 0 to 0.08f), bc(1 to -0.15f), color(3 to -0.25f, 0 to 0.1f),
                node(KelirKinds.FILM_GRAIN, 0.35f),
            )),
            KelirPreset("Night", "Darker and warm for late play", stack(
                node(KelirKinds.NIGHT_MODE, 0.7f), bc(0 to -0.3f),
            )),
            KelirPreset("Mono", "Black and white", stack(
                node(KelirKinds.BLACK_WHITE), bc(1 to 0.25f), node(KelirKinds.FILM_GRAIN, 0.25f),
            )),
            KelirPreset("Noir", "Hard black and white, dark edges", stack(
                node(KelirKinds.BLACK_WHITE), bc(1 to 0.45f, 3 to -0.2f),
                node(KelirKinds.VIGNETTE, 1f, 0 to 0.6f), node(KelirKinds.FILM_GRAIN, 0.3f),
            )),
            KelirPreset("Miniature", "Tilt-shift toy world", stack(
                node(KelirKinds.TILT_SHIFT, 1f, 5 to 0.7f), color(4 to 0.4f), bc(1 to 0.15f),
            )),
            KelirPreset("Painting", "Brush strokes", stack(
                node(KelirKinds.PAINTERLY, 0.8f), color(4 to 0.2f),
            )),
            KelirPreset("Comic", "Print dots and inked edges", stack(
                node(KelirKinds.DETAILS, 1f, 0 to 0.4f), node(KelirKinds.HALFTONE, 0.6f, 0 to 5f, 1 to 1f),
            )),
            KelirPreset("Arcade", "Big pixels and scanlines", stack(
                node(KelirKinds.RETRO, 0.9f), color(3 to 0.2f),
            )),
            KelirPreset("Old film", "Dust, scratches and flicker", stack(
                node(KelirKinds.OLD_FILM), node(KelirKinds.VIGNETTE, 0.7f, 0 to 0.5f),
            )),
            KelirPreset("Depth", "Contact shadows and air in the distance", stack(
                node(KelirKinds.AMBIENT_OCCLUSION, 0.7f), node(KelirKinds.DEPTH_FOG, 0.6f, 1 to 0.2f),
                color(4 to 0.2f),
            )),
            KelirPreset("Bounced light", "Light carried from floors onto walls", stack(
                node(KelirKinds.GLOBAL_ILLUMINATION, 0.8f), node(KelirKinds.AMBIENT_OCCLUSION, 0.4f),
            )),
            KelirPreset("Filmic", "Highlights roll off like film, mid tones kept", stack(
                node(KelirKinds.TONEMAP, 1f, 2 to 0.6f), node(KelirKinds.AMBIENT_OCCLUSION, 0.4f),
                color(4 to 0.15f),
            )),
            KelirPreset("Next-gen", "Soft shadows, bounced light, AgX tones, clean edges", stack(
                node(KelirKinds.AMBIENT_OCCLUSION, 0.6f), node(KelirKinds.GLOBAL_ILLUMINATION, 0.6f),
                node(KelirKinds.TONEMAP, 0.9f, 0 to 1f, 2 to 0.5f), node(KelirKinds.EDGE_SMOOTHING, 0.8f, 1 to 1f),
                node(KelirKinds.SHARPEN, 0.4f, 1 to 1f),
            )),
            KelirPreset("Sunlit", "Shafts through the trees, a deeper sky", stack(
                node(KelirKinds.SUN_SHAFTS, 0.8f), node(KelirKinds.SKY, 1f, 1 to 0.25f, 3 to 0.2f),
                node(KelirKinds.TONEMAP, 0.7f, 2 to 0.5f), color(0 to 0.1f),
            )),
            KelirPreset("Clear skies", "Bluer, richer sky; the ground untouched", stack(
                node(KelirKinds.SKY, 1f, 1 to 0.35f, 3 to -0.2f), node(KelirKinds.DEPTH_FOG, 0.3f, 1 to 0.15f),
            )),
            KelirPreset("Anime ink", "Inked outlines, flat bright colour", stack(
                node(KelirKinds.OUTLINE, 0.9f, 1 to 0.8f), node(KelirKinds.EDGE_SMOOTHING, 0.7f),
                color(3 to 0.25f, 4 to 0.2f), bc(1 to 0.1f),
            )),
            KelirPreset("Smooth edges", "Less shimmer on jagged edges", stack(
                node(KelirKinds.EDGE_SMOOTHING, 1f, 1 to 1f),
            )),
            KelirPreset("Ray-traced", "Traced shadows, reflections and light", stack(
                node(KelirKinds.RT_SHADOWS, 0.8f), node(KelirKinds.RT_REFLECTIONS, 0.7f),
                node(KelirKinds.PATH_TRACED_LIGHT, 0.6f), node(KelirKinds.TONEMAP, 0.8f, 0 to 1f),
            )),
            KelirPreset("Cinematic 3D", "Focus on the subject, graded", stack(
                node(KelirKinds.DEPTH_OF_FIELD, 1f, 2 to 0.4f), node(KelirKinds.AMBIENT_OCCLUSION, 0.5f),
                bc(1 to 0.15f), color(0 to 0.1f, 3 to -0.05f), node(KelirKinds.VIGNETTE, 0.6f, 0 to 0.35f),
            )),
        )
    }

    /**
     * What can run in the game: 3D presets only where [depth] is known to be there, ray-traced
     * ones only once framefx traces rays ([rays]).
     */
    fun usable(depth: Boolean?, rays: Boolean = false): List<KelirPreset> =
        all.filter { (!it.needsDepth || depth != false) && (!it.needsRays || rays) }
}
