package com.emulnk.core.resolver

import com.emulnk.core.model.BundleConfig
import com.emulnk.core.model.DataPoint
import com.emulnk.core.model.ProfileConfig

/**
 * Resolves data point addresses using bundles, pointer chains, or legacy address maps.
 *
 * This class is pure logic — actual memory reads for pointer resolution are delegated
 * to the [MemoryReader] interface, which the app module implements via UDP.
 */
class AddressResolver(
    private val memoryReader: MemoryReader,
    private val maxPointerChainDepth: Int = 10
) {

    /**
     * Abstraction for reading memory. The app module provides a UDP-backed implementation.
     * Returns null if the read fails.
     */
    interface MemoryReader {
        fun read(address: Long, size: Int): ByteArray?
    }

    /**
     * Resolve the effective memory address for a data point, considering:
     * 1. Legacy pointer chain (DataPoint.pointer + offsets)
     * 2. Bundle-based addressing (static or pointer-resolved base + offset)
     * 3. Legacy static address map (DataPoint.addresses)
     *
     * @param point The data point to resolve
     * @param profile The active profile (for bundle definitions)
     * @param gameId The current game ID (for regional address maps)
     * @param byteOrder The platform byte order for pointer dereferencing
     */
    fun resolve(
        point: DataPoint,
        profile: ProfileConfig,
        gameId: String?,
        byteOrder: java.nio.ByteOrder = java.nio.ByteOrder.LITTLE_ENDIAN
    ): Long? {
        // Priority 1: Legacy pointer chain on the data point itself
        if (point.pointer != null) {
            return resolveLegacyPointer(point, gameId, byteOrder)
        }

        // Priority 2: Bundle-based addressing
        if (point.bundle != null) {
            return resolveBundle(point, profile, gameId, byteOrder)
        }

        // Priority 3: Legacy static address map
        if (point.addresses.isNotEmpty()) {
            val addrStr = resolveFromMap(point.addresses, gameId) ?: return null
            return parseHex(addrStr)
        }

        return null
    }

    private fun resolveLegacyPointer(
        point: DataPoint,
        gameId: String?,
        byteOrder: java.nio.ByteOrder
    ): Long? {
        val chain = point.offsets
            ?: point.offset?.let { listOf(it) }
            ?: return null

        if (chain.size > maxPointerChainDepth) return null

        val ptrAddrStr = resolveFromMap(point.pointer!!, gameId) ?: return null
        val ptrAddr = parseHex(ptrAddrStr) ?: return null

        return walkPointerChain(ptrAddr, chain, byteOrder)
    }

    private fun resolveBundle(
        point: DataPoint,
        profile: ProfileConfig,
        gameId: String?,
        byteOrder: java.nio.ByteOrder
    ): Long? {
        val bundleName = point.bundle ?: return null
        val bundle = profile.bundles?.get(bundleName) ?: return null
        val offset = point.offset?.let { parseHex(it) } ?: return null

        val base = resolveBundleBase(bundle, gameId, byteOrder) ?: return null
        return base + offset
    }

    /**
     * Resolve a bundle's base address, either from a static value or via pointer chain.
     */
    fun resolveBundleBase(
        bundle: BundleConfig,
        gameId: String?,
        byteOrder: java.nio.ByteOrder
    ): Long? {
        // Pointer-resolved bundle base
        if (bundle.pointer != null) {
            val ptrAddr = parseHex(bundle.pointer) ?: return null
            val chain = bundle.chain ?: emptyList()
            if (chain.size > maxPointerChainDepth) return null
            return walkPointerChain(ptrAddr, chain, byteOrder)
        }

        // Static base (string or map)
        if (bundle.base != null) {
            return when (bundle.base) {
                is String -> parseHex(bundle.base)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = bundle.base as Map<String, String>
                    val addrStr = resolveFromMap(map, gameId) ?: return null
                    parseHex(addrStr)
                }
                else -> null
            }
        }

        return null
    }

    private fun walkPointerChain(
        basePointerAddr: Long,
        chain: List<String>,
        byteOrder: java.nio.ByteOrder
    ): Long? {
        val ptrData = memoryReader.read(basePointerAddr, 4) ?: return null
        var addr = java.nio.ByteBuffer.wrap(ptrData).order(byteOrder).int.toLong() and 0xFFFFFFFFL
        if (addr == 0L) return null

        for (i in chain.indices) {
            val off = parseHex(chain[i]) ?: return null
            addr += off
            if (i < chain.lastIndex) {
                val next = memoryReader.read(addr, 4) ?: return null
                addr = java.nio.ByteBuffer.wrap(next).order(byteOrder).int.toLong() and 0xFFFFFFFFL
                if (addr == 0L) return null
            }
        }
        return addr
    }

    companion object {
        /**
         * Resolve a game ID against a map using 3-tier matching:
         * 1. Exact match (e.g., GZLE01)
         * 2. Short match (first 4 chars, e.g., GZLE)
         * 3. Series match (first 3 chars, e.g., GZL)
         * 4. Default fallback
         */
        fun resolveFromMap(map: Map<String, String>, gameId: String?): String? {
            if (gameId == null) return map["default"]

            map[gameId]?.let { return it }

            if (gameId.length >= 4) {
                map[gameId.substring(0, 4)]?.let { return it }
            }

            if (gameId.length >= 3) {
                map[gameId.substring(0, 3)]?.let { return it }
            }

            return map["default"]
        }

        fun parseHex(hex: String): Long? {
            if (hex.isBlank()) return null
            return try {
                hex.removePrefix("0x").toLong(16)
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}
