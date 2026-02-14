package com.emulnk.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
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
    onSelectTheme: (ThemeConfig) -> Unit,
    onSetDefaultTheme: (gameId: String, themeId: String) -> Unit,
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    onSync: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.launcher_title), fontSize = 32.sp, fontWeight = FontWeight.Black, color = BrandPurple)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenSettings) {
                    Icon(painter = painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings), tint = Color.White, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onOpenGallery) {
                    Icon(painter = painterResource(R.drawable.ic_palette), contentDescription = stringResource(R.string.gallery), tint = Color.White, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onSync, enabled = !isSyncing) {
                    if (isSyncing) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = BrandPurple, strokeWidth = 2.dp)
                    else Icon(painter = painterResource(R.drawable.ic_sync), contentDescription = stringResource(R.string.sync), tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (detectedGameId != null) StatusSuccess else StatusError))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (detectedGameId != null) stringResource(R.string.detected_game, detectedGameId) else stringResource(R.string.searching_game),
                fontSize = 14.sp,
                color = if (detectedGameId != null) Color.White else Color.Gray,
                fontWeight = FontWeight.Medium
            )
        }

        if (appConfig.devMode) {
            Text(text = stringResource(R.string.dev_mode_active), fontSize = 10.sp, color = BrandPurple, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(modifier = Modifier.height(32.dp))
        if (themes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val message = when {
                    detectedGameId != null -> stringResource(R.string.no_themes_for_game, detectedGameId)
                    else -> stringResource(R.string.no_themes_installed)
                }
                Text(text = message, color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(themes) { config ->
                    ThemeCard(
                        config = config,
                        isDefault = appConfig.defaultThemes[detectedGameId] == config.id,
                        onClick = { onSelectTheme(config) },
                        onLongClick = { detectedGameId?.let { gid -> onSetDefaultTheme(gid, config.id) } }
                    )
                }
            }
        }
    }
}
