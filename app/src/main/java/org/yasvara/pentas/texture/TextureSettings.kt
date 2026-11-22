/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.texture

/**
 * Texture filtering and shading, per game: anisotropic filtering the game didn't ask for, a sharper
 * mip level, and variable rate shading of its 3D work. Samplers and pipelines are made as the game
 * loads, so a change applies from its next start; only the shading rate of a game that started
 * with shading on follows live.
 *
 * On the wire this is the Texture section of the game's framefx channel
 * (frameworks/libs/framefx/include/framefx/channel.h, "Texture section v1"); none is the game's
 * own sampling.
 */
data class TextureSettings(
    val anisotropy: Int = 0,
    val lodBiasEighths: Int = 0,
    /** Coarsest shading of the game's 3D work: 0 full, 1 2×1, 2 2×2, 3 4×4. */
    val shadingRate: Int = 0,
    /** Full rate while the game keeps its pace; coarser only when it falls behind. */
    val shadingAdaptive: Boolean = false,
) {
    val on: Boolean get() = anisotropy != 0 || lodBiasEighths != 0 || shadingRate != 0

    /** The channel section; empty removes it. */
    fun encode(): ByteArray {
        if (!on) return ByteArray(0)
        val shading = if (shadingRate == 0) 0 else shadingRate or (if (shadingAdaptive) ADAPTIVE else 0)
        return byteArrayOf(MAJOR.toByte(), anisotropy.toByte(), lodBiasEighths.toByte(), shading.toByte())
    }

    companion object {
        const val SECTION_ID = 5
        val ANISOTROPY = listOf(0, 4, 8, 16)
        /** Game's, one step sharper, two. */
        val DETAIL = listOf(0, -4, -8)

        private const val SIZE = 4
        private const val MAJOR = 1

        /** Full, then each coarser rate. */
        val SHADING = listOf(0, 1, 2, 3)

        private const val RATE_MASK = 0x03
        private const val ADAPTIVE = 0x80

        fun decode(bytes: ByteArray?): TextureSettings {
            if (bytes == null || bytes.size != SIZE || bytes[0].toInt() != MAJOR) return TextureSettings()
            val shading = bytes[3].toInt() and 0xff
            if (shading and (RATE_MASK or ADAPTIVE).inv() != 0) return TextureSettings()
            val rate = shading and RATE_MASK
            val adaptive = shading and ADAPTIVE != 0
            if (adaptive && rate == 0) return TextureSettings()
            val af = bytes[1].toInt() and 0xff
            val bias = bytes[2].toInt()
            if (af !in listOf(0, 2, 4, 8, 16) || bias !in -16..0) return TextureSettings()
            return TextureSettings(af, bias, rate, adaptive)
        }
    }
}
