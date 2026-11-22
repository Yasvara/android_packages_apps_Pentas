/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.security.yasvara.YasvaraSpoofProfiles
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.yasvara.pentas.charging.ChargingProfileRepository
import org.yasvara.pentas.data.AppClassifier
import org.yasvara.pentas.data.PerAppSettingStore
import org.yasvara.pentas.data.SystemSettings
import org.yasvara.pentas.kelir.Kelir
import org.yasvara.pentas.thermal.PerfTuner
import org.yasvara.pentas.thermal.ThermalProfiles
import org.yasvara.pentas.ui.kit.Kit
import org.yasvara.pentas.ui.kit.ListItem
import org.yasvara.pentas.ui.kit.Sym
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.theme.PentasTheme
import org.yasvara.pentas.ui.tuning.GameClocks
import org.yasvara.pentas.ui.tuning.GraphicsEditor
import org.yasvara.pentas.ui.tuning.KelirEditor
import org.yasvara.pentas.ui.tuning.SoundEditor
import org.yasvara.pentas.ui.tuning.TouchEditor

/** One game's page: identity, launch, and every per-game setting the sidebar also offers. */
class GameActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return finish()
        setContent { PentasTheme { GameScreen(pkg, ::finish) } }
    }

    companion object {
        const val EXTRA_PACKAGE = "package_name"
        fun start(context: Context, pkg: String) =
            context.startActivity(Intent(context, GameActivity::class.java).putExtra(EXTRA_PACKAGE, pkg))
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface GameDeps {
    fun perApp(): PerAppSettingStore
    fun perf(): PerfTuner
    fun system(): SystemSettings
    fun charging(): ChargingProfileRepository
    fun kelir(): Kelir
}

private enum class Part(val title: String) {
    GRAPHICS("Graphics"), KELIR("Kelir"), PERFORMANCE("Performance"), THERMAL("Thermal profile"), TOUCH("Touch"),
    SOUND("Sound"), CHARGING("Charging"), IDENTITY("Device identity"),
}

@Composable
private fun GameScreen(pkg: String, finish: () -> Unit) {
    val ctx = LocalContext.current
    val deps = remember { EntryPointAccessors.fromApplication(ctx.applicationContext, GameDeps::class.java) }
    var part by remember { mutableStateOf<Part?>(null) }
    BackHandler(part != null) { part = null }
    AnimatedContent(part, label = "part") { p ->
        if (p == null) Overview(pkg, deps, onOpen = { part = it }, finish = finish)
        else Screen(p.title, onBack = { part = null }) {
            when (p) {
                Part.GRAPHICS -> GraphicsEditor(pkg)
                Part.KELIR -> KelirEditor(pkg, comfortable = true)
                Part.PERFORMANCE -> GameClocks(pkg)
                Part.THERMAL -> ThermalList(pkg, deps.perApp())
                Part.TOUCH -> TouchEditor(pkg, pad = false)
                Part.SOUND -> SoundEditor(pkg)
                Part.CHARGING -> ChargingList(pkg, deps.charging())
                Part.IDENTITY -> IdentityList(pkg)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Overview(pkg: String, deps: GameDeps, onOpen: (Part) -> Unit, finish: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val pm = ctx.packageManager
    val info = remember { runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() }
    val name = remember { info?.let { pm.getApplicationLabel(it).toString() } ?: pkg }
    val icon = remember { info?.let { pm.getApplicationIcon(it).toBitmap(192, 192).asImageBitmap() } }
    val added = remember { pkg in deps.system().games }
    Screen(name, onBack = finish) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(36.dp)).background(cs.surfaceContainer).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(72.dp).clip(MaterialShapes.Cookie12Sided.toShape()).background(cs.primaryContainer),
                    contentAlignment = Alignment.Center) {
                    icon?.let { Image(it, null, Modifier.size(56.dp)) }
                }
                Column(Modifier.weight(1f)) {
                    Text(pkg, style = label(12.sp), fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (info != null && AppClassifier.isGame(info) || added) "Game" else "App",
                        style = label(13.sp, 600), color = cs.primary)
                }
            }
            Text(
                "Play", Modifier.fillMaxWidth().height(48.dp).clip(CircleShape).background(cs.primary)
                    .clickable { pm.getLaunchIntentForPackage(pkg)?.let(ctx::startActivity) }
                    .padding(top = 13.dp),
                style = label(16.sp, 600), color = cs.onPrimary, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        val perApp = deps.perApp()
        val scale = perApp.resolution(pkg)
        val customJson = Settings.System.getStringForUser(ctx.contentResolver, ThermalProfiles.KEY_CUSTOM_PROFILES, UserHandle.USER_CURRENT)
        val rows = listOf(
            Triple(Part.GRAPHICS, Sym.AspectRatio, if (scale == "1.0") "Native" else "${scale}×"),
            Triple(Part.KELIR, Sym.Palette, deps.kelir().slots(pkg).let { s ->
                s.running?.let { "Slot ${s.active + 1} · ${it.nodes.size} filters" } ?: "Off"
            }),
            Triple(Part.PERFORMANCE, Sym.Speed, if (deps.perf().clockScope(pkg) == PerfTuner.ClockScope.GLOBAL) "Global clocks" else "Own clocks"),
            Triple(Part.THERMAL, Sym.Thermostat, ThermalProfiles.nameOf(perApp.thermalProfile(pkg), customJson)),
            Triple(Part.TOUCH, Sym.TouchApp, null),
            Triple(Part.SOUND, Sym.GraphicEq, null),
            Triple(Part.CHARGING, Sym.BatteryChargingFull,
                deps.charging().getAppProfileId(pkg)?.let { id -> deps.charging().getById(id)?.name } ?: "Follow global"),
            Triple(Part.IDENTITY, Sym.Smartphone, YasvaraSpoofProfiles.profileFor(ctx.contentResolver, pkg)?.name ?: "Real device"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEachIndexed { i, (p, ic, v) -> NavRow(ic, p.title, v, i, rows.size) { onOpen(p) } }
        }
        if (added) Text(
            "Remove from games",
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.errorContainer)
                .clickable { deps.system().removeGame(pkg); perApp.removeAllForPackage(pkg); finish() }
                .padding(16.dp),
            style = label(14.sp, 600), color = cs.onErrorContainer,
        )
    }
}

@Composable
private fun Choice(title: String, selected: Boolean, index: Int, count: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ListItem(
        title = title, shape = Kit.connected(index, count, 24.dp, 6.dp), selected = selected,
        modifier = Modifier.height(56.dp),
        trailing = if (selected) ({ Icon(Sym.Check, null, tint = cs.onSecondaryContainer) }) else null,
        onClick = onClick,
    )
}

@Composable
private fun ThermalList(pkg: String, perApp: PerAppSettingStore) {
    val ctx = LocalContext.current
    var current by remember { mutableIntStateOf(perApp.thermalProfile(pkg)) }
    val options = remember {
        ThermalProfiles.options(
            Settings.System.getStringForUser(ctx.contentResolver, ThermalProfiles.KEY_CUSTOM_PROFILES, UserHandle.USER_CURRENT),
            firstName = "Follow global",
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { i, o ->
            Choice(o.name, o.index == current, i, options.size) { current = o.index; perApp.setThermalProfile(pkg, o.index) }
        }
    }
}

@Composable
private fun ChargingList(pkg: String, repo: ChargingProfileRepository) {
    val profiles = remember { repo.loadAll() }
    var current by remember { mutableStateOf(repo.getAppProfileId(pkg)) }
    val ids = listOf<String?>(null) + profiles.map { it.id }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ids.forEachIndexed { i, id ->
            Choice(profiles.firstOrNull { it.id == id }?.name ?: "Follow global", id == current, i, ids.size) {
                current = id
                repo.setAppProfileId(pkg, id)
                repo.applyActiveProfile(pkg)
            }
        }
    }
}

@Composable
private fun IdentityList(pkg: String) {
    val ctx = LocalContext.current
    val all = remember { YasvaraSpoofProfiles.all(ctx.contentResolver) }
    var current by remember { mutableStateOf(YasvaraSpoofProfiles.profileFor(ctx.contentResolver, pkg)?.id) }
    val ids = listOf<String?>(null) + all.map { it.id }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ids.forEachIndexed { i, id ->
            Choice(all.firstOrNull { it.id == id }?.name ?: "Real device", id == current, i, ids.size) {
                current = id
                YasvaraSpoofProfiles.assign(ctx, pkg, id)
            }
        }
    }
}
