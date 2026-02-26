package com.emulnk.ui.screens

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emulnk.R
import com.emulnk.model.AppConfig
import com.emulnk.model.ThemeConfig
import com.emulnk.ui.components.ThemeCard
import com.emulnk.ui.theme.*

@Composable
fun LauncherScreen(
    detectedGameId: String?,
    themes: List<ThemeConfig>,
    isSyncing: Boolean,
    appConfig: AppConfig,
    rootPath: String,
    onSelectTheme: (ThemeConfig) -> Unit,
    onSetDefaultTheme: (gameId: String, themeId: String) -> Unit,
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    onSync: () -> Unit
) {
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

        // Status pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(vertical = EmuLnkDimens.spacingXs)
                .background(
                    color = if (detectedGameId != null) StatusSuccess.copy(alpha = 0.15f) else StatusError.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(EmuLnkDimens.cornerSm)
                )
                .padding(horizontal = EmuLnkDimens.spacingMd, vertical = EmuLnkDimens.spacingXs)
        ) {
            Text(
                text = if (detectedGameId != null) stringResource(R.string.detected_game, detectedGameId) else stringResource(R.string.searching_game),
                fontSize = 12.sp,
                color = if (detectedGameId != null) StatusSuccess else TextSecondary,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (appConfig.devMode) {
            Text(text = stringResource(R.string.dev_mode_active), fontSize = 10.sp, color = BrandPurple, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = EmuLnkDimens.spacingXs))
        }

        Spacer(modifier = Modifier.height(EmuLnkDimens.spacingXxl))
        if (themes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val message = when {
                    detectedGameId != null -> stringResource(R.string.no_themes_for_game, detectedGameId)
                    else -> stringResource(R.string.no_themes_installed)
                }
                Text(text = message, color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = EmuLnkDimens.spacingXxl))
            }
        } else {
            val pagerState = rememberPagerState { themes.size }

            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = 40.dp),
                pageSpacing = 16.dp,
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) { page ->
                ThemeCard(
                    config = themes[page],
                    isDefault = appConfig.defaultThemes[detectedGameId] == themes[page].id,
                    rootPath = rootPath,
                    onClick = { onSelectTheme(themes[page]) },
                    onLongClick = { detectedGameId?.let { gid -> onSetDefaultTheme(gid, themes[page].id) } }
                )
            }

            if (themes.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = EmuLnkDimens.spacingMd),
                    verticalAlignment = Alignment.CenterVertically,
                    content = {
                        Spacer(modifier = Modifier.weight(1f))
                        repeat(themes.size) { i ->
                            val isActive = pagerState.currentPage == i
                            val color by animateColorAsState(
                                targetValue = if (isActive) BrandPurple else TextTertiary,
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

            Spacer(modifier = Modifier.height(EmuLnkDimens.spacingLg))
        }
    }
}
