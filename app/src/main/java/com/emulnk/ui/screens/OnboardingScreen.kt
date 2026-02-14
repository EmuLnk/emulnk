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
import androidx.compose.ui.graphics.Color
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
            .padding(horizontal = 32.dp)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        Text(stringResource(R.string.welcome_title), fontSize = 32.sp, fontWeight = FontWeight.Black, color = BrandPurple)

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
        Spacer(modifier = Modifier.height(24.dp))
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
            Text(rootPath, fontSize = 10.sp, color = BrandPurple, modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onNext,
            enabled = hasPermission && isRootPathSet,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandPurple),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(R.string.next), fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(32.dp))
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
            .padding(horizontal = 32.dp)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        Text(stringResource(R.string.preferences_title), fontSize = 32.sp, fontWeight = FontWeight.Black, color = BrandPurple)
        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.preferences_subtitle), color = Color.Gray, textAlign = TextAlign.Center)

        Spacer(modifier = Modifier.height(48.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_autoboot_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(stringResource(R.string.settings_autoboot_desc), fontSize = 11.sp, color = Color.Gray)
                }
                Switch(checked = appConfig.autoBoot, onCheckedChange = onSetAutoBoot)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_repo_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(stringResource(R.string.settings_repo_desc), fontSize = 11.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = repoUrlText,
                    onValueChange = { repoUrlText = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandPurple,
                        unfocusedBorderColor = Color.Gray
                    )
                )
                TextButton(
                    onClick = {
                        onResetRepoUrl()
                        repoUrlText = AppConfig().repoUrl
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(stringResource(R.string.settings_reset_default), color = Color.Gray, fontSize = 10.sp)
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
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(R.string.finish_setup), fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onBack) {
            Text(stringResource(R.string.back), color = Color.Gray)
        }

        Spacer(modifier = Modifier.height(32.dp))
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
                Icon(painter = painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            } else if (icon != null) {
                Icon(painter = painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            } else {
                Text(number, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, color = Color.White)
            Text(description, fontSize = 12.sp, color = Color.Gray)
            TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) {
                Text(if (isComplete && completeLabel != null) completeLabel else actionLabel, color = BrandPurple, fontWeight = FontWeight.Bold)
            }
        }
    }
}
