package com.emulnk.ui.navigation

sealed class Screen {
    object Onboarding : Screen()
    object Home : Screen()
    object Gallery : Screen()
    object Dashboard : Screen()
}
