package com.emulnk.core.resolver

import com.emulnk.core.model.HashEntry
import com.emulnk.core.model.MatchConfidence
import com.emulnk.core.model.ProfileConfig

/**
 * Resolves a game to a profile using the hash -> serial -> prefix pipeline.
 */
class ProfileResolver(
    private val hashRegistry: Map<String, HashEntry>,
    private val profileLoader: ProfileLoader
) {

    /**
     * Abstraction for loading profiles and resolving game IDs.
     * The app module implements this via ConfigManager.
     */
    interface ProfileLoader {
        fun loadProfile(profileId: String): ProfileConfig?
        fun resolveProfileId(gameId: String): String?
    }

    data class ResolveResult(
        val profile: ProfileConfig,
        val confidence: MatchConfidence,
        val hashEntry: HashEntry? = null
    )

    /**
     * Resolve a game to a profile using the 4-step pipeline:
     * 1. Hash lookup in registry
     * 2. Game ID lookup via serial
     * 3. Prefix fallback (handled by ProfileLoader.resolveProfileId)
     * 4. Unknown (no match)
     */
    fun resolve(gameHash: String?, gameId: String?): ResolveResult? {
        // Step 1: Hash lookup
        if (!gameHash.isNullOrEmpty()) {
            val entry = hashRegistry[gameHash]
            if (entry != null) {
                val profile = profileLoader.loadProfile(entry.profileId)
                if (profile != null) {
                    return ResolveResult(profile, MatchConfidence.MATCHED, entry)
                }
            }
        }

        // Step 2+3: Game ID lookup (serial + prefix fallback, handled by existing resolver)
        if (!gameId.isNullOrEmpty()) {
            val profileId = profileLoader.resolveProfileId(gameId)
            if (profileId != null) {
                val profile = profileLoader.loadProfile(profileId)
                if (profile != null) {
                    return ResolveResult(profile, MatchConfidence.FALLBACK)
                }
            }
        }

        // Step 4: Unknown
        return null
    }
}
