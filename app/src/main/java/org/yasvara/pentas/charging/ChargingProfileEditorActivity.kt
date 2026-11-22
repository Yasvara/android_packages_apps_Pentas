/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.charging

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.yasvara.pentas.ui.kit.Segmented
import org.yasvara.pentas.ui.kit.Sym
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num
import org.yasvara.pentas.ui.settings.SettingsIntSliderRow
import org.yasvara.pentas.ui.settings.SettingsValueSlider
import org.yasvara.pentas.ui.theme.PentasTheme
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint(ComponentActivity::class)
class ChargingProfileEditorActivity : Hilt_ChargingProfileEditorActivity() {

    private val vm: ChargingProfileEditorViewModel by viewModels()
    @Inject lateinit var repository: ChargingProfileRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PentasTheme {
                EditorRoot(onBack = { finish() })
            }
        }
    }

    /** Reverts a test-applied draft when the editor finishes. */
    override fun onDestroy() {
        if (isFinishing) vm.revertPreview()
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun EditorRoot(onBack: () -> Unit) {
        val cs = MaterialTheme.colorScheme
        val snackbar = remember { SnackbarHostState() }
        var showImporter by remember { mutableStateOf(false) }

        LaunchedEffect(vm.statusMessage) {
            vm.statusMessage?.let { snackbar.showSnackbar(it); vm.clearStatus() }
        }

        // Poll live battery / SC8581 / bypass readings off the main thread.
        LaunchedEffect(vm.policy.dynamics) {
            while (true) {
                val r = withContext(Dispatchers.IO) { ChargeReadings.read(vm.policy.dynamics) }
                vm.updateReadings(r)
                delay(2000)
            }
        }

        Scaffold(
            containerColor = cs.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                if (vm.isExisting) "Edit Charging Profile" else "New Charging Profile",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (vm.previewApplied) {
                                Text(
                                    "Live test active (reverts on exit)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = cs.tertiary,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Sym.ArrowBack, "Back") }
                    },
                    actions = {
                        IconButton(onClick = { vm.applyNow() }) {
                            Icon(Sym.Bolt, "Test apply")
                        }
                        IconButton(onClick = { if (vm.save()) onBack() }) {
                            Icon(Sym.Check, "Save")
                        }
                    },
                )
            },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                Column(
                    Modifier.weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    Spacer(Modifier.height(6.dp))

                    // Easy | Advanced mode toggle
                    Segmented(
                        options = listOf("Easy" to false, "Advanced" to true),
                        selected = vm.advancedMode,
                        onSelect = { vm.advancedMode = it },
                        height = 38.dp,
                    )

                    Spacer(Modifier.height(10.dp))
                    LiveTelemetryStrip(vm.readings)

                    Spacer(Modifier.height(10.dp))
                    NameField()

                    Spacer(Modifier.height(8.dp))
                    ChargingCurvePreview(
                        tiers = vm.tiers.toList(),
                        suspendMc = vm.suspendMc,
                        liveCellC = vm.readings.cellC,
                    )

                    if (!vm.advancedMode) {
                        EasyModeContent(onOpenTemplates = { showImporter = true })
                    } else {
                        AdvancedModeContent(onOpenTemplates = { showImporter = true })
                    }

                    ErrorList()
                    Spacer(Modifier.height(20.dp))
                }

                // Bottom action bar
                Surface(
                    color = cs.surfaceContainerHigh,
                    tonalElevation = 2.dp,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { vm.applyNow() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Sym.Bolt, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Test Live")
                        }
                        Button(
                            onClick = { if (vm.saveAndApply()) onBack() },
                            modifier = Modifier.weight(1.3f),
                        ) {
                            Icon(Sym.Check, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save & Activate")
                        }
                    }
                }
            }
        }

        if (showImporter) {
            ImporterSheet(
                repository = repository,
                onPick = { vm.importFrom(it); showImporter = false },
                onDismiss = { showImporter = false },
            )
        }
    }

    // ---- Shared Top Elements ----

    @Composable
    private fun LiveTelemetryStrip(r: ChargeReadings) {
        val cs = MaterialTheme.colorScheme
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(cs.surfaceContainer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val cellTxt = r.cellC?.let { "Cell %.1f°C".format(it) } ?: "Cell —"
            val powerTxt = when {
                r.isBypassActive -> "Bypass (${r.bypassStateLabel()})"
                r.packWatts != null -> "%s%.1fW".format(if (r.charging) "+" else "−", r.packWatts)
                else -> "Idle"
            }
            val cpTxt = when {
                r.cpDieC != null && r.cpFswKhz != null -> "CP %.0f°C · %dk".format(r.cpDieC, r.cpFswKhz)
                r.cpDieC != null -> "CP %.0f°C".format(r.cpDieC)
                else -> r.adapterSummary()
            }
            Text(cellTxt, style = num(12.sp, 600), color = if ((r.cellC ?: 0f) >= 43f) cs.error else cs.onSurface)
            Text(powerTxt, style = num(12.sp, 600), color = if (r.isBypassActive) cs.tertiary else cs.primary)
            Text(cpTxt, style = label(11.sp, 500), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun NameField() {
        OutlinedTextField(
            value = vm.name,
            onValueChange = { vm.name = it; vm.dirty = true },
            label = { Text("Profile name") },
            placeholder = { Text("e.g. Cool 90W Daily") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // =========================================================================
    // EASY MODE — 1-Tap Presets + 3 Live Sliders + Simple Bypass Card
    // =========================================================================

    @Composable
    private fun EasyModeContent(onOpenTemplates: () -> Unit) {
        val cs = MaterialTheme.colorScheme

        SectionLabel("Quick Presets")
        val presets = ChargingSuggestionEngine.PRESETS
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { preset ->
                        EasyPresetCard(
                            preset = preset,
                            selected = vm.activePresetId == preset.id,
                            modifier = Modifier.weight(1f),
                            onClick = { vm.applyPreset(preset) },
                        )
                    }
                    if (pair.size == 1) {
                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
                            onClick = onOpenTemplates,
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Sym.ContentCopy, null, tint = cs.primary, modifier = Modifier.size(20.dp))
                                Text("More Templates", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Onyx reference curves or duplicate a saved profile",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = cs.onSurfaceVariant,
                                    lineHeight = 14.sp,
                                )
                            }
                        }
                    }
                }
            }
        }

        SectionLabel("Speed & Comfort")
        EasyTunerCard()

        SectionLabel("Bypass Charging")
        EasyBypassCard()

        Spacer(Modifier.height(10.dp))
        TextButton(
            onClick = { vm.advancedMode = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Need per-tier PID, JEITA bands, or ZDFB v3 tuning? Switch to Advanced")
            Spacer(Modifier.width(4.dp))
            Icon(Sym.KeyboardArrowRight, null, modifier = Modifier.size(16.dp))
        }
    }

    @Composable
    private fun EasyPresetCard(
        preset: ChargingSource.Preset,
        selected: Boolean,
        modifier: Modifier,
        onClick: () -> Unit,
    ) {
        val cs = MaterialTheme.colorScheme
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            border = if (selected) BorderStroke(1.5.dp, cs.primary) else null,
            colors = CardDefaults.cardColors(
                containerColor = if (selected) cs.primaryContainer.copy(alpha = 0.45f)
                else cs.surfaceContainerHigh,
            ),
            onClick = onClick,
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        ImageVector.vectorResource(preset.icon), null,
                        tint = cs.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    if (preset.badge.isNotEmpty()) {
                        Text(
                            preset.badge,
                            style = label(9.5.sp, 600),
                            color = if (selected) cs.onPrimary else cs.onSecondaryContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selected) cs.primary else cs.secondaryContainer)
                                .padding(horizontal = 6.dp, vertical = 1.5.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    preset.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    preset.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    lineHeight = 14.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    private fun EasyTunerCard() {
        val cs = MaterialTheme.colorScheme
        val cfg = vm.wizardConfig
        val maxW = ChargingProfiles.MAX_WATT.coerceAtLeast(100f)

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(vertical = 10.dp, horizontal = 4.dp)) {
                SettingsValueSlider(
                    label = "Max Charge Speed",
                    value = cfg.maxWatt.coerceIn(10f, maxW),
                    range = 10f..maxW,
                    onCommit = { w -> vm.updateEasyConfig(cfg.copy(maxWatt = w.roundToInt().toFloat())) },
                    valueText = { w ->
                        val amps = ChargingProfiles.uaToAmp(ChargingProfiles.wattToUa(w))
                        val tag = when {
                            w >= 95f -> "Peak"
                            w >= 80f -> "Hyper"
                            w >= 45f -> "Fast"
                            w >= 25f -> "Cool"
                            else -> "Gentle"
                        }
                        "%.0f W · %.1f A · %s".format(w, amps, tag)
                    },
                )
                SettingsValueSlider(
                    label = "Target Battery Comfort",
                    value = cfg.targetTempC.coerceIn(34f, 44f),
                    range = 34f..44f,
                    onCommit = { c ->
                        val snapped = (c * 2f).roundToInt() / 2f
                        vm.updateEasyConfig(cfg.copy(targetTempC = snapped))
                    },
                    valueText = { c ->
                        val tag = when {
                            c <= 37f -> "Very Cool"
                            c <= 40f -> "Cool Cruise"
                            c <= 42f -> "Fast"
                            else -> "Warm"
                        }
                        "%.1f°C · %s".format(c, tag)
                    },
                )
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(
                        "Thermal Priority",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    val priority = when {
                        cfg.aggressiveness >= 60 -> 75
                        cfg.aggressiveness <= 30 -> 15
                        else -> 45
                    }
                    Segmented(
                        options = listOf(
                            "Cooler" to 75,
                            "Balanced" to 45,
                            "Faster" to 15,
                        ),
                        selected = priority,
                        onSelect = { aggr -> vm.updateEasyConfig(cfg.copy(aggressiveness = aggr)) },
                        height = 34.dp,
                    )
                }
                if (cfg.maxWatt > 90f) {
                    Text(
                        "22A (100W) envelope is enabled automatically for short cold bursts; SC8581 die guard stays active.",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun EasyBypassCard() {
        val cs = MaterialTheme.colorScheme
        val bypass = vm.policy.bypass ?: BypassTuning(mode = BypassMode.OFF)

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Powers the phone straight from your USB-PD / PPS adapter so heavy gaming heat skips the battery cell.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                Segmented(
                    options = BypassMode.entries.map { it.label to it },
                    selected = bypass.mode,
                    onSelect = { vm.setBypassMode(it) },
                    height = 36.dp,
                )
                AnimatedVisibility(visible = bypass.mode != BypassMode.OFF) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(cs.surfaceContainer)
                            .clickable { vm.setCpBypassEnabled(!bypass.cpEnable) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Direct Charge Pump Bypass (>12W PPS)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Uses SC8581 2:1 direct path under heavy loads (~98% eff, ~6× cooler than PMIC buck).",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = bypass.cpEnable,
                            onCheckedChange = { vm.setCpBypassEnabled(it) },
                        )
                    }
                }
            }
        }
    }

    // =========================================================================
    // ADVANCED MODE — Full Per-Tier PID, Emergency, JEITA, Envelope & Bypass v3
    // =========================================================================

    @Composable
    private fun AdvancedModeContent(onOpenTemplates: () -> Unit) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onOpenTemplates,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Sym.ContentCopy, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Import Preset, Onyx Reference, or Copy Profile…")
        }

        SectionLabel("Thermal Tiers (${vm.tiers.size}/${ChargingProfiles.MAX_TIERS})")
        vm.tiers.forEachIndexed { i, tier ->
            CollapsibleTierCard(
                index = i,
                tier = tier,
                canRemove = vm.tiers.size > 1,
                onChange = { vm.updateTier(i, it) },
                onRemove = { vm.removeTier(i) },
            )
            Spacer(Modifier.height(8.dp))
        }
        if (vm.tiers.size < ChargingProfiles.MAX_TIERS) {
            AddTierButton(onClick = { vm.addTier() })
        }

        SectionLabel("Emergency Cutoff")
        EmergencyCard()

        SectionLabel("Kernel Policy & Envelope")
        AdvancedPolicyCard()

        SectionLabel("Bypass Regulator (ZDFB v3 + SC8581 CP-Bypass)")
        AdvancedBypassCard()

        SectionLabel("Live Hardware Sensors")
        HardwareTelemetryCard()
    }

    @Composable
    private fun CollapsibleTierCard(
        index: Int,
        tier: ChargingTier,
        canRemove: Boolean,
        onChange: (ChargingTier) -> Unit,
        onRemove: () -> Unit,
    ) {
        val cs = MaterialTheme.colorScheme
        var expanded by remember { mutableStateOf(false) }
        val maxW = ChargingProfiles.uaToWatt(tier.maxUa)
        val minW = ChargingProfiles.uaToWatt(tier.minUa)
        val maxA = ChargingProfiles.uaToAmp(tier.maxUa)
        val trigC = ChargingProfiles.mcToC(tier.trigMc)
        val modeTag = if (tier.isPureCap) {
            "Cap %.0fW (%.1fA)".format(maxW, maxA)
        } else {
            "%.0f..%.0fW · PID %.1f°C".format(maxW, minW, ChargingProfiles.mcToC(tier.targetMc))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Tier ${index + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "%.1f°C → %s".format(trigC, modeTag),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        if (expanded) Sym.ArrowDropDown else Sym.KeyboardArrowRight,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    if (canRemove) {
                        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                            Icon(Sym.Delete, "Remove", tint = cs.error, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                AnimatedVisibility(visible = expanded) {
                    Column(Modifier.padding(top = 6.dp)) {
                        SettingsValueSlider(
                            label = "Trigger temp",
                            value = ChargingProfiles.mcToC(tier.trigMc),
                            range = 15f..56f,
                            onCommit = {
                                val mc = ChargingProfiles.cToMc(it)
                                val clr = if (tier.clrMc >= mc) (mc - 1500).coerceAtLeast(10_000L) else tier.clrMc
                                onChange(tier.copy(trigMc = mc, clrMc = clr))
                            },
                            valueText = { "%.1f°C".format(it) },
                        )
                        SettingsValueSlider(
                            label = "Clear temp (hysteresis)",
                            value = ChargingProfiles.mcToC(tier.clrMc),
                            range = 10f..55f,
                            onCommit = { onChange(tier.copy(clrMc = ChargingProfiles.cToMc(it))) },
                            valueText = { "%.1f°C".format(it) },
                        )
                        SettingsValueSlider(
                            label = "PID target (0 = pure cap)",
                            value = ChargingProfiles.mcToC(tier.targetMc),
                            range = 0f..56f,
                            onCommit = {
                                onChange(tier.copy(targetMc = if (it < 1f) 0L else ChargingProfiles.cToMc(it)))
                            },
                            valueText = { if (it < 1f) "OFF (cap)" else "%.1f°C".format(it) },
                        )
                        SettingsValueSlider(
                            label = "Max current",
                            value = ChargingProfiles.uaToWatt(tier.maxUa),
                            range = ChargingProfiles.MIN_WATT..ChargingProfiles.MAX_WATT,
                            onCommit = {
                                val ua = ChargingProfiles.wattToUa(it)
                                onChange(tier.copy(maxUa = ua, minUa = tier.minUa.coerceAtMost(ua)))
                            },
                            valueText = { "%.1f W (%.1f A)".format(it, ChargingProfiles.uaToAmp(ChargingProfiles.wattToUa(it))) },
                        )
                        SettingsValueSlider(
                            label = "Min current",
                            value = ChargingProfiles.uaToWatt(tier.minUa),
                            range = ChargingProfiles.MIN_WATT..ChargingProfiles.MAX_WATT,
                            onCommit = {
                                val ua = ChargingProfiles.wattToUa(it).coerceAtMost(tier.maxUa)
                                onChange(tier.copy(minUa = ua))
                            },
                            valueText = { "%.1f W (%.1f A)".format(it, ChargingProfiles.uaToAmp(ChargingProfiles.wattToUa(it))) },
                        )
                        var pidOpen by remember { mutableStateOf(false) }
                        TextButton(onClick = { pidOpen = !pidOpen }) {
                            Text(if (pidOpen) "Hide PID gains" else "PID gains (Kp/Ki/Kd)…", fontSize = 11.sp)
                        }
                        if (pidOpen) {
                            SettingsIntSliderRow("Kp (proportional)", tier.ks.toInt(), 0..500, { onChange(tier.copy(ks = it.toLong())) })
                            SettingsIntSliderRow("Ki (integral)", tier.ki.toInt(), 0..100, { onChange(tier.copy(ki = it.toLong())) })
                            SettingsIntSliderRow("Kd (damping)", tier.kc.toInt(), 0..300, { onChange(tier.copy(kc = it.toLong())) })
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EmergencyCard() {
        val cs = MaterialTheme.colorScheme
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(12.dp)) {
                SettingsValueSlider(
                    label = "Suspend charging at",
                    value = ChargingProfiles.mcToC(vm.suspendMc),
                    range = 42f..58f,
                    onCommit = {
                        val mc = ChargingProfiles.cToMc(it)
                        vm.suspendMc = mc
                        if (vm.resumeMc >= mc) vm.resumeMc = mc - 3000
                        vm.dirty = true
                    },
                    valueText = { "%.1f°C".format(it) },
                )
                SettingsValueSlider(
                    label = "Resume charging below",
                    value = ChargingProfiles.mcToC(vm.resumeMc),
                    range = 38f..55f,
                    onCommit = { vm.resumeMc = ChargingProfiles.cToMc(it); vm.dirty = true },
                    valueText = { "%.1f°C".format(it) },
                )
            }
        }
    }

    @Composable
    private fun AdvancedPolicyCard() {
        val cs = MaterialTheme.colorScheme
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Policy Presets", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (!vm.policy.isEmpty) {
                        TextButton(onClick = { vm.resetPolicyToKernelDefault() }) {
                            Text("Reset to DT", fontSize = 11.sp)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChargingPolicyPresets.Preset.entries.forEach { preset ->
                        Column(
                            Modifier.weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(cs.primaryContainer.copy(alpha = 0.35f))
                                .clickable { vm.applyPolicyPreset(preset) }
                                .padding(10.dp),
                        ) {
                            Text(preset.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Text(preset.summary, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, lineHeight = 13.sp)
                        }
                    }
                }

                // 22A silicon envelope toggle
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(cs.surfaceContainer)
                        .clickable { vm.setEnvelope22A(!vm.isEnvelope22A) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("22A (100W) Silicon Envelope", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Widens kernel FCC clamp from 20A (90W) to 22A (100W). SC8581 OCP & die guard stay active.",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Switch(checked = vm.isEnvelope22A, onCheckedChange = { vm.setEnvelope22A(it) })
                }

                SettingsValueSlider(
                    label = "JEITA hot charging ceiling",
                    value = vm.hotCeilingC.toFloat(),
                    range = 40f..(ChargingEnvelope.TEMP_MAX_C - 2).toFloat(),
                    onCommit = { vm.setHotCeiling(it.roundToInt()) },
                    valueText = { "%.0f°C".format(it) },
                )

                val pdCap = vm.policy.caps.inputMa["pd"] ?: 2000
                SettingsValueSlider(
                    label = "USB-PD buck input cap (in_pd)",
                    value = pdCap.toFloat(),
                    range = 500f..6000f,
                    onCommit = { v ->
                        val ma = (v / 100f).roundToInt() * 100
                        vm.updatePolicy { p ->
                            val base = if (p.isEmpty) ChargingPolicyPresets.balanced() else p
                            base.copy(caps = base.caps.copy(inputMa = base.caps.inputMa + ("pd" to ma)))
                        }
                    },
                    valueText = { "%.0f mA".format(it) },
                )

                val ppsCap = vm.policy.caps.inputMa["pps"] ?: 2000
                SettingsValueSlider(
                    label = "PPS buck input cap (in_pps)",
                    value = ppsCap.toFloat(),
                    range = 500f..6000f,
                    onCommit = { v ->
                        val ma = (v / 100f).roundToInt() * 100
                        vm.updatePolicy { p ->
                            val base = if (p.isEmpty) ChargingPolicyPresets.balanced() else p
                            base.copy(caps = base.caps.copy(inputMa = base.caps.inputMa + ("pps" to ma)))
                        }
                    },
                    valueText = { "%.0f mA".format(it) },
                )
            }
        }
    }

    @Composable
    private fun AdvancedBypassCard() {
        val cs = MaterialTheme.colorScheme
        val bypass = vm.policy.bypass ?: BypassTuning(mode = BypassMode.OFF)
        var showZdfb by remember { mutableStateOf(false) }

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Segmented(
                    options = BypassMode.entries.map { it.label to it },
                    selected = bypass.mode,
                    onSelect = { vm.setBypassMode(it) },
                    height = 36.dp,
                )

                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(cs.surfaceContainer)
                        .clickable { vm.setCpBypassEnabled(!bypass.cpEnable) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("SC8581 DIV2 Direct CP-Bypass (cp_en)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Hands off from buck to 2:1 charge pump on >=25W PPS adapters for sustained >12W gaming.",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Switch(checked = bypass.cpEnable, onCheckedChange = { vm.setCpBypassEnabled(it) })
                }

                if (bypass.cpEnable) {
                    SettingsValueSlider(
                        label = "CP-Bypass enter load (cp_mw)",
                        value = bypass.cpEnterMw / 1000f,
                        range = 6f..25f,
                        onCommit = { w ->
                            vm.updateBypass { it.copy(cpEnterMw = (w.roundToInt() * 1000)) }
                        },
                        valueText = { "%.0f W".format(it) },
                    )
                    SettingsValueSlider(
                        label = "CP-Bypass max Vbat (cp_vbat)",
                        value = bypass.cpVbatMaxMv.toFloat(),
                        range = ChargingEnvelope.CP_VBAT_MIN_MV.toFloat()..ChargingEnvelope.CP_VBAT_MAX_MV.toFloat(),
                        onCommit = { mv ->
                            val snapped = (mv / 10f).roundToInt() * 10
                            vm.updateBypass { it.copy(cpVbatMaxMv = snapped) }
                        },
                        valueText = { "%.0f mV".format(it) },
                    )
                }

                TextButton(onClick = { showZdfb = !showZdfb }) {
                    Text(if (showZdfb) "Hide Buck ZDFB v3 Regulator Tuning" else "Buck ZDFB v3 Regulator Tuning…", fontSize = 12.sp)
                }

                AnimatedVisibility(visible = showZdfb) {
                    Column {
                        SettingsValueSlider(
                            label = "Target pack current (target)",
                            value = bypass.targetMa.toFloat(),
                            range = -200f..500f,
                            onCommit = { v -> vm.updateBypass { it.copy(targetMa = (v / 10f).roundToInt() * 10) } },
                            valueText = { "%+.0f mA".format(it) },
                        )
                        SettingsValueSlider(
                            label = "9V/PD bypass input cap (in9v)",
                            value = bypass.in9vMa.toFloat(),
                            range = 1000f..4000f,
                            onCommit = { v -> vm.updateBypass { it.copy(in9vMa = (v / 100f).roundToInt() * 100) } },
                            valueText = { "%.0f mA".format(it) },
                        )
                        SettingsValueSlider(
                            label = "5V bypass input cap (in5v)",
                            value = bypass.in5vMa.toFloat(),
                            range = 1000f..3000f,
                            onCommit = { v -> vm.updateBypass { it.copy(in5vMa = (v / 100f).roundToInt() * 100) } },
                            valueText = { "%.0f mA".format(it) },
                        )
                        SettingsIntSliderRow(
                            label = "Deadband (deadband)",
                            value = bypass.deadbandMa,
                            range = 5..150,
                            onCommit = { v -> vm.updateBypass { it.copy(deadbandMa = v) } },
                            valueText = { "$it mA" },
                        )
                        SettingsIntSliderRow(
                            label = "Coarse step (step)",
                            value = bypass.stepMa,
                            range = 10..200,
                            onCommit = { v -> vm.updateBypass { it.copy(stepMa = v) } },
                            valueText = { "$it mA" },
                        )
                        SettingsIntSliderRow(
                            label = "Proportional gain (kp)",
                            value = bypass.kp,
                            range = 5..60,
                            onCommit = { v -> vm.updateBypass { it.copy(kp = v) } },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun HardwareTelemetryCard() {
        val cs = MaterialTheme.colorScheme
        val r = vm.readings
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SensorLine("Cell (fuel gauge)", vm.policy.dynamics.cellSensor, r.cellC?.let { "%.1f°C".format(it) })
                SensorLine("Board thermistor", vm.policy.dynamics.boardSensor, r.boardC?.let { "%.1f°C".format(it) })
                SensorLine("Charger thermistor", vm.policy.dynamics.pumpSensor, r.pumpC?.let { "%.1f°C".format(it) })
                SensorLine("SC8581 Die Temp", "cp_master/tdie", r.cpDieC?.let { "%.0f°C".format(it) })
                SensorLine("SC8581 VFC Freq", "cp_master/fsw", r.cpFswKhz?.let { "$it kHz" })
                SensorLine("Bypass State", "smart_charge", "${r.bypassStateLabel()} (${if (r.bypassDrainMa > 0) "−${r.bypassDrainMa}" else "+${-r.bypassDrainMa}"} mA)")
                r.boardOffsetC?.let { delta ->
                    Text(
                        "Board reads %+.1f°C above cell — all tier thresholds use cell temperature."
                            .format(delta),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun SensorLine(role: String, source: String, value: String?) {
        val cs = MaterialTheme.colorScheme
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("$role · $source", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Text(value ?: "—", style = num(12.sp, 600), color = cs.onSurface)
        }
    }

    @Composable
    private fun ErrorList() {
        if (vm.validationErrors.isEmpty()) return
        val cs = MaterialTheme.colorScheme
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cs.errorContainer),
        ) {
            Column(Modifier.padding(12.dp)) {
                vm.validationErrors.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onErrorContainer,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun AddTierButton(onClick: () -> Unit) {
        val cs = MaterialTheme.colorScheme
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(cs.primaryContainer.copy(alpha = 0.4f))
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Sym.Add, null, tint = cs.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add tier", color = cs.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 6.dp, top = 16.dp, bottom = 6.dp),
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ImporterSheet(
        repository: ChargingProfileRepository,
        onPick: (ChargingSource) -> Unit,
        onDismiss: () -> Unit,
    ) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Text(
                "Load Curve or Template",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Column(
                Modifier.padding(horizontal = 10.dp).padding(bottom = 28.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SheetLabel("Curated Presets")
                ChargingSuggestionEngine.PRESETS.forEach { p ->
                    SourceRow(p.name, p.description) { onPick(p) }
                }
                SheetLabel("Onyx Reference Schedules")
                ChargingSuggestionEngine.VENDOR_TEMPLATES.forEach { v ->
                    SourceRow(v.name, v.description) { onPick(v) }
                }
                val saved = repository.loadAll()
                if (saved.isNotEmpty()) {
                    SheetLabel("Duplicate Saved Profile")
                    saved.forEach { p ->
                        SourceRow(
                            p.name,
                            "${p.tiers.size} tiers · %.0fW peak".format(p.peakWatt),
                        ) {
                            onPick(ChargingSource.Vendor(p.id, p.name, "copy", p.tiers.toList(), p.policy))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SheetLabel(text: String) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 4.dp),
        )
    }

    @Composable
    private fun SourceRow(title: String, subtitle: String, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
