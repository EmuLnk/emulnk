package com.emulnk.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import com.emulnk.model.SavedOverlayConfig
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import com.emulnk.ui.theme.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import coil.compose.AsyncImage
import com.emulnk.R
import com.emulnk.model.AppConfig
import com.emulnk.model.ThemeConfig
import com.emulnk.model.ThemeType
import com.emulnk.model.resolvedType
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThemeCard(
    config: ThemeConfig,
    isDefault: Boolean,
    rootPath: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isUserOverlay: Boolean = false,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val previewFile = remember(config.id, rootPath) {
        SavedOverlayConfig.resolvePreviewFile(rootPath, config.id)
    }
    val hasPreview = remember(previewFile) { previewFile.exists() }

    val borderStroke = when {
        isUserOverlay -> androidx.compose.foundation.BorderStroke(2.dp, BrandCyan)
        isDefault -> androidx.compose.foundation.BorderStroke(2.dp, BrandPurple)
        else -> null
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(EmuLnkDimens.cornerLg),
        colors = CardDefaults.cardColors(containerColor = if (isDefault || isUserOverlay) SurfaceOverlay else SurfaceElevated),
        border = borderStroke
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(EmuLnkDimens.spacingLg),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(EmuLnkDimens.cornerMd))
                    .background(SurfaceBase),
                contentAlignment = Alignment.Center
            ) {
                if (hasPreview) {
                    AsyncImage(
                        model = previewFile,
                        contentDescription = config.meta.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = config.targetProfileId.take(2),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary.copy(alpha = 0.1f)
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(EmuLnkDimens.spacingSm),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (config.resolvedType == ThemeType.THEME) {
                        Box(
                            modifier = Modifier
                                .background(BrandPurple, CircleShape)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(stringResource(R.string.badge_theme), color = TextPrimary, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (config.resolvedType == ThemeType.OVERLAY) {
                        Box(
                            modifier = Modifier
                                .background(BrandCyan, CircleShape)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(stringResource(R.string.badge_overlay), color = SurfaceBase, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (config.resolvedType == ThemeType.BUNDLE) {
                        Box(
                            modifier = Modifier
                                .background(StatusWarning, CircleShape)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(stringResource(R.string.badge_bundle), color = SurfaceBase, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (isUserOverlay) {
                        Box(
                            modifier = Modifier
                                .background(BrandCyan, CircleShape)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(stringResource(R.string.badge_custom), color = SurfaceBase, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (isDefault) {
                        Box(
                            modifier = Modifier
                                .background(BrandPurple, CircleShape)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(stringResource(R.string.badge_default), color = TextPrimary, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(EmuLnkDimens.spacingMd))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = config.meta.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1)
                    Text(text = "Profile: ${config.targetProfileId}", fontSize = 12.sp, color = if (isUserOverlay) BrandCyan else BrandPurple)
                }
                if (onEdit != null || onDelete != null) {
                    Row {
                        if (onEdit != null) {
                            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                                Icon(painter = painterResource(R.drawable.ic_edit), contentDescription = "Edit", tint = TextSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (onDelete != null) {
                            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                                Icon(painter = painterResource(R.drawable.ic_delete), contentDescription = "Delete", tint = StatusError, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ThemeSettingsDialog(
    theme: ThemeConfig,
    currentSettings: Map<String, String>,
    onDismiss: () -> Unit,
    onUpdate: (String, String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(EmuLnkDimens.spacingLg),
            shape = RoundedCornerShape(EmuLnkDimens.cornerLg),
            colors = CardDefaults.cardColors(containerColor = SurfaceRaised)
        ) {
            Column(modifier = Modifier.padding(EmuLnkDimens.spacingXl)) {
                Text("${theme.meta.name} Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(modifier = Modifier.height(EmuLnkDimens.spacingLg))
                theme.settings?.forEach { schema ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = EmuLnkDimens.spacingSm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(schema.label, fontSize = 14.sp, color = TextPrimary)
                        if (schema.type == "toggle") {
                            val checked = currentSettings[schema.id] == "true"
                            Switch(checked = checked, onCheckedChange = { onUpdate(schema.id, it.toString()) })
                        }
                    }
                }
                Spacer(modifier = Modifier.height(EmuLnkDimens.spacingXl))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = BrandPurple)) {
                    Text("Close", color = TextPrimary)
                }
            }
        }
    }
}

@Composable
fun DebugOverlay(logs: List<String>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth(0.8f)
            .height(150.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceBase.copy(alpha = 0.9f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, BrandPurple),
        shape = RoundedCornerShape(EmuLnkDimens.cornerMd)
    ) {
        LazyColumn(modifier = Modifier.padding(EmuLnkDimens.spacingMd), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Text("Developer Console", color = BrandPurple, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                HorizontalDivider(modifier = Modifier.padding(vertical = EmuLnkDimens.spacingSm), color = DividerColor)
            }
            items(logs) { log ->
                Text(log, color = TextSecondary, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun SyncProgressDialog(message: String) {
    Dialog(onDismissRequest = { }) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(EmuLnkDimens.spacingLg),
            shape = RoundedCornerShape(EmuLnkDimens.cornerLg),
            colors = CardDefaults.cardColors(containerColor = SurfaceRaised)
        ) {
            Column(
                modifier = Modifier.padding(EmuLnkDimens.spacingXl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Syncing Repository", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(modifier = Modifier.height(EmuLnkDimens.spacingXl))
                CircularProgressIndicator(color = BrandPurple)
                Spacer(modifier = Modifier.height(EmuLnkDimens.spacingXl))
                Text(
                    text = message,
                    fontSize = 14.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun AppSettingsDialog(
    appConfig: AppConfig,
    rootPath: String,
    appVersionCode: Int,
    onDismiss: () -> Unit,
    onSetAutoBoot: (Boolean) -> Unit,
    onSetRepoUrl: (String) -> Unit,
    onResetRepoUrl: () -> Unit,
    onChangeRootFolder: () -> Unit,
    onSetDevMode: (Boolean) -> Unit,
    onSetDevUrl: (String) -> Unit
) {
    var repoUrlText by remember(appConfig.repoUrl) { mutableStateOf(appConfig.repoUrl) }
    var devUrlText by remember(appConfig.devUrl) { mutableStateOf(appConfig.devUrl) }
    var repoFeedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repoFeedback) {
        if (repoFeedback != null) {
            delay(1500)
            repoFeedback = null
        }
    }

    val dismissAndSave = {
        if (repoUrlText != appConfig.repoUrl) onSetRepoUrl(repoUrlText)
        if (devUrlText != appConfig.devUrl) onSetDevUrl(devUrlText)
        onDismiss()
    }
    Dialog(onDismissRequest = dismissAndSave) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(EmuLnkDimens.spacingLg),
            shape = RoundedCornerShape(EmuLnkDimens.cornerLg),
            colors = CardDefaults.cardColors(containerColor = SurfaceRaised)
        ) {
            Column(
                modifier = Modifier
                    .padding(EmuLnkDimens.spacingXl)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-boot Theme", fontSize = 14.sp, color = TextPrimary)
                        Text("Auto-select theme when game detected", fontSize = 11.sp, color = TextSecondary)
                    }
                    Switch(checked = appConfig.autoBoot, onCheckedChange = onSetAutoBoot)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = EmuLnkDimens.spacingMd), color = DividerColor)

                Text("Repository URL", fontSize = 14.sp, color = TextPrimary)
                Spacer(modifier = Modifier.height(EmuLnkDimens.spacingXs))
                OutlinedTextField(
                    value = repoUrlText,
                    onValueChange = { repoUrlText = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 11.sp, color = TextPrimary),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandPurple,
                        unfocusedBorderColor = TextTertiary
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = EmuLnkDimens.spacingXs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(EmuLnkDimens.spacingSm)
                ) {
                    TextButton(onClick = {
                        onSetRepoUrl(repoUrlText)
                        repoFeedback = "Saved!"
                    }) {
                        Text("Save", color = BrandPurple, fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        onResetRepoUrl()
                        repoUrlText = AppConfig().repoUrl
                        repoFeedback = "Reset to default"
                    }) {
                        Text("Reset Default", color = TextSecondary, fontSize = 12.sp)
                    }
                    AnimatedVisibility(
                        visible = repoFeedback != null,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Text(
                            repoFeedback ?: "",
                            fontSize = 11.sp,
                            color = StatusSuccess
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = EmuLnkDimens.spacingMd), color = DividerColor)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Root Folder", fontSize = 14.sp, color = TextPrimary)
                        Text(rootPath, fontSize = 10.sp, color = BrandPurple)
                    }
                    TextButton(onClick = onChangeRootFolder) {
                        Text("Change", color = BrandPurple, fontSize = 12.sp)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = EmuLnkDimens.spacingMd), color = DividerColor)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Developer Live-Link", fontSize = 14.sp, color = TextPrimary)
                        Text("Load themes from a dev server", fontSize = 11.sp, color = TextSecondary)
                    }
                    Switch(checked = appConfig.devMode, onCheckedChange = onSetDevMode)
                }
                if (appConfig.devMode) {
                    Spacer(modifier = Modifier.height(EmuLnkDimens.spacingSm))
                    OutlinedTextField(
                        value = devUrlText,
                        onValueChange = { devUrlText = it },
                        label = { Text("Dev Server URL", color = TextSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontSize = 12.sp, color = TextPrimary),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandPurple,
                            unfocusedBorderColor = TextTertiary
                        )
                    )
                    Text("e.g. http://192.168.x.x:5500", fontSize = 10.sp, color = TextTertiary, modifier = Modifier.padding(top = EmuLnkDimens.spacingXs))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = EmuLnkDimens.spacingMd), color = DividerColor)

                Text("App Version: v$appVersionCode", fontSize = 12.sp, color = TextTertiary)

                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = dismissAndSave,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandPurple)
                ) {
                    Text("Close", color = TextPrimary)
                }
            }
        }
    }
}

@Composable
fun ThemePreviewSquare(
    config: ThemeConfig?,
    rootPath: String,
    size: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit
) {
    val previewFile = remember(config?.id, rootPath) {
        config?.let { SavedOverlayConfig.resolvePreviewFile(rootPath, it.id) }
    }
    val hasPreview = remember(previewFile) { previewFile?.exists() == true }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(EmuLnkDimens.cornerSm))
            .background(SurfaceBase),
        contentAlignment = Alignment.Center
    ) {
        if (hasPreview && previewFile != null) {
            AsyncImage(
                model = previewFile,
                contentDescription = config?.meta?.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (config != null) {
            Text(
                text = config.targetProfileId.take(2),
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                color = TextPrimary.copy(alpha = 0.15f)
            )
        }
    }
}

@Composable
fun ThemeTypeBadge(config: ThemeConfig) {
    val isThemeType = config.resolvedType == ThemeType.THEME
    val typeLabel = stringResource(if (isThemeType) R.string.type_theme else R.string.type_overlay)
    val typeColor = if (isThemeType) BrandPurple else BrandCyan
    Box(
        modifier = Modifier
            .background(typeColor.copy(alpha = 0.15f), RoundedCornerShape(EmuLnkDimens.cornerSm))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = typeLabel, fontSize = 10.sp, color = typeColor, fontWeight = FontWeight.SemiBold)
    }
}
