/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.kelir

import kotlin.math.roundToInt

/**
 * Where a kind runs in the game's frame, in the order they run: the game's 3D before its HUD with
 * its depth, neighbourhood, colour (one LUT), screen.
 */
enum class Band { SCENE, DETAIL, COLOUR, SCREEN }

/**
 * One parameter of a kind. Continuous ones travel as s16 codes piecewise around the default, so
 * the default is exactly 0; discrete ones as their choice index.
 */
data class Param(
    val name: String,
    val choices: Int = 0,
    val lo: Float = 0f,
    val default: Float = 0f,
    val hi: Float = 0f,
    /** Index of a parameter that must be off its default for this one to matter, or -1. */
    val needs: Int = -1,
    /** Labels of the choices, for discrete parameters. */
    val labels: List<String> = emptyList(),
) {
    val discrete: Boolean get() = choices > 0

    fun decode(q: Int): Float {
        if (discrete) return q.toFloat()
        if (q == Q_MAX) return hi
        if (q == -Q_MAX) return lo
        val t = q.toFloat() / Q_MAX
        val v = if (q >= 0) default + t * (hi - default) else default + t * (default - lo)
        return v.coerceIn(lo, hi)
    }

    /** Nearest code, clamped into range; exact for the default and the ends. */
    fun encode(value: Float): Int {
        if (discrete) return value.roundToInt().coerceIn(0, choices - 1)
        if (value >= default) {
            if (hi == default) return 0
            return (((value - default) / (hi - default)).coerceAtMost(1f) * Q_MAX).roundToInt()
        }
        if (lo == default) return 0
        return -(((default - value) / (default - lo)).coerceAtMost(1f) * Q_MAX).roundToInt()
    }

    fun clamp(value: Float): Float = if (discrete) value.roundToInt().coerceIn(0, choices - 1).toFloat() else value.coerceIn(lo, hi)

    companion object {
        const val Q_MAX = 32767
    }
}

data class Kind(
    val ordinal: Int,
    val name: String,
    val band: Band,
    val style: Boolean,
    val params: List<Param>,
)

/**
 * The Kelir kinds, in wire order. Mirrors frameworks/libs/framefx/kelir/params.cpp; both are held
 * to the golden kelir_params.json.
 */
object KelirKinds {
    const val BRIGHTNESS_CONTRAST = 0
    const val COLOR = 1
    const val LEVELS = 2
    const val BLACK_WHITE = 3
    const val SEPIA = 4
    const val NIGHT_MODE = 5
    const val COLORBLIND = 6
    const val SHARPEN = 7
    const val DETAILS = 8
    const val TILT_SHIFT = 9
    const val PAINTERLY = 10
    const val WATERCOLOR = 11
    const val VIGNETTE = 12
    const val FILM_GRAIN = 13
    const val LETTERBOX = 14
    const val OLD_FILM = 15
    const val RETRO = 16
    const val HALFTONE = 17
    const val AMBIENT_OCCLUSION = 18
    const val DEPTH_FOG = 19
    const val DEPTH_OF_FIELD = 20
    const val GLOBAL_ILLUMINATION = 21
    const val TONEMAP = 22
    const val SKY = 23
    const val SUN_SHAFTS = 24
    const val OUTLINE = 25
    const val EDGE_SMOOTHING = 26
    const val RT_SHADOWS = 27
    const val RT_REFLECTIONS = 28
    const val PATH_TRACED_LIGHT = 29

    /** Kinds that trace rays through the game's scene: they wait for framefx's ray tracing. */
    val RAYS = setOf(RT_SHADOWS, RT_REFLECTIONS, PATH_TRACED_LIGHT)

    private fun c(name: String, lo: Float, default: Float, hi: Float, needs: Int = -1) =
        Param(name, 0, lo, default, hi, needs)

    private fun d(name: String, vararg labels: String) = Param(name, labels.size, labels = labels.toList())

    val all: List<Kind> = listOf(
        Kind(BRIGHTNESS_CONTRAST, "Brightness/Contrast", Band.COLOUR, false, listOf(
            c("Exposure", -2f, 0f, 2f), c("Contrast", -1f, 0f, 1f), c("Highlights", -1f, 0f, 1f),
            c("Shadows", -1f, 0f, 1f), c("Gamma", 0.5f, 1f, 2f),
        )),
        Kind(COLOR, "Color", Band.COLOUR, false, listOf(
            c("Temperature", -1f, 0f, 1f), c("Tint", -1f, 0f, 1f), c("Hue", -180f, 0f, 180f),
            c("Saturation", -1f, 0f, 1f), c("Vibrance", -1f, 0f, 1f),
            c("Tint colour", 0f, 0f, 360f, needs = 6), c("Tint intensity", 0f, 0f, 1f),
        )),
        Kind(LEVELS, "Levels", Band.COLOUR, false, listOf(
            c("Black", 0f, 0f, 0.5f), c("White", 0.5f, 1f, 1f), c("Midtones", 0.5f, 1f, 2f),
            c("Brightness", -0.5f, 0f, 0.5f),
        )),
        Kind(BLACK_WHITE, "Black & White", Band.COLOUR, false, emptyList()),
        Kind(SEPIA, "Sepia", Band.COLOUR, false, emptyList()),
        Kind(NIGHT_MODE, "Night Mode", Band.COLOUR, false, emptyList()),
        Kind(COLORBLIND, "Colorblind", Band.COLOUR, false, listOf(d("Type", "Protan", "Deutan", "Tritan"))),
        Kind(SHARPEN, "Sharpen", Band.DETAIL, false, listOf(
            c("Ignore film grain", 0f, 0f, 1f), d("Quality", "Standard", "Plus"),
        )),
        Kind(DETAILS, "Details", Band.DETAIL, false, listOf(
            c("Clarity", -1f, 0f, 1f), c("HDR toning", 0f, 0f, 1f), c("Bloom", 0f, 0f, 1f),
            c("Bloom threshold", 0.2f, 0.75f, 1f, needs = 2),
        )),
        Kind(TILT_SHIFT, "Tilt-Shift", Band.DETAIL, false, listOf(
            d("Shape", "Band", "Radial"), c("Position", 0f, 0.5f, 1f), c("Size", 0.05f, 0.3f, 1f),
            c("Softness", 0f, 0.5f, 1f), c("Angle", -90f, 0f, 90f), c("Blur", 0f, 0.5f, 1f),
        )),
        Kind(PAINTERLY, "Painterly", Band.DETAIL, true, listOf(c("Brush", 0f, 0.5f, 1f), c("Detail", 0f, 0.5f, 1f))),
        Kind(WATERCOLOR, "Watercolor", Band.DETAIL, true, listOf(
            c("Blurriness", 0f, 0.5f, 1f), c("Fuzziness", 0f, 0.5f, 1f),
            c("Pencil detail", 0f, 0.5f, 1f), c("Pencil intensity", 0f, 0.5f, 1f),
        )),
        Kind(VIGNETTE, "Vignette", Band.SCREEN, false, listOf(c("Size", 0f, 0.5f, 1f), c("Roundness", 0f, 0.5f, 1f))),
        Kind(FILM_GRAIN, "Film Grain", Band.SCREEN, false, listOf(c("Size", 0.5f, 1f, 3f), c("Colour", 0f, 0f, 1f))),
        Kind(LETTERBOX, "Letterbox", Band.SCREEN, false, listOf(c("Aspect", 1.33f, 2.39f, 3f))),
        Kind(OLD_FILM, "Old Film", Band.SCREEN, false, listOf(
            c("Dust", 0f, 0.5f, 1f), c("Scratches", 0f, 0.5f, 1f), c("Flicker", 0f, 0.3f, 1f),
        )),
        Kind(RETRO, "Retro", Band.SCREEN, false, listOf(
            c("Pixel size", 1f, 4f, 16f), c("Colour levels", 2f, 8f, 32f), c("Scanlines", 0f, 0.5f, 1f),
        )),
        Kind(HALFTONE, "Halftone", Band.SCREEN, false, listOf(c("Dot size", 2f, 6f, 16f), d("Ink", "Mono", "CMYK"))),
        Kind(AMBIENT_OCCLUSION, "Ambient occlusion", Band.SCENE, false, listOf(c("Radius", 0.1f, 0.6f, 3f))),
        Kind(DEPTH_FOG, "Depth fog", Band.SCENE, false, listOf(
            c("Start", 0f, 30f, 500f), c("Density", 0f, 0.3f, 1f), c("Hue", 0f, 210f, 360f),
            c("Saturation", 0f, 0.15f, 1f), c("Brightness", 0f, 0.85f, 1f),
        )),
        Kind(DEPTH_OF_FIELD, "Depth of field", Band.SCENE, false, listOf(
            d("Focus", "Auto", "Fixed"), c("Distance", 0.3f, 10f, 300f, needs = 0), c("Blur", 0f, 0.5f, 1f),
            c("Range", 0f, 0.3f, 1f),
        )),
        Kind(GLOBAL_ILLUMINATION, "Global illumination", Band.SCENE, false, listOf(
            c("Reach", 0.5f, 4f, 20f), c("Bounce", 0f, 1f, 3f),
        )),
        Kind(TONEMAP, "Tonemap", Band.SCENE, false, listOf(
            d("Curve", "Filmic", "AgX", "Neutral"), c("Exposure", -2f, 0f, 2f), c("Highlights", 0f, 0.5f, 1f),
            c("Contrast", -1f, 0f, 1f),
        )),
        Kind(SKY, "Sky", Band.SCENE, false, listOf(
            c("Brightness", -1f, 0f, 1f), c("Saturation", -1f, 0f, 1f), c("Hue", -180f, 0f, 180f),
            c("Warmth", -1f, 0f, 1f),
        )),
        Kind(SUN_SHAFTS, "Sun shafts", Band.SCENE, false, listOf(
            c("Length", 0.1f, 0.5f, 1f), c("Threshold", 0.3f, 0.8f, 1f), c("Warmth", 0f, 0.3f, 1f),
        )),
        Kind(OUTLINE, "Outline", Band.SCENE, false, listOf(
            c("Thickness", 0.5f, 1f, 3f), c("Darkness", 0f, 0.8f, 1f), c("Detail", 0f, 0.5f, 1f),
        )),
        Kind(EDGE_SMOOTHING, "Edge smoothing", Band.SCENE, false, listOf(
            c("Edge threshold", 0.03f, 0.1f, 0.3f), d("Quality", "Fast", "Best"),
        )),
        Kind(RT_SHADOWS, "Ray-traced shadows", Band.SCENE, false, listOf(
            c("Softness", 0f, 0.3f, 1f), c("Distance", 5f, 40f, 200f),
        )),
        Kind(RT_REFLECTIONS, "Ray-traced reflections", Band.SCENE, false, listOf(
            c("Gloss", 0f, 0.5f, 1f), c("Distance", 5f, 40f, 200f),
        )),
        Kind(PATH_TRACED_LIGHT, "Path-traced light", Band.SCENE, false, listOf(
            d("Rays", "1", "2"), c("Reach", 1f, 8f, 30f), c("Bounce", 0f, 1f, 3f),
        )),
    )

    operator fun get(kind: Int): Kind = all[kind]

    fun known(kind: Int): Boolean = kind in all.indices
}
