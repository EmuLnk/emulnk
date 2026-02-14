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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.emulnk.ui.theme.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.TextStyle
import com.emulnk.model.AppConfig
import com.emulnk.model.ThemeConfig

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThemeCard(config: ThemeConfig, isDefault: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        modifier = Modifier
            .aspectRatio(0.8f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = if (isDefault) CardPurpleDark else CardDarkAlt),
        border = if (isDefault) androidx.compose.foundation.BorderStroke(2.dp, BrandPurple) else null
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(SurfaceMedium),
                contentAlignment = Alignment.Center
            ) {
                if (isDefault) {
                    Box(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(BrandPurple, CircleShape).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("DEFAULT", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    text = config.targetProfileId.take(2),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.1f)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Column {
                Text(text = config.meta.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(text = "Profile: ${config.targetProfileId}", fontSize = 12.sp, color = BrandPurple)
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
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("${theme.meta.name} Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                theme.settings?.forEach { schema ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(schema.label, fontSize = 14.sp, color = Color.White)
                        if (schema.type == "toggle") {
                            val checked = currentSettings[schema.id] == "true"
                            Switch(checked = checked, onCheckedChange = { onUpdate(schema.id, it.toString()) })
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = BrandPurple)) {
                    Text("Close")
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
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, BrandPurple),
        shape = RoundedCornerShape(16.dp)
    ) {
        LazyColumn(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Text("Developer Console", color = BrandPurple, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.1f))
            }
            items(logs) { log ->
                Text(log, color = Color.LightGray, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun SyncProgressDialog(message: String) {
    Dialog(onDismissRequest = { }) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Syncing Repository", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(24.dp))
                CircularProgressIndicator(color = BrandPurple)
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = message,
                    fontSize = 14.sp,
                    color = Color.LightGray,
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

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-boot Theme", fontSize = 14.sp, color = Color.White)
                        Text("Auto-select theme when game detected", fontSize = 11.sp, color = Color.Gray)
                    }
                    Switch(checked = appConfig.autoBoot, onCheckedChange = onSetAutoBoot)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.1f))

                Text("Repository URL", fontSize = 14.sp, color = Color.White)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = repoUrlText,
                    onValueChange = { repoUrlText = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 11.sp, color = Color.White),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandPurple,
                        unfocusedBorderColor = Color.Gray
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { onSetRepoUrl(repoUrlText) }) {
                        Text("Save", color = BrandPurple, fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        onResetRepoUrl()
                        repoUrlText = AppConfig().repoUrl
                    }) {
                        Text("Reset Default", color = Color.Gray, fontSize = 12.sp)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Root Folder", fontSize = 14.sp, color = Color.White)
                        Text(rootPath, fontSize = 10.sp, color = BrandPurple)
                    }
                    TextButton(onClick = onChangeRootFolder) {
                        Text("Change", color = BrandPurple, fontSize = 12.sp)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Developer Live-Link", fontSize = 14.sp, color = Color.White)
                        Text("Load themes from a dev server", fontSize = 11.sp, color = Color.Gray)
                    }
                    Switch(checked = appConfig.devMode, onCheckedChange = onSetDevMode)
                }
                if (appConfig.devMode) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = devUrlText,
                        onValueChange = {
                            devUrlText = it
                            onSetDevUrl(it)
                        },
                        label = { Text("Dev Server URL") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontSize = 12.sp, color = Color.White),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandPurple,
                            unfocusedBorderColor = Color.Gray
                        )
                    )
                    Text("e.g. http://192.168.x.x:5500", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.1f))

                Text("App Version: v$appVersionCode", fontSize = 12.sp, color = Color.Gray)

                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandPurple)
                ) {
                    Text("Close")
                }
            }
        }
    }
}
