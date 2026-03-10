package com.emulnk.core.resolver

import com.emulnk.core.model.BundleConfig
import com.emulnk.core.model.ProfileConfig

/**
 * Merges child profiles with their parent profiles via the `extends` field.
 *
 * Merge rules:
 * - Bundles: field-level merge (child fields override, missing fields inherited)
 * - DataPoints: override by id, inherit rest, append new
 * - Macros: override by id, inherit rest, append new
 * - gameIds: NOT inherited (child defines own identity)
 * - name, platform: child values take priority
 */
object ProfileMerger {

    private const val MAX_INHERITANCE_DEPTH = 3

    /**
     * Resolve a profile's full inheritance chain and return the merged result.
     *
     * @param profile The child profile to resolve
     * @param loader Function to load a profile by ID (for parent resolution)
     * @param depth Current recursion depth (prevents circular references)
     */
    fun resolve(
        profile: ProfileConfig,
        depth: Int = 0,
        loader: (String) -> ProfileConfig?
    ): ProfileConfig {
        val parentId = profile.extends ?: return profile
        if (depth >= MAX_INHERITANCE_DEPTH) return profile

        val parent = loader(parentId) ?: return profile
        val resolvedParent = resolve(parent, depth + 1, loader)

        return merge(child = profile, parent = resolvedParent)
    }

    private fun merge(child: ProfileConfig, parent: ProfileConfig): ProfileConfig {
        return ProfileConfig(
            id = child.id,
            name = child.name,
            platform = child.platform,
            gameIds = child.gameIds,
            extends = null, // Resolved — no longer needed
            bundles = mergeBundles(child.bundles, parent.bundles),
            dataPoints = mergeById(child.dataPoints, parent.dataPoints) { it.id },
            macros = mergeById(child.macros, parent.macros) { it.id }
        )
    }

    private fun mergeBundles(
        child: Map<String, BundleConfig>?,
        parent: Map<String, BundleConfig>?
    ): Map<String, BundleConfig>? {
        if (parent == null) return child
        if (child == null) return parent

        val merged = parent.toMutableMap()
        for ((key, childBundle) in child) {
            val parentBundle = merged[key]
            if (parentBundle != null) {
                // Field-level merge: child fields override, missing fields inherited
                merged[key] = BundleConfig(
                    base = childBundle.base ?: parentBundle.base,
                    pointer = childBundle.pointer ?: parentBundle.pointer,
                    chain = childBundle.chain ?: parentBundle.chain
                )
            } else {
                merged[key] = childBundle
            }
        }
        return merged
    }

    private fun <T> mergeById(
        childList: List<T>,
        parentList: List<T>,
        idExtractor: (T) -> String
    ): List<T> {
        val childIds = childList.map { idExtractor(it) }.toSet()
        val inherited = parentList.filter { idExtractor(it) !in childIds }
        val overridden = parentList.filter { idExtractor(it) in childIds }
            .map { parent ->
                childList.first { idExtractor(it) == idExtractor(parent) }
            }
        val appended = childList.filter { idExtractor(it) !in parentList.map(idExtractor).toSet() }

        return inherited + overridden + appended
    }
}
