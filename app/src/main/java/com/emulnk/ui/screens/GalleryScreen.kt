package com.emulnk.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.emulnk.R
import com.emulnk.model.GalleryConsole
import com.emulnk.model.GalleryGame
import com.emulnk.model.GalleryIndex
import com.emulnk.model.GalleryTheme
import com.emulnk.model.ScreenTarget
import com.emulnk.model.StoreWidget
import com.emulnk.model.ThemeConfig
import com.emulnk.model.ThemeType
import com.emulnk.model.resolvedType
import com.emulnk.ui.theme.*

private fun List<GalleryTheme>.filterForScreen(isDualScreen: Boolean) =
    if (!isDualScreen) filter { it.type == ThemeType.OVERLAY } else this

private fun filterGame(game: GalleryGame, isDualScreen: Boolean, selectedTags: Set<String>): GalleryGame? {
    val themes = game.themes.filterForScreen(isDualScreen).let { result ->
        if (selectedTags.isNotEmpty()) result.filter { theme -> selectedTags.all { it in theme.tags } } else result
    }
    return if (themes.isNotEmpty()) game.copy(themes = themes) else null
}

private sealed class GalleryLevel {
    object ConsoleList : GalleryLevel()
    data class GameList(val console: GalleryConsole) : GalleryLevel()
    data class ThemeList(val game: GalleryGame) : GalleryLevel()
}

@Composable
fun GalleryScreen(
    galleryIndex: GalleryIndex?,
    rawBaseUrl: String,
    isSyncing: Boolean,
    allInstalledThemes: List<ThemeConfig>,
    appVersionCode: Int,
    isDualScreen: Boolean,
    storeWidgets: Map<String, List<StoreWidget>> = emptyMap(),
    widgetUpdatesAvailable: Set<String> = emptySet(),
    initialProfileId: String? = null,
    onBack: () -> Unit,
    onImportTheme: () -> Unit,
    onSelectTheme: (ThemeConfig) -> Unit,
    onDownloadTheme: (GalleryTheme) -> Unit,
    onDeleteTheme: (String) -> Unit,
    onFetchWidgets: (console: String, profileId: String) -> Unit = { _, _ -> },
    onDownloadWidgets: (console: String, profileId: String) -> Unit = { _, _ -> },
    onUpdateWidgets: (console: String, profileId: String) -> Unit = { _, _ -> },
    onLaunchBuilder: (profileId: String, console: String) -> Unit = { _, _ -> },
    installedWidgetIds: Map<String, Set<String>> = emptyMap(),
    onDownloadWidget: (console: String, profileId: String, StoreWidget) -> Unit = { _, _, _ -> },
    onDeleteWidget: (profileId: String, StoreWidget) -> Unit = { _, _ -> },
    onDeleteAllWidgets: (profileId: String) -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize().padding(EmuLnkDimens.spacingXl).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(painter = painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back), tint = TextPrimary)
            }
            Spacer(modifier = Modifier.width(EmuLnkDimens.spacingSm))
            Text(stringResource(R.string.gallery_title), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.weight(1f))
            IconButton(onClick = onImportTheme) {
                Icon(painter = painterResource(R.drawable.ic_download), contentDescription = stringResource(R.string.import_theme), tint = TextPrimary, modifier = Modifier.size(20.dp))
            }
        }

        Spacer(modifier = Modifier.height(EmuLnkDimens.spacingSm))

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = BrandPurple,
            divider = { HorizontalDivider(color = DividerColor) }
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(stringResource(R.string.tab_community)) }, selectedContentColor = BrandPurple, unselectedContentColor = TextTertiary)
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(stringResource(R.string.tab_local)) }, selectedContentColor = BrandPurple, unselectedContentColor = TextTertiary)
        }

        Spacer(modifier = Modifier.height(EmuLnkDimens.spacingLg))

        when (selectedTab) {
            0 -> CommunityTab(
                galleryIndex = galleryIndex,
                rawBaseUrl = rawBaseUrl,
                allInstalledThemes = allInstalledThemes,
                isSyncing = isSyncing,
                appVersionCode = appVersionCode,
                isDualScreen = isDualScreen,
                storeWidgets = storeWidgets,
                widgetUpdatesAvailable = widgetUpdatesAvailable,
                initialProfileId = initialProfileId,
                onBack = onBack,
                onSelectTheme = onSelectTheme,
                onDownloadTheme = onDownloadTheme,
                onDeleteTheme = onDeleteTheme,
                onFetchWidgets = onFetchWidgets,
                onDownloadWidgets = onDownloadWidgets,
                onUpdateWidgets = onUpdateWidgets,
                onLaunchBuilder = onLaunchBuilder,
                installedWidgetIds = installedWidgetIds,
                onDownloadWidget = onDownloadWidget,
                onDeleteWidget = onDeleteWidget,
                onDeleteAllWidgets = onDeleteAllWidgets
            )
            1 -> LocalThemeList(
                galleryIndex = galleryIndex,
                allInstalledThemes = allInstalledThemes,
                isSyncing = isSyncing,
                isDualScreen = isDualScreen,
                onDeleteTheme = onDeleteTheme
            )
        }
    }
}

@Composable
private fun CommunityTab(
    galleryIndex: GalleryIndex?,
    rawBaseUrl: String,
    allInstalledThemes: List<ThemeConfig>,
    isSyncing: Boolean,
    appVersionCode: Int,
    isDualScreen: Boolean,
    storeWidgets: Map<String, List<StoreWidget>>,
    widgetUpdatesAvailable: Set<String>,
    initialProfileId: String? = null,
    onBack: () -> Unit,
    onSelectTheme: (ThemeConfig) -> Unit,
    onDownloadTheme: (GalleryTheme) -> Unit,
    onDeleteTheme: (String) -> Unit,
    onFetchWidgets: (console: String, profileId: String) -> Unit,
    onDownloadWidgets: (console: String, profileId: String) -> Unit,
    onUpdateWidgets: (console: String, profileId: String) -> Unit,
    onLaunchBuilder: (profileId: String, console: String) -> Unit,
    installedWidgetIds: Map<String, Set<String>>,
    onDownloadWidget: (console: String, profileId: String, StoreWidget) -> Unit,
    onDeleteWidget: (profileId: String, StoreWidget) -> Unit,
    onDeleteAllWidgets: (profileId: String) -> Unit
) {
    if (galleryIndex == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = BrandPurple)
        }
        return
    }

    var level by remember {
        val initial: GalleryLevel = if (initialProfileId != null) {
            val game = galleryIndex.consoles.flatMap { it.games }
                .firstOrNull { it.profileId == initialProfileId }
            if (game != null) GalleryLevel.ThemeList(game) else GalleryLevel.ConsoleList
        } else GalleryLevel.ConsoleList
        mutableStateOf(initial)
    }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTags by remember { mutableStateOf(setOf<String>()) }
    // Save tag selections when navigating forward, restore on back
    val tagStack = remember { mutableListOf<Set<String>>() }

    val allThemes = remember(galleryIndex) {
        galleryIndex.consoles.flatMap { c -> c.games.flatMap { g -> g.themes } }
    }

    val currentLevel = level

    val visibleTags = remember(currentLevel, galleryIndex) {
        when (currentLevel) {
            is GalleryLevel.ConsoleList -> allThemes.flatMap { it.tags }.toSet()
            is GalleryLevel.GameList -> currentLevel.console.games.flatMap { g -> g.themes.flatMap { it.tags } }.toSet()
            is GalleryLevel.ThemeList -> currentLevel.game.themes.flatMap { it.tags }.toSet()
        }.sorted()
    }

    val onTagToggle: (String) -> Unit = { tag ->
        selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag
    }

    if (searchQuery.isNotEmpty()) {
        val query = searchQuery.lowercase()
        val results = allThemes.filter { theme ->
            theme.name.lowercase().contains(query) ||
            theme.description.lowercase().contains(query) ||
            theme.tags.any { it.lowercase().contains(query) } ||
            theme.console.lowercase().contains(query) ||
            theme.profileId.lowercase().contains(query) ||
            theme.gameName.lowercase().contains(query)
        }.filterForScreen(isDualScreen)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(EmuLnkDimens.spacingLg)) {
            item { GallerySearchBar(searchQuery, { searchQuery = it }) }
            if (results.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = EmuLnkDimens.spacingXl), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_themes_found), color = TextSecondary)
                    }
                }
            } else {
                lazyItems(results, key = { it.id }) { theme ->
                    ThemeCard(theme, allInstalledThemes, isSyncing, appVersionCode, onBack, onSelectTheme, onDownloadTheme, onDeleteTheme)
                }
            }
        }
    } else {
        when (currentLevel) {
            is GalleryLevel.ConsoleList -> ConsoleListView(
                galleryIndex = galleryIndex,
                rawBaseUrl = rawBaseUrl,
                selectedTags = selectedTags,
                isDualScreen = isDualScreen,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                visibleTags = visibleTags,
                onTagToggle = onTagToggle,
                onSelectConsole = {
                    tagStack.add(selectedTags)
                    selectedTags = emptySet()
                    level = GalleryLevel.GameList(it)
                }
            )
            is GalleryLevel.GameList -> GameListView(
                console = currentLevel.console,
                rawBaseUrl = rawBaseUrl,
                selectedTags = selectedTags,
                isDualScreen = isDualScreen,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                visibleTags = visibleTags,
                onTagToggle = onTagToggle,
                onSelectGame = {
                    tagStack.add(selectedTags)
                    selectedTags = emptySet()
                    level = GalleryLevel.ThemeList(it)
                },
                onBack = {
                    selectedTags = tagStack.removeLastOrNull() ?: emptySet()
                    level = GalleryLevel.ConsoleList
                }
            )
            is GalleryLevel.ThemeList -> {
                val filteredThemes = currentLevel.game.themes.let { themes ->
                    var result = themes.filterForScreen(isDualScreen)
                    if (selectedTags.isNotEmpty()) {
                        result = result.filter { theme -> selectedTags.all { it in theme.tags } }
                    }
                    result
                }

                val game = currentLevel.game

                if (game.hasWidgets) {
                    LaunchedEffect(game.profileId) {
                        onFetchWidgets(game.console, game.profileId)
                    }
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(EmuLnkDimens.spacingLg)) {
                    item { GallerySearchBar(searchQuery, { searchQuery = it }) }
                    if (visibleTags.isNotEmpty()) {
                        item { TagChipRow(visibleTags, selectedTags, onTagToggle) }
                    }
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                selectedTags = tagStack.removeLastOrNull() ?: emptySet()
                                val console = galleryIndex.consoles.firstOrNull { it.id == game.console }
                                level = if (console != null) GalleryLevel.GameList(console) else GalleryLevel.ConsoleList
                            }.padding(bottom = EmuLnkDimens.spacingSm)
                        ) {
                            Icon(painter = painterResource(R.drawable.ic_back), contentDescription = null, tint = BrandPurple, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(game.name, color = BrandPurple, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    if (game.hasWidgets) {
                        item {
                            WidgetStoreSection(
                                game = game,
                                widgets = storeWidgets[game.profileId] ?: emptyList(),
                                isSyncing = isSyncing,
                                hasUpdate = game.profileId in widgetUpdatesAvailable,
                                onDownloadWidgets = { onDownloadWidgets(game.console, game.profileId) },
                                onUpdateWidgets = { onUpdateWidgets(game.console, game.profileId) },
                                onLaunchBuilder = { onLaunchBuilder(game.profileId, game.console) },
                                installedWidgetIds = installedWidgetIds[game.profileId] ?: emptySet(),
                                onDownloadWidget = { w -> onDownloadWidget(game.console, game.profileId, w) },
                                onDeleteWidget = { w -> onDeleteWidget(game.profileId, w) },
                                onDeleteAllWidgets = { onDeleteAllWidgets(game.profileId) }
                            )
                        }
                    }
                    if (filteredThemes.isEmpty() && !game.hasWidgets) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = EmuLnkDimens.spacingXl), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.no_themes_found), color = TextSecondary)
                            }
                        }
                    } else {
                        lazyItems(filteredThemes, key = { it.id }) { theme ->
                            ThemeCard(theme, allInstalledThemes, isSyncing, appVersionCode, onBack, onSelectTheme, onDownloadTheme, onDeleteTheme)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsoleListView(
    galleryIndex: GalleryIndex,
    rawBaseUrl: String,
    selectedTags: Set<String>,
    isDualScreen: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    visibleTags: List<String>,
    onTagToggle: (String) -> Unit,
    onSelectConsole: (GalleryConsole) -> Unit
) {
    val filteredConsoles = remember(galleryIndex, selectedTags, isDualScreen) {
        galleryIndex.consoles.mapNotNull { console ->
            val filteredGames = console.games.mapNotNull { filterGame(it, isDualScreen, selectedTags) }
            if (filteredGames.isNotEmpty()) console.copy(games = filteredGames) else null
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        verticalArrangement = Arrangement.spacedBy(EmuLnkDimens.spacingMd),
        horizontalArrangement = Arrangement.spacedBy(EmuLnkDimens.spacingMd)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            GallerySearchBar(searchQuery, onSearchChange)
        }
        if (visibleTags.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                TagChipRow(visibleTags, selectedTags, onTagToggle)
            }
        }
        if (filteredConsoles.isEmpty() && searchQuery.isEmpty() && visibleTags.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(modifier = Modifier.fillMaxWidth().padding(EmuLnkDimens.spacingXl), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(EmuLnkDimens.spacingMd)) {
                        Icon(painter = painterResource(R.drawable.ic_sync), contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp))
                        Text(
                            stringResource(R.string.gallery_failed_to_load), 
                            color = TextSecondary, 
                            fontSize = 14.sp, 
                            textAlign = TextAlign.Center 
                        )
                    }
                }
            }
        } else {
            items(filteredConsoles) { console ->
                val themeCount = console.games.sumOf { it.themes.size }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceRaised),
                    shape = RoundedCornerShape(EmuLnkDimens.cornerMd),
                    onClick = { onSelectConsole(console) }
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(EmuLnkDimens.spacingSm),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(EmuLnkDimens.cornerSm)).background(SurfaceBase),
                            contentAlignment = Alignment.Center
                        ) {
                            val iconUrl = "file:///android_asset/icons/${console.id.uppercase()}.svg"
                            AsyncImage(
                                model = iconUrl,
                                contentDescription = console.id,
                                modifier = Modifier.fillMaxSize().padding(6.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                        Text(console.id, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.padding(top = EmuLnkDimens.spacingSm))
                        Text(
                            stringResource(R.string.theme_count, themeCount),
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GameListView(
    console: GalleryConsole,
    rawBaseUrl: String,
    selectedTags: Set<String>,
    isDualScreen: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    visibleTags: List<String>,
    onTagToggle: (String) -> Unit,
    onSelectGame: (GalleryGame) -> Unit,
    onBack: () -> Unit
) {
    val filteredGames = remember(console, selectedTags, isDualScreen) {
        console.games.mapNotNull { filterGame(it, isDualScreen, selectedTags) }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        verticalArrangement = Arrangement.spacedBy(EmuLnkDimens.spacingMd),
        horizontalArrangement = Arrangement.spacedBy(EmuLnkDimens.spacingMd)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            GallerySearchBar(searchQuery, onSearchChange)
        }
        if (visibleTags.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                TagChipRow(visibleTags, selectedTags, onTagToggle)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onBack).padding(bottom = EmuLnkDimens.spacingSm)
            ) {
                Icon(painter = painterResource(R.drawable.ic_back), contentDescription = null, tint = BrandPurple, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(console.id, color = BrandPurple, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        items(filteredGames) { game ->
            val themeCount = game.themes.size
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceRaised),
                shape = RoundedCornerShape(EmuLnkDimens.cornerMd),
                onClick = { onSelectGame(game) }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(EmuLnkDimens.spacingSm),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(EmuLnkDimens.cornerSm)).background(SurfaceBase),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            game.name,
                            fontSize = 12.sp,
                            color = TextPrimary.copy(alpha = 0.3f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(EmuLnkDimens.spacingSm)
                        )
                        val coverUrl = "$rawBaseUrl/covers/${game.profileId}.webp"
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = game.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.height(EmuLnkDimens.spacingSm))
                    Text(
                        game.name,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        stringResource(R.string.theme_count, themeCount),
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeCard(
    theme: GalleryTheme,
    allInstalledThemes: List<ThemeConfig>,
    isSyncing: Boolean,
    appVersionCode: Int,
    onBack: () -> Unit,
    onSelectTheme: (ThemeConfig) -> Unit,
    onDownloadTheme: (GalleryTheme) -> Unit,
    onDeleteTheme: (String) -> Unit
) {
    val minVersion = theme.minAppVersion
    val isIncompatible = minVersion > appVersionCode
    val compositeId = "${theme.profileId}_${theme.id}"
    val localTheme = allInstalledThemes.find { it.id == compositeId }
    val isInstalled = localTheme != null

    val localVersion = localTheme?.meta?.version ?: "1.0.0"
    val remoteVersion = theme.version ?: "1.0.0"
    val isOutdated = isInstalled && isNewerVersion(remoteVersion, localVersion)

    val cardBorder = when {
        isOutdated -> BorderStroke(1.dp, BrandPurple.copy(alpha = 0.5f))
        isInstalled -> BorderStroke(1.dp, BrandPurple.copy(alpha = 0.3f))
        else -> null
    }

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceRaised), shape = RoundedCornerShape(EmuLnkDimens.cornerMd), border = cardBorder) {
        Row(modifier = Modifier.padding(EmuLnkDimens.spacingLg), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(80.dp).clip(RoundedCornerShape(EmuLnkDimens.cornerSm)).background(SurfaceBase), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = theme.previewUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Spacer(modifier = Modifier.width(EmuLnkDimens.spacingLg))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EmuLnkDimens.spacingSm)) {
                    Text(theme.name, fontWeight = FontWeight.Bold, color = TextPrimary)
                    val badgeColor = when (theme.type) {
                        ThemeType.OVERLAY -> BrandCyan
                        ThemeType.BUNDLE -> StatusWarning
                        else -> BrandPurple
                    }
                    Box(modifier = Modifier.background(badgeColor, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(theme.type.uppercase(), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = SurfaceBase)
                    }
                }
                Text("v$remoteVersion by ${theme.author}", fontSize = 11.sp, color = TextSecondary)
                if (isIncompatible) {
                    Text(stringResource(R.string.requires_app_version, minVersion), fontSize = 11.sp, color = StatusError, fontWeight = FontWeight.Bold)
                } else if (isOutdated) {
                    Text(stringResource(R.string.update_available, localVersion), fontSize = 11.sp, color = BrandPurple, fontWeight = FontWeight.Bold)
                } else if (isInstalled) {
                    Text(stringResource(R.string.installed), fontSize = 11.sp, color = BrandPurple, fontWeight = FontWeight.Bold)
                } else {
                    Text(theme.description, fontSize = 11.sp, color = TextSecondary, maxLines = 2)
                }

                if (theme.tags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        theme.tags.take(4).forEach { tag ->
                            Box(
                                modifier = Modifier
                                    .background(BrandPurple.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                    .border(1.dp, BrandPurple.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(tag, fontSize = 9.sp, color = TextSecondary)
                            }
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EmuLnkDimens.spacingSm)) {
                if (isInstalled) {
                    IconButton(
                        onClick = { onDeleteTheme(compositeId) },
                        enabled = !isSyncing,
                        modifier = Modifier.background(SurfaceBase.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(painter = painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.remove), tint = StatusError, modifier = Modifier.size(20.dp))
                    }
                }

                Button(
                    onClick = {
                        if (localTheme != null && !isOutdated) {
                            onSelectTheme(localTheme)
                            onBack()
                        } else {
                            onDownloadTheme(theme)
                        }
                    },
                    enabled = !isSyncing && !isIncompatible,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isIncompatible) TextTertiary else BrandPurple
                    ),
                    modifier = Modifier.size(48.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape = CircleShape
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TextPrimary, strokeWidth = 2.dp)
                    } else {
                        val iconRes = when {
                            isIncompatible -> R.drawable.ic_upgrade
                            isOutdated -> R.drawable.ic_download
                            isInstalled -> R.drawable.ic_play_arrow
                            else -> R.drawable.ic_download
                        }
                        Icon(painter = painterResource(iconRes), contentDescription = null, tint = TextPrimary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalThemeList(
    galleryIndex: GalleryIndex?,
    allInstalledThemes: List<ThemeConfig>,
    isSyncing: Boolean,
    isDualScreen: Boolean,
    onDeleteTheme: (String) -> Unit
) {
    val repoThemeIds = remember(galleryIndex) {
        galleryIndex?.consoles?.flatMap { c -> c.games.flatMap { g -> g.themes.map { "${g.profileId}_${it.id}" } } }?.toSet() ?: emptySet()
    }
    val gameNameMap = remember(galleryIndex) {
        galleryIndex?.consoles?.flatMap { c -> c.games.map { g -> g.profileId to g.name } }?.toMap() ?: emptyMap()
    }
    val displayThemes = allInstalledThemes
        .let { themes ->
            if (isDualScreen) themes else themes.filter { it.resolvedType == ThemeType.OVERLAY }
        }

    if (displayThemes.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_installed_themes), color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(EmuLnkDimens.spacingLg)) {
            lazyItems(displayThemes, key = { it.id }) { theme ->
                val isImported = theme.id !in repoThemeIds
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceRaised), shape = RoundedCornerShape(EmuLnkDimens.cornerMd)) {
                    Row(modifier = Modifier.padding(EmuLnkDimens.spacingLg), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(80.dp).clip(RoundedCornerShape(EmuLnkDimens.cornerSm)).background(SurfaceBase), contentAlignment = Alignment.Center) {
                            Text(theme.targetProfileId.take(2), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary.copy(alpha = 0.3f))
                        }
                        Spacer(modifier = Modifier.width(EmuLnkDimens.spacingLg))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EmuLnkDimens.spacingSm)) {
                                Text(theme.meta.name, fontWeight = FontWeight.Bold, color = TextPrimary)
                                val localType = theme.resolvedType
                                val badgeColor = when (localType) {
                                    ThemeType.OVERLAY -> BrandCyan
                                    ThemeType.BUNDLE -> StatusWarning
                                    else -> BrandPurple
                                }
                                Box(modifier = Modifier.background(badgeColor, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                    Text(localType.uppercase(), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = SurfaceBase)
                                }
                                if (isImported) {
                                    Box(modifier = Modifier.background(StatusWarning, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                        Text(stringResource(R.string.imported), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = SurfaceBase)
                                    }
                                }
                            }
                            Text("v${theme.meta.version ?: "1.0.0"} by ${theme.meta.author}", fontSize = 11.sp, color = TextSecondary)
                            val gameName = gameNameMap[theme.targetProfileId]
                            Text(gameName ?: theme.targetProfileId, fontSize = 11.sp, color = BrandPurple)
                        }

                        IconButton(
                            onClick = { onDeleteTheme(theme.id) },
                            enabled = !isSyncing,
                            modifier = Modifier.background(SurfaceBase.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(painter = painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.delete), tint = StatusError, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetStoreSection(
    game: GalleryGame,
    widgets: List<StoreWidget>,
    isSyncing: Boolean,
    hasUpdate: Boolean = false,
    onDownloadWidgets: () -> Unit,
    onUpdateWidgets: () -> Unit = {},
    onLaunchBuilder: () -> Unit,
    installedWidgetIds: Set<String> = emptySet(),
    onDownloadWidget: (StoreWidget) -> Unit = {},
    onDeleteWidget: (StoreWidget) -> Unit = {},
    onDeleteAllWidgets: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BrandPurple.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(EmuLnkDimens.cornerMd),
        border = BorderStroke(1.dp, BrandPurple.copy(alpha = 0.3f))
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(EmuLnkDimens.spacingLg)
            ) {
                Icon(painter = painterResource(R.drawable.ic_layers), contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(EmuLnkDimens.spacingSm))
                Text(
                    stringResource(R.string.widget_store_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                if (widgets.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(EmuLnkDimens.spacingSm))
                    Box(
                        modifier = Modifier.background(BrandPurple, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("${widgets.size}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SurfaceBase)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    painter = painterResource(R.drawable.ic_keyboard_arrow_down),
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp).rotate(chevronRotation)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(horizontal = EmuLnkDimens.spacingLg, vertical = EmuLnkDimens.spacingLg)) {
                    if (widgets.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(EmuLnkDimens.spacingMd), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = BrandPurple, modifier = Modifier.size(24.dp))
                        }
                    } else {
                        if (hasUpdate) {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(bottom = EmuLnkDimens.spacingSm),
                                colors = CardDefaults.cardColors(containerColor = BrandPurple.copy(alpha = 0.15f)),
                                shape = RoundedCornerShape(EmuLnkDimens.cornerSm)
                            ) {
                                Row(
                                    modifier = Modifier.padding(EmuLnkDimens.spacingMd),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        stringResource(R.string.widgets_update_available),
                                        fontSize = 13.sp,
                                        color = TextPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = onUpdateWidgets,
                                        enabled = !isSyncing,
                                        colors = ButtonDefaults.buttonColors(containerColor = BrandPurple),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                                    ) {
                                        Text(stringResource(R.string.widgets_update_btn), color = TextPrimary, fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = EmuLnkDimens.spacingSm),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onDownloadWidgets,
                                enabled = !isSyncing,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Box(
                                    modifier = Modifier.size(28.dp).background(BrandPurple, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(painter = painterResource(R.drawable.ic_download), contentDescription = "Download All", tint = TextPrimary, modifier = Modifier.size(14.dp))
                                }
                            }
                            val hasInstalledAny = widgets.any { it.id in installedWidgetIds }
                            if (hasInstalledAny) {
                                Spacer(modifier = Modifier.width(EmuLnkDimens.spacingSm))
                                IconButton(
                                    onClick = onDeleteAllWidgets,
                                    enabled = !isSyncing,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(
                                        modifier = Modifier.size(28.dp).background(SurfaceBase.copy(alpha = 0.5f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(painter = painterResource(R.drawable.ic_delete), contentDescription = "Delete All", tint = StatusError, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }

                        widgets.forEachIndexed { index, widget ->
                            val widgetInstalled = widget.id in installedWidgetIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = EmuLnkDimens.spacingSm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(SurfaceBase),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (widget.previewUrl != null) {
                                        AsyncImage(
                                            model = widget.previewUrl,
                                            contentDescription = widget.label,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Text(
                                            widget.label.take(1),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary.copy(alpha = 0.3f)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(EmuLnkDimens.spacingMd))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        widget.label,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (widget.description.isNotEmpty()) {
                                        Text(
                                            widget.description,
                                            fontSize = 11.sp,
                                            color = TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = { if (widgetInstalled) onDeleteWidget(widget) else onDownloadWidget(widget) },
                                    enabled = !isSyncing,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    if (isSyncing) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = TextSecondary, strokeWidth = 2.dp)
                                    } else if (widgetInstalled) {
                                        Box(
                                            modifier = Modifier.size(28.dp).background(SurfaceBase.copy(alpha = 0.5f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_delete),
                                                contentDescription = "Delete",
                                                tint = StatusError,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier.size(28.dp).background(BrandPurple, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_download),
                                                contentDescription = "Download",
                                                tint = TextPrimary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            if (index < widgets.lastIndex) {
                                HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                            }
                        }

                        Spacer(modifier = Modifier.height(EmuLnkDimens.spacingLg))

                        OutlinedButton(
                            onClick = onLaunchBuilder,
                            enabled = !isSyncing,
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandPurple),
                            border = BorderStroke(1.dp, BrandPurple.copy(alpha = 0.5f)),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Icon(painter = painterResource(R.drawable.ic_layers), contentDescription = null, tint = BrandPurple, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.launch_overlay_builder), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GallerySearchBar(searchQuery: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = searchQuery,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.search_themes), color = TextTertiary) },
        leadingIcon = { Icon(painter = painterResource(R.drawable.ic_search), contentDescription = null, tint = TextTertiary, modifier = Modifier.size(20.dp)) },
        trailingIcon = {
            if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(painter = painterResource(R.drawable.ic_close), contentDescription = null, tint = TextTertiary, modifier = Modifier.size(20.dp))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(EmuLnkDimens.cornerSm),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = BrandPurple,
            unfocusedBorderColor = DividerColor,
            cursorColor = BrandPurple,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary
        )
    )
}

@Composable
private fun TagChipRow(visibleTags: List<String>, selectedTags: Set<String>, onTagToggle: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(EmuLnkDimens.spacingSm)
    ) {
        visibleTags.forEach { tag ->
            val isSelected = tag in selectedTags
            FilterChip(
                selected = isSelected,
                onClick = { onTagToggle(tag) },
                label = { Text(tag, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = BrandPurple.copy(alpha = 0.2f),
                    selectedLabelColor = BrandPurple,
                    containerColor = SurfaceRaised,
                    labelColor = TextSecondary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = DividerColor,
                    selectedBorderColor = BrandPurple,
                    enabled = true,
                    selected = isSelected
                )
            )
        }
    }
}

/** Returns true if [remote] is a newer semver than [local]. */
private fun isNewerVersion(remote: String, local: String): Boolean {
    val r = remote.split(".").mapNotNull { it.toIntOrNull() }
    val l = local.split(".").mapNotNull { it.toIntOrNull() }
    for (i in 0 until maxOf(r.size, l.size)) {
        val rv = r.getOrElse(i) { 0 }
        val lv = l.getOrElse(i) { 0 }
        if (rv != lv) return rv > lv
    }
    return false
}
