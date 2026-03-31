package com.emulnk.model

import java.io.File

data class SavedOverlayConfig(
    val id: String,
    val name: String,
    val profileId: String,
    val console: String? = null,
    val selectedWidgetIds: List<String>,
    val screenAssignments: Map<String, ScreenTarget> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** Prefix for user-created overlay IDs */
        const val ID_PREFIX = "uo_"

        /** Supported icon/preview extensions in priority order */
        internal val ICON_EXTENSIONS = listOf("svg", "webp", "png")

        /** Returns the first existing icon file for a user overlay, or the png path as default */
        fun resolveIconFile(rootPath: String, id: String): File {
            val dir = File(rootPath, "user_overlays")
            return ICON_EXTENSIONS.map { File(dir, "${id}_icon.$it") }.firstOrNull { it.exists() }
                ?: File(dir, "${id}_icon.png")
        }

        /** Resolves the preview image: user icon if it exists, otherwise the theme's preview */
        fun resolvePreviewFile(rootPath: String, themeId: String): File {
            val userIcon = resolveIconFile(rootPath, themeId)
            if (userIcon.exists()) return userIcon
            val themeDir = File(rootPath, "themes/$themeId")
            return ICON_EXTENSIONS.map { File(themeDir, "preview.$it") }.firstOrNull { it.exists() }
                ?: File(themeDir, "preview.png")
        }
    }
}
