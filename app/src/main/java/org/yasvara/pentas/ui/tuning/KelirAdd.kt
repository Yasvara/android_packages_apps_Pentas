/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.ui.tuning

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.yasvara.pentas.R
import org.yasvara.pentas.kelir.Band
import org.yasvara.pentas.kelir.Kelir
import org.yasvara.pentas.kelir.KelirKinds
import org.yasvara.pentas.kelir.KelirPresets
import org.yasvara.pentas.kelir.KelirSlots
import org.yasvara.pentas.kelir.KelirStack
import org.yasvara.pentas.ui.kit.Block
import org.yasvara.pentas.ui.kit.Kit
import org.yasvara.pentas.ui.kit.PageHeader
import org.yasvara.pentas.ui.kit.Section
import org.yasvara.pentas.ui.kit.Tag
import org.yasvara.pentas.ui.kit.label

/** Adding to a slot: a starting stack in its place, or one kind at the end of its band. */
@Composable
internal fun KelirAdd(
    slot: Int,
    stack: KelirStack,
    depth: SceneDepth?,
    onStart: (KelirStack) -> Unit,
    onAdd: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(Kit.Gap)) {
        PageHeader("Add to slot ${slot + 1}", onBack = onClose, back = R.drawable.sym_close)
        Section("Start from")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((name, _, starter) in KelirPresets.usable(depth?.ready)) {
                Column(
                    Modifier.width(92.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer)
                        .clickable(role = Role.Button) { onStart(starter) }.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(name, style = label(12.sp, 600), color = cs.onSurface)
                    // What it holds, in stack order.
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (n in starter.nodes) KindGlyph(n.kind, size = 12.dp)
                    }
                    if (stack.nodes.isNotEmpty()) {
                        Text("Replaces ${stack.nodes.size}", style = label(9.5.sp), color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        for (band in Band.entries) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Section(band.title())
                band.cap()?.let { cap ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${stack.count(band)}/$cap",
                        Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainerHigh)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        style = label(9.5.sp, 600), color = cs.onSurfaceVariant,
                    )
                }
            }
            val kinds = KelirKinds.all.filter { it.band == band }
            // The 3D kinds need what Lapis finds; unknown while the game isn't running.
            val noDepth = band == Band.SCENE && depth != null && !depth.ready
            for (row in kinds.chunked(3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (kind in row) {
                        val refusal = stack.refusal(kind.ordinal)
                        val ok = refusal == null && !noDepth
                        Column(
                            Modifier.weight(1f).height(58.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (ok) cs.surfaceContainer else cs.surfaceContainerLow)
                                .clickable(enabled = ok, role = Role.Button) { onAdd(kind.ordinal) }
                                .padding(horizontal = 9.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                KindGlyph(kind.ordinal, tint = if (ok) cs.primary else cs.outlineVariant)
                                if (kind.style) Tag("Heavy")
                            }
                            Column {
                                Text(kind.name, style = label(11.sp, 500), color = if (ok) cs.onSurface else cs.outline, maxLines = 1)
                                refusal?.let { Text(it.short(), style = label(9.5.sp), color = cs.outline, maxLines = 1) }
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (band == Band.SCENE) {
                val note = when {
                    depth == null -> "Run where Lapis has the game's depth and its 3D before the HUD. " +
                        "The ray-traced kinds wait for framefx's ray tracing."
                    noDepth -> depth.text + "."
                    else -> "The ray-traced kinds wait for framefx's ray tracing."
                }
                note?.let { Text(it, Modifier.padding(horizontal = 6.dp), style = label(11.sp), color = cs.onSurfaceVariant) }
            }
        }
    }
}

private fun KelirStack.Refusal.short() = when (this) {
    KelirStack.Refusal.FULL -> "Stack full"
    KelirStack.Refusal.DETAIL_FULL -> "Detail full"
    KelirStack.Refusal.STYLE_TAKEN -> "One style"
    KelirStack.Refusal.SCREEN_FULL -> "Screen full"
    KelirStack.Refusal.ONCE -> "In the stack"
}

/** Another game's slot copied into [slot]. */
@Composable
internal fun KelirCopyFrom(pkg: String, slot: Int, kelir: Kelir, onPick: (KelirStack) -> Unit, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val pm = LocalContext.current.packageManager
    val games by produceState(emptyList<Triple<String, String, KelirSlots>>(), pkg) {
        value = withContext(Dispatchers.IO) {
            kelir.games().filter { it != pkg }.mapNotNull { other ->
                val slots = kelir.slots(other)
                if (slots.slots.all { it.nodes.isEmpty() }) return@mapNotNull null
                val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(other, 0)).toString() }.getOrNull()
                    ?: return@mapNotNull null
                Triple(other, name, slots)
            }.sortedBy { it.second.lowercase() }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Kit.Gap)) {
        PageHeader("Into slot ${slot + 1}", onBack = onClose, back = R.drawable.sym_arrow_back)
        if (games.isEmpty()) {
            Text("No other game has filters", Modifier.padding(6.dp), style = label(12.sp), color = cs.onSurfaceVariant)
        }
        games.forEachIndexed { i, (_, name, slots) ->
            Block(shape = Kit.connected(i, games.size), padding = 10.dp) {
                Text(name, style = label(13.sp, 550), color = cs.onSurface, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    slots.slots.forEachIndexed { s, stack ->
                        if (stack.nodes.isNotEmpty()) Chip("${s + 1} · ${stack.nodes.size} filters") { onPick(stack) }
                    }
                }
            }
        }
    }
}
