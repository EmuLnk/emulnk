package com.emulnk.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.emulnk.R
import com.emulnk.model.RepoIndex
import com.emulnk.model.RepoTheme
import com.emulnk.model.ThemeConfig
import com.emulnk.ui.theme.*

@Composable
fun GalleryScreen(
    repoIndex: RepoIndex?,
    isSyncing: Boolean,
    allInstalledThemes: List<ThemeConfig>,
    appVersionCode: Int,
    onBack: () -> Unit,
    onImportTheme: () -> Unit,
    onSelectTheme: (ThemeConfig) -> Unit,
    onDownloadTheme: (RepoTheme) -> Unit,
    onDeleteTheme: (String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(painter = painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back), tint = Color.White)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.gallery_title), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            IconButton(onClick = onImportTheme) {
                Icon(painter = painterResource(R.drawable.ic_download), contentDescription = stringResource(R.string.import_theme), tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = BrandPurple
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(stringResource(R.string.tab_community)) }, selectedContentColor = BrandPurple, unselectedContentColor = Color.Gray)
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(stringResource(R.string.tab_local)) }, selectedContentColor = BrandPurple, unselectedContentColor = Color.Gray)
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (selectedTab) {
            0 -> CommunityThemeList(
                repoIndex = repoIndex,
                allInstalledThemes = allInstalledThemes,
                isSyncing = isSyncing,
                appVersionCode = appVersionCode,
                onBack = onBack,
                onSelectTheme = onSelectTheme,
                onDownloadTheme = onDownloadTheme,
                onDeleteTheme = onDeleteTheme
            )
            1 -> LocalThemeList(
                repoIndex = repoIndex,
                allInstalledThemes = allInstalledThemes,
                isSyncing = isSyncing,
                onDeleteTheme = onDeleteTheme
            )
        }
    }
}

@Composable
private fun CommunityThemeList(
    repoIndex: RepoIndex?,
    allInstalledThemes: List<ThemeConfig>,
    isSyncing: Boolean,
    appVersionCode: Int,
    onBack: () -> Unit,
    onSelectTheme: (ThemeConfig) -> Unit,
    onDownloadTheme: (RepoTheme) -> Unit,
    onDeleteTheme: (String) -> Unit
) {
    if (repoIndex == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = BrandPurple)
        }
    } else {
        LazyVerticalGrid(columns = GridCells.Fixed(1), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(repoIndex.themes) { theme ->
                val minVersion = theme.minAppVersion ?: 1
                val isIncompatible = minVersion > appVersionCode
                val localTheme = allInstalledThemes.find { it.id == theme.id }
                val isInstalled = localTheme != null

                val localVersion = localTheme?.meta?.version ?: "1.0.0"
                val remoteVersion = theme.version ?: "1.0.0"
                val isOutdated = isInstalled && localVersion != remoteVersion

                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardDark), shape = RoundedCornerShape(16.dp)) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceDarkest), contentAlignment = Alignment.Center) {
                            AsyncImage(
                                model = theme.previewUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(theme.name, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("v$remoteVersion by ${theme.author}", fontSize = 11.sp, color = Color.Gray)
                            if (isIncompatible) {
                                Text(stringResource(R.string.requires_app_version, minVersion), fontSize = 11.sp, color = StatusError, fontWeight = FontWeight.Bold)
                            } else if (isOutdated) {
                                Text(stringResource(R.string.update_available, localVersion), fontSize = 11.sp, color = BrandPurple, fontWeight = FontWeight.Bold)
                            } else if (isInstalled) {
                                Text(stringResource(R.string.installed), fontSize = 11.sp, color = StatusSuccess, fontWeight = FontWeight.Bold)
                            } else {
                                Text(theme.description, fontSize = 11.sp, color = Color.LightGray, maxLines = 2)
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isInstalled) {
                                IconButton(
                                    onClick = { onDeleteTheme(theme.id) },
                                    enabled = !isSyncing,
                                    modifier = Modifier.background(Color.Black.copy(alpha = 0.2f), CircleShape)
                                ) {
                                    Icon(painter = painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.remove), tint = StatusError, modifier = Modifier.size(20.dp))
                                }
                            }

                            Button(
                                onClick = {
                                    if (isInstalled && !isOutdated) {
                                        onSelectTheme(localTheme)
                                        onBack()
                                    } else {
                                        onDownloadTheme(theme)
                                    }
                                },
                                enabled = !isSyncing && !isIncompatible,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when {
                                        isIncompatible -> Color.DarkGray
                                        isOutdated -> BrandPurple
                                        isInstalled -> StatusSuccess
                                        else -> BrandPurple
                                    }
                                ),
                                modifier = Modifier.size(48.dp),
                                contentPadding = PaddingValues(0.dp),
                                shape = CircleShape
                            ) {
                                if (isSyncing) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                } else {
                                    val iconRes = when {
                                        isIncompatible -> R.drawable.ic_upgrade
                                        isOutdated -> R.drawable.ic_download
                                        isInstalled -> R.drawable.ic_play_arrow
                                        else -> R.drawable.ic_download
                                    }
                                    Icon(painter = painterResource(iconRes), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalThemeList(
    repoIndex: RepoIndex?,
    allInstalledThemes: List<ThemeConfig>,
    isSyncing: Boolean,
    onDeleteTheme: (String) -> Unit
) {
    val repoThemeIds = repoIndex?.themes?.map { it.id }?.toSet() ?: emptySet()
    val localOnlyThemes = allInstalledThemes.filter { it.id !in repoThemeIds }

    if (localOnlyThemes.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_imported_themes), color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    } else {
        LazyVerticalGrid(columns = GridCells.Fixed(1), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(localOnlyThemes) { theme ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardDark), shape = RoundedCornerShape(16.dp)) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceDarkest), contentAlignment = Alignment.Center) {
                            Text(theme.targetProfileId.take(2), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.3f))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(theme.meta.name, fontWeight = FontWeight.Bold, color = Color.White)
                                Box(modifier = Modifier.background(StatusWarning, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                    Text(stringResource(R.string.imported), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                            Text("v${theme.meta.version ?: "1.0.0"} by ${theme.meta.author}", fontSize = 11.sp, color = Color.Gray)
                            Text("Profile: ${theme.targetProfileId}", fontSize = 11.sp, color = BrandPurple)
                        }

                        IconButton(
                            onClick = { onDeleteTheme(theme.id) },
                            enabled = !isSyncing,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.2f), CircleShape)
                        ) {
                            Icon(painter = painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.delete), tint = StatusError, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}
