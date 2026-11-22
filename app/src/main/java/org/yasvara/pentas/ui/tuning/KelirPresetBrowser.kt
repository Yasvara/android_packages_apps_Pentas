/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.ui.tuning

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yasvara.pentas.R
import org.yasvara.pentas.kelir.KelirPresets
import org.yasvara.pentas.kelir.KelirSlots
import org.yasvara.pentas.ui.kit.Kit
import org.yasvara.pentas.ui.kit.PageHeader
import org.yasvara.pentas.ui.kit.RoundButton
import org.yasvara.pentas.ui.kit.label

/**
 * The presets, tried one by one on the running game in the active slot. Nothing is stored until
 * Keep; leaving puts the slot back as it was.
 */
@Composable
internal fun KelirPresetBrowser(
    slots: KelirSlots,
    depth: SceneDepth?,
    onTry: (KelirSlots) -> Unit,
    onKeep: (KelirSlots) -> Unit,
    onClose: (KelirSlots) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val original = remember { slots }
    val slot = original.active
    val presets = remember(depth?.ready) { KelirPresets.usable(depth?.ready) }
    var at by remember { mutableIntStateOf(-1) }
    fun pick(i: Int) {
        at = Math.floorMod(i, presets.size)
        onTry(original.edit(slot, presets[at].stack))
    }

    Column(verticalArrangement = Arrangement.spacedBy(Kit.Gap)) {
        PageHeader("Presets · slot ${slot + 1}", onBack = { onClose(original) }, back = R.drawable.sym_arrow_back)
        Text(
            if (at < 0) "Tap one to see it on the game. Keep puts it in slot ${slot + 1}."
            else presets[at].note,
            Modifier.padding(horizontal = 6.dp), style = label(11.5.sp), color = cs.onSurfaceVariant,
        )
        for (row in presets.indices.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in row) {
                    val on = i == at
                    val bg by animateColorAsState(if (on) cs.primaryContainer else cs.surfaceContainer, label = "preset")
                    Column(
                        Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(16.dp)).background(bg)
                            .clickable(role = Role.RadioButton) { pick(i) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            presets[i].name, style = label(12.sp, 600),
                            color = if (on) cs.onPrimaryContainer else cs.onSurface, maxLines = 1,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (n in presets[i].stack.nodes) KindGlyph(n.kind, size = 12.dp)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundButton(R.drawable.sym_chevron_left, "Previous preset", { pick(if (at < 0) -1 else at - 1) }, size = 40.dp)
            val ready = at >= 0
            Text(
                if (ready) "Keep ${presets[at].name}" else "Keep",
                Modifier.weight(1f).height(40.dp).clip(CircleShape)
                    .background(if (ready) cs.primary else cs.surfaceContainerHigh)
                    .clickable(enabled = ready) { onKeep(original.edit(slot, presets[at].stack)) }
                    .padding(top = 11.dp),
                style = label(13.sp, 600), color = if (ready) cs.onPrimary else cs.outline,
                textAlign = TextAlign.Center, maxLines = 1,
            )
            RoundButton(R.drawable.sym_chevron_right, "Next preset", { pick(at + 1) }, size = 40.dp)
        }
        if (original.slots[slot].nodes.isNotEmpty()) {
            Text(
                "Keep replaces the ${original.slots[slot].nodes.size} filters in slot ${slot + 1}.",
                Modifier.padding(horizontal = 6.dp), style = label(10.5.sp), color = cs.outline,
            )
        }
    }
}
