/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.charging

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yasvara.pentas.thermal.ThermalNodes
import org.yasvara.pentas.thermal.ThermalProfiles
import org.yasvara.pentas.ui.kit.Segmented
import org.yasvara.pentas.ui.kit.Sym
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num
import org.yasvara.pentas.ui.theme.PentasTheme
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint(ComponentActivity::class)
class ChargingProfileListActivity : Hilt_ChargingProfileListActivity() {

    @Inject lateinit var repository: ChargingProfileRepository
    private val refreshTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PentasTheme {
                ListRoot(revision = refreshTick.intValue, onBack = { finish() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick.intValue++
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ListRoot(revision: Int, onBack: () -> Unit) {
        val context = this
        val cs = MaterialTheme.colorScheme
        val cr = context.contentResolver
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        var refresh by remember { mutableIntStateOf(0) }
        var showResetDialog by remember { mutableStateOf(false) }

        val profiles = remember(revision, refresh) { repository.loadAll() }
        val globalId = remember(revision, refresh) { repository.getGlobalProfileId() }
        val activeName = remember(profiles, globalId) {
            profiles.firstOrNull { it.id == globalId }?.name ?: "Cool 90W (Default)"
        }

        var bypassMode by remember(revision, refresh) {
            mutableIntStateOf(
                Settings.System.getIntForUser(
                    cr, ThermalProfiles.KEY_BYPASS_CHARGING,
                    ThermalNodes.BYPASS_MODE_OFF, UserHandle.USER_CURRENT,
                ).coerceIn(ThermalNodes.BYPASS_MODE_OFF, ThermalNodes.BYPASS_MODE_AUTO)
            )
        }

        var readings by remember { mutableStateOf<ChargeReadings?>(null) }
        LaunchedEffect(Unit) {
            while (true) {
                readings = withContext(Dispatchers.IO) { ChargeReadings.read() }
                delay(1500)
            }
        }

        Scaffold(
            containerColor = cs.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Charging", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Active: $activeName",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Sym.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        if (profiles.isNotEmpty() || globalId != null) {
                            IconButton(onClick = { showResetDialog = true }) {
                                Icon(Sym.DeleteSweep, "Reset all")
                            }
                        }
                    },
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { startActivity(editorIntent(context, null, null)) },
                    containerColor = cs.primaryContainer,
                    contentColor = cs.onPrimaryContainer,
                    icon = { Icon(Sym.Add, null) },
                    text = { Text("New Profile") },
                )
            },
        ) { pad ->
            LazyColumn(
                Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 6.dp, bottom = 96.dp),
            ) {
                item(key = "live_hub") {
                    LiveChargingHubCard(
                        readings = readings,
                        bypassMode = bypassMode,
                        onSelectBypass = { next ->
                            bypassMode = next
                            Settings.System.putIntForUser(
                                cr, ThermalProfiles.KEY_BYPASS_CHARGING, next, UserHandle.USER_CURRENT,
                            )
                            val label = when (next) {
                                ThermalNodes.BYPASS_MODE_FORCE -> "Bypass Always On"
                                ThermalNodes.BYPASS_MODE_AUTO -> "Bypass On for Games"
                                else -> "Bypass Off"
                            }
                            scope.launch { snackbar.showSnackbar(label) }
                        },
                    )
                }

                item(key = "sec_default") {
                    ListSectionLabel("System Default")
                }

                item(key = "system_default") {
                    SystemDefaultCard(
                        isActive = globalId == null,
                        onActivate = {
                            repository.setGlobalProfileId(null)
                            repository.applyToDaemon(null)
                            refresh++
                            scope.launch {
                                snackbar.showSnackbar("Switched to Cool 90W (System Default)")
                            }
                        },
                    )
                }

                item(key = "sec_quick_presets") {
                    ListSectionLabel("Quick Create From Preset")
                }

                item(key = "preset_strip") {
                    QuickPresetStrip(
                        onCreate = { preset ->
                            startActivity(editorIntent(context, null, preset.id))
                        },
                    )
                }

                item(key = "sec_saved") {
                    ListSectionLabel("My Profiles (${profiles.size})")
                }

                if (profiles.isEmpty()) {
                    item(key = "empty_hint") {
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(Sym.Bolt, null, tint = cs.primary, modifier = Modifier.size(26.dp))
                                Column {
                                    Text(
                                        "Using calibrated Cool 90W default",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "Pick any preset above or tap + New Profile to customize speed, comfort temp, or bypass.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = cs.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    items(profiles, key = { it.id }) { p ->
                        val isActive = p.id == globalId
                        ProfileRow(
                            profile = p,
                            isActive = isActive,
                            onEdit = { startActivity(editorIntent(context, p.id, null)) },
                            onActivate = {
                                repository.setGlobalProfileId(p.id)
                                repository.applyToDaemon(p)
                                repository.syncBypassSetting(p)
                                refresh++
                                scope.launch { snackbar.showSnackbar("\"${p.name}\" is now active") }
                            },
                            onDelete = {
                                repository.delete(p.id)
                                refresh++
                                scope.launch { snackbar.showSnackbar("Deleted \"${p.name}\"") }
                            },
                        )
                    }
                }
            }
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text("Reset all charging profiles?") },
                text = {
                    Text("Removes all saved custom profiles and returns the daemon to the calibrated Cool 90W default.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        repository.resetAll()
                        showResetDialog = false
                        refresh++
                        scope.launch { snackbar.showSnackbar("Reset to Cool 90W default") }
                    }) { Text("Reset", color = cs.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
                },
            )
        }
    }

    @Composable
    private fun LiveChargingHubCard(
        readings: ChargeReadings?,
        bypassMode: Int,
        onSelectBypass: (Int) -> Unit,
    ) {
        val cs = MaterialTheme.colorScheme
        val watts = readings?.packWatts
        val packMa = readings?.packMa
        val packMv = readings?.packMv
        val isBypass = readings?.isBypassActive == true

        val headline = when {
            readings == null -> "Reading…"
            isBypass -> "Bypass"
            watts != null && abs(packMa ?: 0) < 60 -> "0.0 W"
            watts != null -> "%s%.1f W".format(if (readings.charging) "+" else "−", watts)
            else -> "—"
        }

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                headline,
                                style = num(28.sp, 650),
                                color = when {
                                    isBypass -> cs.tertiary
                                    readings?.charging == true -> cs.primary
                                    else -> cs.onSurface
                                },
                            )
                            readings?.capacity?.let { cap ->
                                Text(
                                    "$cap%",
                                    style = num(15.sp, 600),
                                    color = cs.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 3.dp),
                                )
                            }
                        }
                        val detail = if (packMa != null && packMv != null) {
                            "%+.2f A · %.2f V".format(packMa / 1000f, packMv / 1000f)
                        } else "Waiting for fuel gauge"
                        Text(detail, style = num(12.sp, 500), color = cs.onSurfaceVariant)
                    }

                    Text(
                        readings?.adapterSummary() ?: "—",
                        style = label(11.sp, 600),
                        color = if (readings?.usbOnline == true) cs.onPrimaryContainer else cs.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (readings?.usbOnline == true) cs.primaryContainer
                                else cs.surfaceContainerHighest
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }

                // Telemetry chips: Cell temp | SC8581 Pump / Board | Bypass State
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val cellText = readings?.cellC?.let { "%.1f°C".format(it) } ?: "—"
                    val cellWarn = (readings?.cellC ?: 0f) >= 43f
                    MetricPill("Cell", cellText, cellWarn, false, Modifier.weight(1f))

                    val pumpLabel = if (readings?.cpDieC != null || readings?.cpFswKhz != null) {
                        "SC8581"
                    } else "Board"
                    val pumpValue = when {
                        readings?.cpDieC != null && readings.cpFswKhz != null ->
                            "%.0f° · %dk".format(readings.cpDieC, readings.cpFswKhz)
                        readings?.cpDieC != null -> "%.0f°C".format(readings.cpDieC)
                        readings?.cpFswKhz != null -> "${readings.cpFswKhz} kHz"
                        readings?.boardC != null -> "%.1f°C".format(readings.boardC)
                        else -> "—"
                    }
                    MetricPill(pumpLabel, pumpValue, (readings?.cpDieC ?: 0f) >= 80f, false, Modifier.weight(1f))

                    val bpState = readings?.bypassStateLabel() ?: "Off"
                    MetricPill("Bypass", bpState, false, isBypass, Modifier.weight(1f))
                }

                // Quick 1-tap Bypass Mode selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Bypass Charging",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            when (bypassMode) {
                                ThermalNodes.BYPASS_MODE_FORCE -> "Direct power on adapter"
                                ThermalNodes.BYPASS_MODE_AUTO -> "Auto CP-Bypass in games"
                                else -> "Normal battery charging"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Segmented(
                        options = listOf(
                            "Off" to ThermalNodes.BYPASS_MODE_OFF,
                            "Games" to ThermalNodes.BYPASS_MODE_AUTO,
                            "Always" to ThermalNodes.BYPASS_MODE_FORCE,
                        ),
                        selected = bypassMode,
                        onSelect = onSelectBypass,
                        height = 36.dp,
                    )
                }
            }
        }
    }

    @Composable
    private fun MetricPill(
        title: String,
        value: String,
        warn: Boolean,
        highlight: Boolean,
        modifier: Modifier,
    ) {
        val cs = MaterialTheme.colorScheme
        val bg = when {
            warn -> cs.errorContainer
            highlight -> cs.tertiaryContainer
            else -> cs.surfaceContainerHigh
        }
        val fg = when {
            warn -> cs.onErrorContainer
            highlight -> cs.onTertiaryContainer
            else -> cs.onSurface
        }
        Column(
            modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = num(12.5.sp, 620), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, style = label(10.sp), color = cs.onSurfaceVariant, maxLines = 1)
        }
    }

    @Composable
    private fun SystemDefaultCard(
        isActive: Boolean,
        onActivate: () -> Unit,
    ) {
        val cs = MaterialTheme.colorScheme
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            border = if (isActive) BorderStroke(1.5.dp, cs.primary) else null,
            colors = CardDefaults.cardColors(
                containerColor = if (isActive) cs.primaryContainer.copy(alpha = 0.42f)
                else cs.surfaceContainerHigh,
            ),
            onClick = onActivate,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (isActive) cs.primary else cs.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Sym.AutoAwesome, null,
                        tint = if (isActive) cs.onPrimary else cs.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Cool 90W (System Default)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (isActive) {
                            BadgePill("Active", cs.primary, cs.onPrimary)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Universal 3rd-party USB-PD/PPS · 90W burst → cool ~39.5°C cruise · VFC & SC8581 die guard",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }

    @Composable
    private fun QuickPresetStrip(
        onCreate: (ChargingSource.Preset) -> Unit,
    ) {
        val cs = MaterialTheme.colorScheme
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChargingSuggestionEngine.PRESETS.forEach { preset ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
                    onClick = { onCreate(preset) },
                ) {
                    Column(Modifier.width(146.dp).padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Icon(
                                ImageVector.vectorResource(preset.icon), null,
                                tint = cs.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            if (preset.badge.isNotEmpty()) {
                                BadgePill(preset.badge, cs.secondaryContainer, cs.onSecondaryContainer)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            preset.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "%.0fW · %.0f°C".format(preset.config.maxWatt, preset.config.targetTempC),
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ProfileRow(
        profile: CustomChargingProfile,
        isActive: Boolean,
        onEdit: () -> Unit,
        onActivate: () -> Unit,
        onDelete: () -> Unit,
    ) {
        val cs = MaterialTheme.colorScheme
        val peakUa = profile.tiers.maxOfOrNull { it.maxUa } ?: 0L
        val maxWatt = ChargingProfiles.uaToWatt(peakUa)
        val maxAmp = ChargingProfiles.uaToAmp(peakUa)
        val bp = profile.policy.bypass

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            border = if (isActive) BorderStroke(1.5.dp, cs.primary) else null,
            colors = CardDefaults.cardColors(
                containerColor = if (isActive) cs.primaryContainer.copy(alpha = 0.4f)
                else cs.surfaceContainerHigh,
            ),
            onClick = onEdit,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            profile.name.ifBlank { "(unnamed)" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isActive) {
                            BadgePill("Active", cs.primary, cs.onPrimary)
                        }
                    }
                    Text(
                        "%.0fW (%.1fA) · %d tiers · Cutoff %.0f°C".format(
                            maxWatt, maxAmp, profile.tiers.size,
                            ChargingProfiles.mcToC(profile.emergencySuspendMc),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                    val tags = buildList {
                        if ((profile.policy.envelope.fccMaxMa ?: 0) > ChargingEnvelope.POLICY_FCC_DEFAULT_MA) {
                            add("22A Envelope")
                        }
                        if (bp != null && bp.mode != BypassMode.OFF) {
                            add(if (bp.cpEnable) "CP-Bypass (${bp.mode.label})" else "Bypass (${bp.mode.label})")
                        }
                        if (profile.policy.jeitaNormal.isNotEmpty()) {
                            val hi = profile.policy.jeitaNormal.lastOrNull { !it.isCutoff }?.tHiC
                            if (hi != null && hi > 48) add("JEITA ${hi}°C")
                        }
                    }
                    if (tags.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tags.take(3).forEach { tag ->
                                BadgePill(tag, cs.surfaceContainerHighest, cs.onSurfaceVariant)
                            }
                        }
                    }
                }
                IconButton(onClick = onActivate, modifier = Modifier.size(38.dp)) {
                    Icon(
                        if (isActive) Sym.CheckCircle else Sym.Bolt,
                        contentDescription = "Activate",
                        tint = if (isActive) cs.primary else cs.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(38.dp)) {
                    Icon(Sym.Delete, "Delete", tint = cs.error)
                }
            }
        }
    }

    @Composable
    private fun BadgePill(
        text: String,
        bg: androidx.compose.ui.graphics.Color,
        fg: androidx.compose.ui.graphics.Color,
    ) {
        Text(
            text,
            style = label(10.sp, 600),
            color = fg,
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(bg)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }

    @Composable
    private fun ListSectionLabel(text: String) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
        )
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, ChargingProfileListActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        fun editorIntent(context: Context, profileId: String?, sourceId: String?) =
            Intent(context, ChargingProfileEditorActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                profileId?.let { putExtra("profile_id", it) }
                sourceId?.let { putExtra("source_id", it) }
            }
    }
}
