package com.emulnk.model

data class RepoIndex(
    val version: String,
    val themes: List<RepoTheme>
)

data class RepoTheme(
    val id: String,
    val name: String,
    val author: String = "Unknown",
    val version: String? = "1.0.0",
    val description: String = "",
    val targetProfileId: String,
    val minAppVersion: Int? = 1,
    val previewUrl: String? = null,
    val downloadUrl: String? = null
)
