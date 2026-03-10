package com.emulnk.core.model

enum class MatchConfidence {
    /** Hash found in registry — correct profile loaded, all data points active. */
    MATCHED,

    /** Hash unknown but serial matched — vanilla profile loaded, only stable data points shown. */
    FALLBACK,

    /** Nothing matched — no profile loaded, no data displayed. */
    UNKNOWN
}
