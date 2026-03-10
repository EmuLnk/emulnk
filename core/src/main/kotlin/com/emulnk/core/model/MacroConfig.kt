package com.emulnk.core.model

data class MacroConfig(
    val id: String,
    val steps: List<MacroStep>
)

data class MacroStep(
    val varId: String? = null,
    val value: String? = null,
    val delay: Long? = null
)
