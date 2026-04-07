package com.emulnk.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emulnk.R
import com.emulnk.core.model.MatchConfidence
import com.emulnk.model.AppConfig
import com.emulnk.model.ThemeConfig
import com.emulnk.model.ThemeType
import com.emulnk.model.resolvedType
import com.emulnk.model.SavedOverlayConfig
import com.emulnk.ui.components.PairingBottomSheet
import com.emulnk.ui.components.ThemeCard
import com.emulnk.ui.theme.*

private fun AppConfig.isDefaultForGame(gameId: String?, itemId: String): Boolean {
    if (gameId == null) return false
    return defaultThemes[gameId] == itemId ||
        (defaultOverlays ?: emptyMap())[gameId] == itemId ||
        defaultBundles[gameId]?.let {
            it.primaryOverlayId == itemId || it.secondaryOverlayId == itemId
        } == true
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherScreen(
    detectedGameId: String?,
    themes: List<ThemeConfig>,
    isSyncing: Boolean,
    appConfig: AppConfig,
    rootPath: String,
    isDualScreen: Boolean,
    onSelectTheme: (ThemeConfig) -> Unit,
    onSelectOverlayBundle: (primary: ThemeConfig?, secondary: ThemeConfig?, setDefault: Boolean) -> Unit,
    onSetDefaultTheme: (gameId: String, themeId: String) -> Unit,
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    onSync: () -> Unit,
    uninstalledThemeCount: Int = 0,
    hasGalleryWidgets: Boolean = false,
    onJumpToGallery: () -> Unit = {},
    detectedGameName: String? = null,
    userOverlays: List<ThemeConfig> = emptyList(),
    onDeleteOverlay: (String) -> Unit = {},
    onEditOverlay: (ThemeConfig) -> Unit = {},
    showBuilderButton: Boolean = false,
    onLaunchBuilder: () -> Unit = {},
    confidence: MatchConfidence = MatchConfidence.MATCHED,
    gameHash: String? = null
) {
    var showBundleSheet by remember { mutableStateOf(false) }
    var pendingTheme by remember { mutableStateOf<ThemeConfig?>(null) }

    val displayName = when {
        detectedGameName != null && appConfig.devMode -> "$detectedGameName ($detectedGameId)"
        detectedGameName != null -> detectedGameName
        else -> detectedGameId
    }

    Column(modifier = Modifier.fillMaxSize().padding(EmuLnkDimens.spacingXl).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EmuLnkDimens.spacingSm)) {
                Icon(
                    painter = painterResource(R.drawable.ic_logo),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = stringResource(R.string.launcher_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = BrandPurple
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showBuilderButton) {
                    IconButton(
                        onClick = onLaunchBuilder,
                        modifier = Modifier
                            .padding(end = EmuLnkDimens.spacingMd)
                            .size(32.dp)
                            .background(BrandPurple, CircleShape)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_build),
                            contentDescription = stringResource(R.string.builder_screen_title),
                            tint = TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(painter = painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings), tint = TextPrimary, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onOpenGallery) {
                    Icon(painter = painterResource(R.drawable.ic_palette), contentDescription = stringResource(R.string.gallery), tint = TextPrimary, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onSync, enabled = !isSyncing) {
                    if (isSyncing) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = BrandPurple, strokeWidth = 2.dp)
                    else Icon(painter = painterResource(R.drawable.ic_sync), contentDescription = stringResource(R.string.sync), tint = TextPrimary, modifier = Modifier.size(20.dp))
                }
            }
        }

        // Status pill + confidence badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EmuLnkDimens.spacingSm),
            modifier = Modifier.padding(vertical = EmuLnkDimens.spacingXs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(
                        color = if (detectedGameId != null) StatusSuccess.copy(alpha = 0.15f) else StatusError.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(EmuLnkDimens.cornerSm)
                    )
                    .padding(horizontal = EmuLnkDimens.spacingMd, vertical = EmuLnkDimens.spacingXs)
            ) {
                Text(
                    text = if (detectedGameId != null) stringResource(R.string.detected_game, displayName ?: detectedGameId) else stringResource(R.string.searching_game),
                    fontSize = 12.sp,
                    color = if (detectedGameId != null) StatusSuccess else TextSecondary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (detectedGameId != null && confidence != MatchConfidence.MATCHED) {
                val badgeColor = if (confidence == MatchConfidence.FALLBACK) StatusWarning else StatusError
                val badgeText = if (confidence == MatchConfidence.FALLBACK) "Unknown ROM variant" else "Unsupported game"
                Text(
                    text = badgeText,
                    fontSize = 10.sp,
                    color = badgeColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(EmuLnkDimens.cornerSm))
                        .padding(horizontal = EmuLnkDimens.spacingSm, vertical = 2.dp)
                )
            }
        }

        if (appConfig.devMode && gameHash != null) {
            val clipboardManager = LocalClipboardManager.current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = EmuLnkDimens.spacingXs)
            ) {
                Text(
                    text = "Hash: $gameHash",
                    fontSize = 10.sp,
                    color = TextTertiary,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(gameHash))
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_content_copy),
                        contentDescription = "Copy hash",
                        tint = TextTertiary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        if (appConfig.devMode) {
            Text(text = stringResource(R.string.dev_mode_active), fontSize = 10.sp, color = BrandPurple, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = EmuLnkDimens.spacingXs))
        }

        Spacer(modifier = Modifier.height(EmuLnkDimens.spacingXxl))
        if (themes.isEmpty() && userOverlays.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val message = when {
                        detectedGameId != null -> stringResource(R.string.no_themes_for_game, displayName ?: detectedGameId)
                        else -> stringResource(R.string.no_themes_installed)
                    }
                    Text(text = message, color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = EmuLnkDimens.spacingXxl))

                    if (uninstalledThemeCount > 0) {
                        Spacer(modifier = Modifier.height(EmuLnkDimens.spacingLg))
                        Button(
                            onClick = onJumpToGallery,
                            colors = ButtonDefaults.buttonColors(containerColor = BrandPurple),
                            shape = RoundedCornerShape(EmuLnkDimens.cornerSm)
                        ) {
                            Icon(painter = painterResource(R.drawable.ic_palette), contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(EmuLnkDimens.spacingSm))
                            Text(
                                if (detectedGameName != null) stringResource(R.string.jump_to_game, detectedGameName)
                                else stringResource(R.string.browse_game_themes),
                                color = TextPrimary, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        } else {
            val allPagerThemes = themes + userOverlays
            val pagerState = rememberPagerState { allPagerThemes.size }

            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = 40.dp),
                pageSpacing = 16.dp,
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) { page ->
                val item = allPagerThemes[page]
                val isUo = item.id.startsWith(SavedOverlayConfig.ID_PREFIX)
                ThemeCard(
                    config = item,
                    isDefault = appConfig.isDefaultForGame(detectedGameId, item.id),
                    rootPath = rootPath,
                    isUserOverlay = isUo,
                    onEdit = if (isUo) ({ onEditOverlay(item) }) else null,
                    onDelete = if (isUo) ({ onDeleteOverlay(item.id) }) else null,
                    onClick = {
                        // Single-screen or bundles: select directly.
                        // Dual-screen non-bundles: open pairing sheet to pick a companion.
                        if (!isDualScreen || item.resolvedType == ThemeType.BUNDLE) {
                            onSelectTheme(item)
                        } else {
                            pendingTheme = item
                            showBundleSheet = true
                        }
                    },
                    onLongClick = { detectedGameId?.let { gid -> onSetDefaultTheme(gid, item.id) } }
                )
            }

            if (allPagerThemes.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = EmuLnkDimens.spacingMd),
                    verticalAlignment = Alignment.CenterVertically,
                    content = {
                        Spacer(modifier = Modifier.weight(1f))
                        repeat(allPagerThemes.size) { i ->
                            val isActive = pagerState.currentPage == i
                            val isUoDot = allPagerThemes[i].id.startsWith(SavedOverlayConfig.ID_PREFIX)
                            val activeColor = if (isUoDot) BrandCyan else BrandPurple
                            val color by animateColorAsState(
                                targetValue = if (isActive) activeColor else TextTertiary,
                                label = "dotColor"
                            )
                            Box(
                                modifier = Modifier
                                    .height(6.dp)
                                    .width(if (isActive) 24.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(color)
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                )
            }

            val hasExtras = uninstalledThemeCount > 0 || hasGalleryWidgets
            if (hasExtras) {
                val hintText = stringResource(R.string.more_items_available)

                TextButton(
                    onClick = onJumpToGallery,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = EmuLnkDimens.spacingSm)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_palette),
                        contentDescription = null,
                        tint = BrandPurple,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(EmuLnkDimens.spacingSm))
                    Text(hintText, color = BrandPurple, fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_forward),
                        contentDescription = null,
                        tint = BrandPurple,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(EmuLnkDimens.spacingLg))
        }

    }

    val allThemes = themes + userOverlays

    val tapped = pendingTheme
    if (showBundleSheet && tapped != null) {
        val companions = allThemes.filter { it.id != tapped.id && it.resolvedType != ThemeType.BUNDLE }

        val savedBundle = detectedGameId?.let { appConfig.defaultBundles[it] }
        val isDefault = appConfig.isDefaultForGame(detectedGameId, tapped.id)
        val defaultCompanionId = when {
            savedBundle?.primaryOverlayId == tapped.id -> savedBundle?.secondaryOverlayId
            savedBundle?.secondaryOverlayId == tapped.id -> savedBundle?.primaryOverlayId
            else -> {
                if (appConfig.defaultThemes[detectedGameId] == tapped.id)
                    (appConfig.defaultOverlays ?: emptyMap())[detectedGameId]
                else if ((appConfig.defaultOverlays ?: emptyMap())[detectedGameId] == tapped.id)
                    appConfig.defaultThemes[detectedGameId]
                else null
            }
        }

        PairingBottomSheet(
            selectedItem = tapped,
            companions = companions,
            gameName = displayName ?: stringResource(R.string.fallback_game_name),
            rootPath = rootPath,
            isDualScreenBundle = true,
            isCurrentDefault = isDefault,
            defaultCompanionId = defaultCompanionId,
            onDismiss = { showBundleSheet = false; pendingTheme = null },
            onLaunch = { _, _, _ -> },
            onLaunchBundle = { primary, secondary, setDefault ->
                showBundleSheet = false; pendingTheme = null
                onSelectOverlayBundle(primary, secondary, setDefault)
            }
        )
    }
}

