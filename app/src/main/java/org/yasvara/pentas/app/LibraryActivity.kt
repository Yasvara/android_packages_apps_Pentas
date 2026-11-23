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
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yasvara.pentas.R
import org.yasvara.pentas.cache.AppIconCache
import org.yasvara.pentas.cache.BannerBitmapCache
import org.yasvara.pentas.data.AppClassifier
import org.yasvara.pentas.data.AppSettings
import org.yasvara.pentas.data.PerAppSettingStore
import org.yasvara.pentas.data.SystemSettings
import org.yasvara.pentas.data.banner.BannerSource
import org.yasvara.pentas.data.banner.GameBannerRepository
import org.yasvara.pentas.data.fpsstats.FpsStatsRepository
import org.yasvara.pentas.data.model.AppRef
import org.yasvara.pentas.data.repo.InstalledAppsRepository
import org.yasvara.pentas.session.SessionService
import org.yasvara.pentas.settings.fpsstats.FpsStatsActivity
import org.yasvara.pentas.thermal.ThermalProfiles
import org.yasvara.pentas.ui.kit.FloatingPillBar
import org.yasvara.pentas.ui.kit.Glyph
import org.yasvara.pentas.ui.kit.Kit
import org.yasvara.pentas.ui.kit.RoundButton
import org.yasvara.pentas.ui.kit.SplitActionButton
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num
import org.yasvara.pentas.ui.theme.PentasTheme
import java.util.Locale

/** The launcher entry: adaptive M3 Expressive game library (Portrait V1 + Landscape H2). */
class LibraryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { PentasTheme { Library() } }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LibraryDeps {
    fun system(): SystemSettings
    fun settings(): AppSettings
    fun apps(): InstalledAppsRepository
    fun banners(): GameBannerRepository
    fun bitmaps(): BannerBitmapCache
    fun icons(): AppIconCache
    fun perApp(): PerAppSettingStore
    fun fpsStats(): FpsStatsRepository
}

private data class GameEntry(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val isLive: Boolean,
    val thermalLabel: String,
    val resolutionLabel: String,
    val fpsAvg: Float?,
)

private fun loadGames(ctx: Context, deps: LibraryDeps): List<GameEntry> {
    val pm = ctx.packageManager
    val system = deps.system()
    val perApp = deps.perApp()
    val icons = deps.icons()
    val livePkg = Settings.System.getString(ctx.contentResolver, SessionService.KEY_SESSION_LIVE).orEmpty()
    val customJson = Settings.System.getStringForUser(
        ctx.contentResolver, ThermalProfiles.KEY_CUSTOM_PROFILES, UserHandle.USER_CURRENT,
    )
    val latestFps = HashMap<String, Float>()
    for (session in deps.fpsStats().listSessions()) {
        if (session.fpsAvg > 0f && session.packageName !in latestFps) {
            latestFps[session.packageName] = session.fpsAvg
        }
    }

    val detected = if (!system.autoGameDetect) emptyList() else {
        pm.getInstalledApplications(0)
            .filter { AppClassifier.isGame(it) && pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { it.packageName }
    }

    return (system.games + detected).distinct()
        .mapNotNull { pkg ->
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            val name = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(pkg)
            val thermalIdx = perApp.thermalProfile(pkg)
            val thermal = if (thermalIdx == ThermalProfiles.PROFILE_INHERIT) "Global" else ThermalProfiles.nameOf(thermalIdx, customJson)
            val scale = perApp.resolution(pkg)
            val res = if (scale == "1.0") "Native 1.0×" else "${scale}×"
            GameEntry(
                packageName = pkg,
                label = name,
                icon = icons.icon(pkg, 128),
                isLive = pkg == livePkg,
                thermalLabel = thermal,
                resolutionLabel = res,
                fpsAvg = latestFps[pkg],
            )
        }
        .sortedWith(compareByDescending<GameEntry> { it.isLive }.thenBy { it.label.lowercase() })
}

@Composable
private fun rememberBanner(pkg: String, deps: LibraryDeps, reqW: Int): ImageBitmap? {
    val autoFetch = deps.settings().bannerAutoFetch
    val sourceKey = deps.settings().bannerSource
    val bmp by produceState<ImageBitmap?>(null, pkg, reqW, autoFetch, sourceKey) {
        value = withContext(Dispatchers.IO) {
            val cached = deps.banners().getCached(pkg)?.localPath
            val path = cached ?: if (autoFetch) {
                deps.banners().getOrFetch(pkg, BannerSource.fromKey(sourceKey))?.localPath
            } else null
            path?.let { deps.bitmaps().get(it, reqW)?.asImageBitmap() }
        }
    }
    return bmp
}

private fun launchGame(ctx: Context, pkg: String) {
    ctx.packageManager.getLaunchIntentForPackage(pkg)?.let(ctx::startActivity)
}

@Composable
private fun Library() {
    val ctx = LocalContext.current
    val deps = remember { EntryPointAccessors.fromApplication(ctx.applicationContext, LibraryDeps::class.java) }
    var rev by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var compact by remember { deps.settings().libraryCompactState }

    val games by produceState(emptyList<GameEntry>(), rev) {
        value = withContext(Dispatchers.IO) { loadGames(ctx, deps) }
    }

    val filtered = remember(games, query) {
        val q = query.trim()
        if (q.isEmpty()) games else games.filter { it.label.contains(q, ignoreCase = true) }
    }

    BackHandler(searching) {
        searching = false
        query = ""
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val isLandscape = maxWidth > maxHeight
        if (isLandscape) {
            LandscapeLibrary(
                games = filtered,
                deps = deps,
                searching = searching,
                query = query,
                onQueryChange = { query = it },
                onToggleSearch = {
                    searching = !searching
                    if (!searching) query = ""
                },
                onAdd = { adding = true },
            )
        } else {
            PortraitLibrary(
                games = filtered,
                deps = deps,
                compact = compact,
                onToggleCompact = {
                    val next = !compact
                    compact = next
                    deps.settings().libraryCompact = next
                },
                searching = searching,
                query = query,
                onQueryChange = { query = it },
                onToggleSearch = {
                    searching = !searching
                    if (!searching) query = ""
                },
                onAdd = { adding = true },
            )
        }
    }

    if (adding) {
        AddGameSheet(
            deps = deps,
            existing = games.mapTo(HashSet()) { it.packageName },
            onDone = { adding = false; rev++ },
        )
    }
}

@Composable
private fun PortraitLibrary(
    games: List<GameEntry>,
    deps: LibraryDeps,
    compact: Boolean,
    onToggleCompact: () -> Unit,
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onAdd: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val showHero = !searching && games.isNotEmpty()
    val hero = if (showHero) games.first() else null
    val listGames = if (showHero) games.drop(1) else games

    Box(Modifier.fillMaxSize().background(cs.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnimatedContent(searching, Modifier.weight(1f), label = "portrait_header") { isSearch ->
                    if (isSearch) {
                        SearchPill(query, onQueryChange, Modifier.fillMaxWidth())
                    } else {
                        Text(
                            "Pentas",
                            style = label(28.sp, 650),
                            color = cs.onSurface,
                            maxLines = 1,
                        )
                    }
                }
                RoundButton(
                    icon = if (compact) R.drawable.sym_view_carousel else R.drawable.sym_view_list,
                    desc = if (compact) "Grid view" else "Compact list",
                    onClick = onToggleCompact,
                    size = 38.dp,
                )
                RoundButton(
                    icon = if (searching) R.drawable.sym_close else R.drawable.sym_search,
                    desc = if (searching) "Close search" else "Search games",
                    onClick = onToggleSearch,
                    size = 38.dp,
                    active = searching,
                )
            }

            if (games.isEmpty()) {
                EmptyLibraryState(searching, Modifier.fillMaxSize().padding(bottom = 84.dp))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (compact) 1 else 2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = if (searching) 24.dp else 104.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 10.dp),
                ) {
                    hero?.let { h ->
                        item(key = "hero_${h.packageName}", span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.padding(bottom = 4.dp)) {
                                PortraitHeroCard(h, deps)
                            }
                        }
                    }
                    itemsIndexed(listGames, key = { _, g -> g.packageName }) { index, game ->
                        if (compact) {
                            CompactGameRow(
                                game = game,
                                index = index,
                                count = listGames.size,
                                onPlay = { launchGame(ctx, game.packageName) },
                                onTune = { GameActivity.start(ctx, game.packageName) },
                            )
                        } else {
                            SplitTileGameCard(game, deps)
                        }
                    }
                }
            }
        }

        if (!searching) {
            FloatingPillBar(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
                vertical = false,
            ) {
                RoundButton(R.drawable.sym_monitoring, "Sessions", { ctx.startActivity(Intent(ctx, FpsStatsActivity::class.java)) }, size = 40.dp)
                RoundButton(R.drawable.sym_tune, "Global clocks", { ctx.startActivity(Intent(ctx, GlobalActivity::class.java)) }, size = 40.dp)
                RoundButton(R.drawable.sym_settings, "Settings", { ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) }, size = 40.dp)
                RoundButton(R.drawable.sym_add, "Add game", onAdd, size = 40.dp, active = true)
            }
        }
    }
}

@Composable
private fun PortraitHeroCard(game: GameEntry, deps: LibraryDeps) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val banner = rememberBanner(game.packageName, deps, reqW = 960)
    val meta = buildString {
        append(game.thermalLabel)
        game.fpsAvg?.let { append(String.format(Locale.US, " · %.1f FPS", it)) }
    }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(2f)
                .clip(RoundedCornerShape(28.dp, 28.dp, 6.dp, 6.dp))
                .background(cs.surfaceContainerHigh)
                .clickable { GameActivity.start(ctx, game.packageName) },
        ) {
            banner?.let {
                Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            if (game.isLive) {
                Text(
                    "Playing",
                    Modifier.align(Alignment.TopEnd).padding(10.dp)
                        .clip(CircleShape).background(cs.primary)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    style = label(11.sp, 650),
                    color = cs.onPrimary,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp, 6.dp, 28.dp, 28.dp))
                .background(cs.surfaceContainer)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            game.icon?.let {
                Image(it, null, Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(game.label, style = label(15.sp, 650), color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(meta, style = label(12.sp, 500), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SplitActionButton(
                primaryLabel = "Play",
                primaryIcon = R.drawable.sym_play_arrow,
                onPrimary = { launchGame(ctx, game.packageName) },
                secondaryIcon = R.drawable.sym_tune,
                secondaryDesc = "Tune ${game.label}",
                onSecondary = { GameActivity.start(ctx, game.packageName) },
                height = 38.dp,
            )
        }
    }
}

@Composable
private fun SplitTileGameCard(game: GameEntry, deps: LibraryDeps) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val banner = rememberBanner(game.packageName, deps, reqW = 540)
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val outer by animateDpAsState(if (pressed) 16.dp else 24.dp, Kit.fast(), label = "tile_r")

    Column(
        Modifier.fillMaxWidth().clickable(src, null) { GameActivity.start(ctx, game.packageName) },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(1.85f)
                .clip(RoundedCornerShape(outer, outer, 6.dp, 6.dp))
                .background(cs.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (banner != null) {
                Image(banner, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                game.icon?.let { Image(it, null, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))) }
            }
            if (game.isLive) {
                Text(
                    "Playing",
                    Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .clip(CircleShape).background(cs.primary)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    style = label(10.sp, 650),
                    color = cs.onPrimary,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp, 6.dp, outer, outer))
                .background(cs.surfaceContainer)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                game.label,
                style = label(13.sp, 600),
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            game.fpsAvg?.let {
                Text(
                    String.format(Locale.US, "%.0f", it),
                    style = num(11.sp, 650),
                    color = cs.primary,
                )
            }
        }
    }
}

@Composable
private fun CompactGameRow(
    game: GameEntry,
    index: Int,
    count: Int,
    onPlay: () -> Unit,
    onTune: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val sub = buildString {
        if (game.isLive) append("Playing · ")
        game.fpsAvg?.let { append(String.format(Locale.US, "%.1f FPS · ", it)) }
        append(game.thermalLabel)
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(Kit.connected(index, count, 24.dp, 6.dp))
            .background(cs.surfaceContainer)
            .clickable(onClick = onTune)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        game.icon?.let {
            Image(it, null, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
        } ?: Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(game.label, style = label(14.sp, 600), color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = label(11.5.sp, 500), color = if (game.isLive) cs.primary else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RoundButton(R.drawable.sym_play_arrow, "Play ${game.label}", onPlay, size = 34.dp, active = true)
        RoundButton(R.drawable.sym_tune, "Tune ${game.label}", onTune, size = 34.dp)
    }
}

@Composable
private fun LandscapeLibrary(
    games: List<GameEntry>,
    deps: LibraryDeps,
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onAdd: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val dockState = rememberLazyListState()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { games.size.coerceAtLeast(1) })
    val activeIndex = if (games.isEmpty()) 0 else pagerState.currentPage.coerceIn(0, games.lastIndex)
    val selected = games.getOrNull(activeIndex)

    LaunchedEffect(activeIndex, games.size) {
        if (games.isNotEmpty()) dockState.animateScrollToItem(activeIndex)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Selective bottom-left blur: artwork on the top/right stays crisp and recognizable,
        // while the left stage & bottom dock sit over a soft frosted-blur + dark matte scrim.
        Crossfade(selected?.packageName, label = "landscape_backdrop") { pkg ->
            val banner = pkg?.let { rememberBanner(it, deps, reqW = 1200) }
            if (banner != null) {
                Box(Modifier.fillMaxSize()) {
                    Image(
                        bitmap = banner,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    Image(
                        bitmap = banner,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                            .blur(18.dp)
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            Color.Black,
                                            Color.Black.copy(alpha = 0.88f),
                                            Color.Transparent,
                                        ),
                                        start = Offset(0f, size.height),
                                        end = Offset(size.width * 0.85f, size.height * 0.08f),
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            },
                        contentScale = ContentScale.Crop,
                    )
                }
            } else {
                Box(Modifier.fillMaxSize().background(cs.background))
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.82f),
                        Color.Black.copy(alpha = 0.52f),
                        Color.Black.copy(alpha = 0.24f),
                    ),
                    start = Offset(0f, Float.POSITIVE_INFINITY),
                    end = Offset(Float.POSITIVE_INFINITY, 0f),
                )
            )
        )

        Row(
            Modifier.fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .displayCutoutPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FloatingPillBar(vertical = true) {
                RoundButton(R.drawable.sym_add, "Add game", onAdd, size = 40.dp, active = true)
                RoundButton(R.drawable.sym_monitoring, "Sessions", { ctx.startActivity(Intent(ctx, FpsStatsActivity::class.java)) }, size = 40.dp)
                RoundButton(R.drawable.sym_tune, "Global clocks", { ctx.startActivity(Intent(ctx, GlobalActivity::class.java)) }, size = 40.dp)
                RoundButton(R.drawable.sym_settings, "Settings", { ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) }, size = 40.dp)
            }

            Column(Modifier.weight(1f).fillMaxSize()) {
                // Top-left header only; top-right stays completely clean.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Pentas", style = label(22.sp, 650), color = Color.White)
                    RoundButton(
                        icon = if (searching) R.drawable.sym_close else R.drawable.sym_search,
                        desc = if (searching) "Close search" else "Search games",
                        onClick = onToggleSearch,
                        size = 36.dp,
                        active = searching,
                    )
                    if (searching) {
                        SearchPill(query, onQueryChange, Modifier.width(240.dp))
                    }
                }

                if (games.isNotEmpty()) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        key = { idx -> games.getOrNull(idx)?.packageName ?: idx },
                    ) { page ->
                        val game = games[page]
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    game.label,
                                    style = label(32.sp, 700),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SpecPill(game.thermalLabel)
                                    SpecPill(game.resolutionLabel)
                                    game.fpsAvg?.let { SpecPill(String.format(Locale.US, "%.1f FPS", it)) }
                                    if (game.isLive) SpecPill("Playing", highlight = true)
                                }
                                SplitActionButton(
                                    primaryLabel = "Play",
                                    primaryIcon = R.drawable.sym_play_arrow,
                                    onPrimary = { launchGame(ctx, game.packageName) },
                                    secondaryIcon = R.drawable.sym_tune,
                                    secondaryDesc = "Tune ${game.label}",
                                    onSecondary = { GameActivity.start(ctx, game.packageName) },
                                    height = 44.dp,
                                )
                            }
                        }
                    }

                    // Bottom 1:1 app icon console dock synced with HorizontalPager.
                    LazyRow(
                        state = dockState,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        itemsIndexed(games, key = { _, g -> g.packageName }) { index, game ->
                            val active = index == activeIndex
                            DockIconTile(
                                game = game,
                                active = active,
                                onClick = {
                                    if (active) launchGame(ctx, game.packageName)
                                    else scope.launch { pagerState.animateScrollToPage(index) }
                                },
                            )
                        }
                    }
                } else {
                    EmptyLibraryState(searching, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun DockIconTile(game: GameEntry, active: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val size by animateDpAsState(if (active) 64.dp else 56.dp, Kit.default(), label = "dock_sz")
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(size)
                .clip(RoundedCornerShape(18.dp))
                .background(cs.surfaceContainerHigh)
                .then(if (active) Modifier.border(2.dp, cs.primary, RoundedCornerShape(18.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            game.icon?.let {
                Image(it, null, Modifier.fillMaxSize().padding(if (active) 4.dp else 0.dp).clip(RoundedCornerShape(14.dp)))
            }
        }
        Text(
            game.label,
            style = label(11.sp, if (active) 650 else 500),
            color = if (active) Color.White else Color.White.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SpecPill(text: String, highlight: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        Modifier.clip(CircleShape)
            .background(if (highlight) cs.primary else cs.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        style = label(12.sp, 600),
        color = if (highlight) cs.onPrimary else cs.onSurface,
    )
}

@Composable
private fun SearchPill(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.height(38.dp).clip(CircleShape).background(cs.surfaceContainerHigh).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Glyph(R.drawable.sym_search, cs.onSurfaceVariant, 18.dp)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Search games", style = label(14.sp), color = cs.onSurfaceVariant)
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = label(14.sp).copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
            )
        }
    }
}

@Composable
private fun EmptyLibraryState(searching: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            if (searching) "No matching games" else "No games added yet",
            style = label(14.sp, 550),
            color = cs.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddGameSheet(deps: LibraryDeps, existing: Set<String>, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var all by remember { mutableStateOf(emptyList<AppRef>()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { all = deps.apps().launchableApps().filter { it.packageName !in existing } }
    val filtered = remember(all, query) {
        if (query.isBlank()) all else all.filter { it.label.contains(query, true) }
    }

    ModalBottomSheet(onDismissRequest = onDone, containerColor = cs.surfaceContainerLow) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(CircleShape)
                    .background(cs.surfaceContainerHigh).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Glyph(R.drawable.sym_search, cs.onSurfaceVariant, 20.dp)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search apps", style = label(15.sp), color = cs.onSurfaceVariant)
                    BasicTextField(
                        query,
                        { query = it },
                        Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = label(15.sp).copy(color = cs.onSurface),
                        cursorBrush = SolidColor(cs.primary),
                    )
                }
            }
            LazyColumn(
                Modifier.fillMaxWidth().height(360.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    val bmp by produceState<ImageBitmap?>(null, app.packageName) {
                        value = withContext(Dispatchers.IO) { deps.icons().icon(app.packageName, 96) }
                    }
                    Row(
                        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp))
                            .clickable { deps.system().addGame(app.packageName); onDone() }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        bmp?.let { Image(it, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))) }
                        Text(app.label, style = label(15.sp, 550), color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
