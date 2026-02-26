package com.emulnk.ui.screens

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.emulnk.R
import com.emulnk.model.AppConfig
import com.emulnk.ui.theme.*

@Composable
fun OnboardingScreen(
    rootPath: String,
    isRootPathSet: Boolean,
    appConfig: AppConfig,
    onGrantPermission: () -> Unit,
    onSelectFolder: () -> Unit,
    onSetAutoBoot: (Boolean) -> Unit,
    onSetRepoUrl: (String) -> Unit,
    onResetRepoUrl: () -> Unit,
    onCompleteOnboarding: () -> Unit
) {
    var onboardingPage by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(SurfaceOverlay, SurfaceBase)))
    ) {
        AnimatedContent(
            targetState = onboardingPage,
            transitionSpec = {
                slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
            }
        ) { page ->
            when (page) {
                0 -> OnboardingPermissionsPage(
                    rootPath = rootPath,
                    isRootPathSet = isRootPathSet,
                    onGrantPermission = onGrantPermission,
                    onSelectFolder = onSelectFolder,
                    onNext = { onboardingPage = 1 }
                )
                1 -> OnboardingPreferencesPage(
                    appConfig = appConfig,
                    onSetAutoBoot = onSetAutoBoot,
                    onSetRepoUrl = onSetRepoUrl,
                    onResetRepoUrl = onResetRepoUrl,
                    onCompleteOnboarding = onCompleteOnboarding,
                    onBack = { onboardingPage = 0 }
                )
            }
        }

        // Page indicator dots
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = EmuLnkDimens.spacingXxl),
            horizontalArrangement = Arrangement.spacedBy(EmuLnkDimens.spacingSm)
        ) {
            repeat(2) { index ->
                Box(
                    modifier = Modifier
                        .height(6.dp)
                        .then(
                            if (index == onboardingPage)
                                Modifier.width(24.dp)
                            else
                                Modifier.width(6.dp)
                        )
                        .clip(CircleShape)
                        .background(if (index == onboardingPage) BrandPurple else TextTertiary)
                )
            }
        }
    }
}

@Composable
private fun OnboardingPermissionsPage(
    rootPath: String,
    isRootPathSet: Boolean,
    onGrantPermission: () -> Unit,
    onSelectFolder: () -> Unit,
    onNext: () -> Unit
) {
    var hasPermission by remember {
        mutableStateOf(Environment.isExternalStorageManager())
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = Environment.isExternalStorageManager()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = EmuLnkDimens.spacingXxl)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.height(EmuLnkDimens.spacingXxl))
        Text(
            stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.headlineLarge,
            color = BrandPurple
        )

        Spacer(modifier = Modifier.height(48.dp))

        OnboardingStep(
            "1",
            stringResource(R.string.onboarding_storage_title),
            stringResource(R.string.onboarding_storage_desc),
            hasPermission,
            stringResource(R.string.onboarding_grant_permission),
            stringResource(R.string.onboarding_permission_granted),
            onGrantPermission
        )
        Spacer(modifier = Modifier.height(EmuLnkDimens.spacingXl))
        OnboardingStep(
            "2",
            stringResource(R.string.onboarding_folder_title),
            stringResource(R.string.onboarding_folder_desc),
            isRootPathSet,
            stringResource(R.string.onboarding_select_folder),
            stringResource(R.string.onboarding_change_folder),
            onSelectFolder
        )

        if (isRootPathSet) {
            Text(rootPath, fontSize = 10.sp, color = BrandPurple, modifier = Modifier.padding(top = EmuLnkDimens.spacingXs))
        }

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onNext,
            enabled = hasPermission && isRootPathSet,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandPurple),
            shape = RoundedCornerShape(EmuLnkDimens.cornerMd)
        ) {
            Text(stringResource(R.string.next), fontWeight = FontWeight.Bold, color = TextPrimary)
        }
        Spacer(modifier = Modifier.height(EmuLnkDimens.spacingXxl))
    }
}

@Composable
private fun OnboardingPreferencesPage(
    appConfig: AppConfig,
    onSetAutoBoot: (Boolean) -> Unit,
    onSetRepoUrl: (String) -> Unit,
    onResetRepoUrl: () -> Unit,
    onCompleteOnboarding: () -> Unit,
    onBack: () -> Unit
) {
    var repoUrlText by remember(appConfig.repoUrl) { mutableStateOf(appConfig.repoUrl) }

    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = EmuLnkDimens.spacingXxl)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.height(EmuLnkDimens.spacingXxl))
        Text(
            stringResource(R.string.preferences_title),
            style = MaterialTheme.typography.headlineLarge,
            color = BrandPurple
        )
        Spacer(modifier = Modifier.height(EmuLnkDimens.spacingLg))
        Text(stringResource(R.string.preferences_subtitle), color = TextSecondary, textAlign = TextAlign.Center)

        Spacer(modifier = Modifier.height(48.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceRaised),
            shape = RoundedCornerShape(EmuLnkDimens.cornerMd)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(EmuLnkDimens.spacingLg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_autoboot_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(stringResource(R.string.settings_autoboot_desc), fontSize = 11.sp, color = TextSecondary)
                }
                Switch(checked = appConfig.autoBoot, onCheckedChange = onSetAutoBoot)
            }
        }

        Spacer(modifier = Modifier.height(EmuLnkDimens.spacingLg))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceRaised),
            shape = RoundedCornerShape(EmuLnkDimens.cornerMd)
        ) {
            Column(modifier = Modifier.padding(EmuLnkDimens.spacingLg)) {
                Text(stringResource(R.string.settings_repo_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(stringResource(R.string.settings_repo_desc), fontSize = 11.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(EmuLnkDimens.spacingSm))
                OutlinedTextField(
                    value = repoUrlText,
                    onValueChange = { repoUrlText = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = TextPrimary),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandPurple,
                        unfocusedBorderColor = TextTertiary
                    )
                )
                TextButton(
                    onClick = {
                        onResetRepoUrl()
                        repoUrlText = AppConfig().repoUrl
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(stringResource(R.string.settings_reset_default), color = TextSecondary, fontSize = 10.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = {
                onSetRepoUrl(repoUrlText)
                onCompleteOnboarding()
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandPurple),
            shape = RoundedCornerShape(EmuLnkDimens.cornerMd)
        ) {
            Text(stringResource(R.string.finish_setup), fontWeight = FontWeight.Bold, color = TextPrimary)
        }

        Spacer(modifier = Modifier.height(EmuLnkDimens.spacingSm))

        TextButton(onClick = onBack) {
            Text(stringResource(R.string.back), color = TextSecondary)
        }

        Spacer(modifier = Modifier.height(EmuLnkDimens.spacingXxl))
    }
}

@Composable
fun OnboardingStep(number: String, title: String, description: String, isComplete: Boolean, actionLabel: String, completeLabel: String? = null, onAction: () -> Unit) {
    val icon = when(number) {
        "1" -> R.drawable.ic_security
        "2" -> R.drawable.ic_folder_open
        else -> null
    }

    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(if (isComplete) StatusSuccess else BrandPurple.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            if (isComplete) {
                Icon(painter = painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = TextPrimary, modifier = Modifier.size(20.dp))
            } else if (icon != null) {
                Icon(painter = painterResource(icon), contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
            } else {
                Text(number, color = TextPrimary, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.width(EmuLnkDimens.spacingLg))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(description, fontSize = 12.sp, color = TextSecondary)
            TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) {
                Text(if (isComplete && completeLabel != null) completeLabel else actionLabel, color = BrandPurple, fontWeight = FontWeight.Bold)
            }
        }
    }
}
