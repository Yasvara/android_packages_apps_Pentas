/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.utils.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.yasvara.pentas.data.AppSettings
import org.yasvara.pentas.data.fpsstats.FpsStatsCollector
import org.yasvara.pentas.data.fpsstats.FpsStatsRepository
import org.yasvara.pentas.telemetry.FpsInteractor
import org.yasvara.pentas.telemetry.TelemetryBus
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MainModule {
    @Provides @Singleton
    fun appSettings(@ApplicationContext context: Context) = AppSettings(context)

    @Provides @Singleton
    fun fps(@ApplicationContext context: Context, bus: TelemetryBus) = FpsInteractor(context, bus)

    @Provides @Singleton
    fun statsCollector(@ApplicationContext context: Context, fps: FpsInteractor, bus: TelemetryBus) =
        FpsStatsCollector(context, fps, bus)

    @Provides @Singleton
    fun statsRepository(@ApplicationContext context: Context) = FpsStatsRepository(context)
}
