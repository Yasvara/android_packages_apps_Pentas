/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.yasvara.pentas.R
import org.yasvara.pentas.overlay.LocalOverlay
import org.yasvara.pentas.overlay.Page
import org.yasvara.pentas.overlay.ui.tuner.Tuner
import org.yasvara.pentas.ui.kit.Kit
import org.yasvara.pentas.ui.kit.PageHeader

val PanelWidth = 320.dp

/** The 320 dp panel: one page at a time, swapped in place. */
@Composable
fun Panel(mode: Int) {
    val o = LocalOverlay.current
    val page by o.state.page
    Box(
        Modifier.width(PanelWidth).fillMaxHeight().clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
    ) {
        AnimatedContent(
            page,
            transitionSpec = {
                val forward = targetState != Page.HOME
                (fadeIn(spring(stiffness = 1600f)) + slideInHorizontally(spring(0.8f, 380f)) { if (forward) it / 6 else -it / 6 })
                    .togetherWith(fadeOut(spring(stiffness = 3800f)))
            },
            label = "page",
        ) { p ->
            when (p) {
                Page.HOME -> Home(mode)
                Page.WIDGETS -> WidgetsPage()
                Page.TUNER -> Tuner(mode)
                Page.BATTERY -> BatteryPage()
                Page.BLUETOOTH -> BluetoothPage()
                Page.WIFI -> WifiPage()
                Page.PING -> PingPage()
                Page.RECORD -> RecordPage()
                Page.EDIT -> EditPage()
                Page.APPS -> AppsPage()
            }
        }
    }
}

/** A detail page: header with back to home, then scrolling content. */
@Composable
fun SubPage(title: String, trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val o = LocalOverlay.current
    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(Kit.Gap)) {
        PageHeader(title, onBack = { o.state.home() }, back = R.drawable.sym_arrow_back, trailing = trailing)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Kit.Gap),
            content = content,
        )
    }
}
