package com.emulnk.core

import android.util.Log
import com.emulnk.data.MemoryRepository
import com.emulnk.model.ConsoleConfig
import com.emulnk.model.DataPoint
import com.emulnk.model.GameData
import com.emulnk.model.ProfileConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles detection and polling loops for emulator memory.
 */
class MemoryService(private val repository: MemoryRepository) {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _uiState = MutableStateFlow(GameData())
    val uiState: StateFlow<GameData> = _uiState

    private val _detectedGameId = MutableStateFlow<String?>(null)
    val detectedGameId: StateFlow<String?> = _detectedGameId

    private val _detectedConsole = MutableStateFlow<String?>(null)
    val detectedConsole: StateFlow<String?> = _detectedConsole

    private var consoleConfigs: List<ConsoleConfig> = emptyList()
    private var currentProfile: ProfileConfig? = null
    private var detectionJob: Job? = null
    private var pollingJob: Job? = null
    private var detectionFailures = 0
    private var wasGameDetected = false

    companion object {
        private const val TAG = "MemoryService"
    }

    fun start(configs: List<ConsoleConfig>) {
        this.consoleConfigs = configs
        startDetection()
    }

    fun stop() {
        detectionJob?.cancel()
        pollingJob?.cancel()
    }

    fun close() {
        stop()
        serviceScope.cancel()
        repository.close()
    }

    fun stopPolling() {
        pollingJob?.cancel()
        _uiState.value = _uiState.value.copy(isConnected = false)
    }

    private fun startDetection() {
        detectionJob?.cancel()
        detectionJob = serviceScope.launch {
            while (isActive) {
                var found = false
                for (config in consoleConfigs) {
                    repository.setPort(config.port)
                    val idAddr = parseHex(config.idAddress) ?: continue
                    val rawId = repository.readMemory(idAddr, config.idSize)
                    val idString = rawId?.decodeToString()?.trim()?.filter { it.isLetterOrDigit() }

                    if (idString != null && idString.length >= 4) {
                        detectionFailures = 0
                        wasGameDetected = true
                        found = true
                        if (idString != _detectedGameId.value || config.console != _detectedConsole.value) {
                            _detectedGameId.value = idString
                            _detectedConsole.value = config.console
                        }
                        break // Lock onto this console
                    }
                }

                if (!found) {
                    detectionFailures++
                    if (detectionFailures >= MemoryConstants.MAX_DETECTION_FAILURES) {
                        _detectedGameId.value = null
                        _detectedConsole.value = null
                        _uiState.value = _uiState.value.copy(isConnected = false)
                        wasGameDetected = false
                    }
                }
                delay(if (found && detectionFailures == 0) MemoryConstants.DETECTION_SUCCESS_DELAY_MS else MemoryConstants.DETECTION_RETRY_DELAY_MS)
            }
        }
    }

    fun setProfile(profile: ProfileConfig) {
        currentProfile = profile
        startPolling(profile)
    }

    private fun startPolling(profile: ProfileConfig) {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive) {
                val newValues = mutableMapOf<String, Any>()
                val rawValues = mutableMapOf<String, Any>()
                var successCount = 0
                val gameId = _detectedGameId.value
                
                for (point in profile.dataPoints) {
                    val addressLong = resolveEffectiveAddress(point, gameId, profile.platform) ?: continue
                    val rawData = repository.readMemory(addressLong, point.size)
                    
                    if (rawData != null) {
                        val rawNum = parseValue(rawData, point.type)
                        rawValues[point.id] = rawNum
                        
                        var processedValue = rawNum
                        if (point.formula != null && rawNum is Number) {
                            processedValue = MathEngine.evaluate(point.formula, rawNum.toDouble())
                        }
                        
                        newValues[point.id] = processedValue
                        successCount++
                    }
                }

                _uiState.value = _uiState.value.copy(
                    isConnected = successCount > 0,
                    values = newValues,
                    raw = rawValues
                )

                delay(MemoryConstants.POLLING_INTERVAL_MS)
            }
        }
    }

    fun writeVariable(varId: String, value: Int) {
        val profile = currentProfile ?: return
        val gameId = _detectedGameId.value ?: return
        val point = profile.dataPoints.find { it.id == varId } ?: return
        val addr = resolveEffectiveAddress(point, gameId, profile.platform) ?: return
        val buffer = ByteBuffer.allocate(point.size)
        if (point.type.contains("le")) buffer.order(ByteOrder.LITTLE_ENDIAN)
        else buffer.order(ByteOrder.BIG_ENDIAN)

        when (point.size) {
            1 -> buffer.put(value.toByte())
            2 -> buffer.putShort(value.toShort())
            4 -> buffer.putInt(value)
        }
        repository.writeMemory(addr, buffer.array())
    }

    fun runMacro(macroId: String, onLog: (String) -> Unit) {
        val profile = currentProfile ?: return
        val macro = profile.macros.find { it.id == macroId } ?: return
        
        serviceScope.launch {
            onLog("Starting Macro: $macroId")
            for (step in macro.steps) {
                if (step.delay != null) delay(step.delay)
                if (step.varId != null && step.value != null) {
                    val targetValue = if (step.value.all { it.isDigit() || it == '-' }) {
                        step.value.toInt()
                    } else {
                        (_uiState.value.raw[step.value] as? Number)?.toInt() ?: 0
                    }
                    writeVariable(step.varId, targetValue)
                }
            }
            onLog("Macro Finished: $macroId")
        }
    }

    fun writeMemory(address: Long, data: ByteArray) {
        repository.writeMemory(address, data)
    }

    private fun resolveEffectiveAddress(point: DataPoint, gameId: String?, platform: String? = null): Long? {
        if (point.pointer != null) {
            val chain = point.offsets
                ?: point.offset?.let { listOf(it) }
                ?: return null

            if (chain.size > MemoryConstants.MAX_POINTER_CHAIN_DEPTH) return null

            val ptrAddrStr = resolveFromMap(point.pointer, gameId) ?: return null
            val ptrAddr = parseHex(ptrAddrStr) ?: return null
            val order = if (platform == "GCN" || platform == "WII") ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN

            val ptrData = repository.readMemory(ptrAddr, 4) ?: return null
            var addr = ByteBuffer.wrap(ptrData).order(order).int.toLong() and 0xFFFFFFFFL
            if (addr == 0L) return null // Null pointer — entity not loaded

            for (i in chain.indices) {
                val off = parseHex(chain[i]) ?: return null
                addr += off
                if (i < chain.lastIndex) {
                    // Intermediate: dereference
                    val next = repository.readMemory(addr, 4) ?: return null
                    addr = ByteBuffer.wrap(next).order(order).int.toLong() and 0xFFFFFFFFL
                    if (addr == 0L) return null
                }
            }
            return addr
        }
        // Static address
        val addrStr = resolveFromMap(point.addresses, gameId) ?: return null
        return parseHex(addrStr)
    }

    private fun resolveFromMap(map: Map<String, String>, gameId: String?): String? {
        if (gameId == null) return map["default"]

        // 1. Exact match (e.g., GZLE01)
        map[gameId]?.let { return it }

        // 2. Short match (e.g., GZLE)
        if (gameId.length >= 4) {
            map[gameId.substring(0, 4)]?.let { return it }
        }

        // 3. Series match (e.g., GZL)
        if (gameId.length >= 3) {
            map[gameId.substring(0, 3)]?.let { return it }
        }

        return map["default"]
    }

    private fun parseHex(hex: String): Long? {
        if (hex.isBlank()) {
            Log.w(TAG, "Invalid hex address: empty string in profile")
            return null
        }
        return try {
            hex.removePrefix("0x").toLong(16)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid hex address '$hex' in profile: ${e.message}")
            null
        }
    }

    private fun parseValue(data: ByteArray, type: String): Any {
        val buffer = ByteBuffer.wrap(data)
        return when (type) {
            "u16_be" -> { buffer.order(ByteOrder.BIG_ENDIAN); if (data.size >= 2) buffer.short.toInt() and 0xFFFF else 0 }
            "u16_le" -> { buffer.order(ByteOrder.LITTLE_ENDIAN); if (data.size >= 2) buffer.short.toInt() and 0xFFFF else 0 }
            "u32_be" -> { buffer.order(ByteOrder.BIG_ENDIAN); if (data.size >= 4) buffer.int.toLong() and 0xFFFFFFFFL else 0 }
            "u8" -> if (data.size >= 1) data[0].toInt() and 0xFF else 0
            "float_be" -> { buffer.order(ByteOrder.BIG_ENDIAN); if (data.size >= 4) buffer.float else 0.0f }
            "u32_le" -> { buffer.order(ByteOrder.LITTLE_ENDIAN); if (data.size >= 4) buffer.int.toLong() and 0xFFFFFFFFL else 0 }
            "float_le" -> { buffer.order(ByteOrder.LITTLE_ENDIAN); if (data.size >= 4) buffer.float else 0.0f }
            else -> 0
        }
    }

    fun updateState(transform: (GameData) -> GameData) {
        _uiState.update(transform)
    }
}
