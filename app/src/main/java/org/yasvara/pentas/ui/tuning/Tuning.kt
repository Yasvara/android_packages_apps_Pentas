/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.ui.tuning

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.yasvara.pentas.audio.DolbyController
import org.yasvara.pentas.data.AngleDriver
import org.yasvara.pentas.data.PerAppSettingStore
import org.yasvara.pentas.kelir.Kelir
import org.yasvara.pentas.upscale.Upscaler
import org.yasvara.pentas.video.VideoPath
import org.yasvara.pentas.luwes.Luwes
import org.yasvara.pentas.thermal.PerfTuner
import org.yasvara.pentas.touch.GameTouchModeManager

/**
 * Per-game tuning editors. The sidebar's tuner and the app's per-game page render the same
 * composables, so a control behaves identically in both. Each takes the package it edits;
 * changes reach the hardware at once when that package is the live session.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TuningDeps {
    fun perApp(): PerAppSettingStore
    fun perf(): PerfTuner
    fun touch(): GameTouchModeManager
    fun dolby(): DolbyController
    fun angle(): AngleDriver
    fun luwes(): Luwes
    fun kelir(): Kelir
    fun upscaler(): Upscaler
    fun videoPath(): VideoPath
}

@Composable
fun rememberTuning(): TuningDeps {
    val ctx: Context = LocalContext.current
    return remember(ctx.applicationContext) {
        EntryPointAccessors.fromApplication(ctx.applicationContext, TuningDeps::class.java)
    }
}
