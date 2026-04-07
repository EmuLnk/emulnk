package com.emulnk.core.constants

import com.emulnk.core.model.ConsoleConfig


val ConsoleConfigDefaults = listOf(
    ConsoleConfig(
        id = "dolphin_gcn",
        name = "Dolphin (GameCube)",
        packageNames = listOf("org.emulnk.dolphinlnk"),
        console = "GCN",
        port = 55355
    ),
    ConsoleConfig(
        id = "dolphin_wii",
        name = "Dolphin (Wii)",
        packageNames = listOf("org.emulnk.dolphinlnk"),
        console = "WII",
        port = 55355
    )
)