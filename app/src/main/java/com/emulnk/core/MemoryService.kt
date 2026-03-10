package com.emulnk.core

import android.util.Log
import com.emulnk.BuildConfig
import com.emulnk.data.MemoryRepository
import com.emulnk.core.math.MathEngine
import com.emulnk.core.model.DataPoint
import com.emulnk.core.model.IdentityResponse
import com.emulnk.model.MatchConfidence
import com.emulnk.core.model.ProfileConfig
import com.emulnk.core.resolver.AddressResolver
import com.emulnk.model.ConsoleConfig
import com.google.gson.Gson
import com.emulnk.model.GameData
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
    private val gson = Gson()

    private val _uiState = MutableStateFlow(GameData())
    val uiState: StateFlow<GameData> = _uiState

    private val _detectedGameId = MutableStateFlow<String?>(null)
    val detectedGameId: StateFlow<String?> = _detectedGameId

    private val _detectedConsole = MutableStateFlow<String?>(null)
    val detectedConsole: StateFlow<String?> = _detectedConsole

    private val _matchConfidence = MutableStateFlow<MatchConfidence?>(null)
    val matchConfidence: StateFlow<MatchConfidence?> = _matchConfidence

    private val _detectedGameHash = MutableStateFlow<String?>(null)
    val detectedGameHash: StateFlow<String?> = _detectedGameHash

    private var consoleConfigs: List<ConsoleConfig> = emptyList()
    private var currentProfile: ProfileConfig? = null
    private var currentConfidence: MatchConfidence? = null
    private var detectionJob: Job? = null
    private var pollingJob: Job? = null
    private var detectionFailures = 0
    private var wasGameDetected = false
    private var activePort: Int? = null

    /** Address resolver using the repository as its memory reader. */
    private val addressResolver = AddressResolver(
        memoryReader = object : AddressResolver.MemoryReader {
            override fun read(address: Long, size: Int): ByteArray? {
                return repository.readMemory(address, size)
            }
        },
        maxPointerChainDepth = MemoryConstants.MAX_POINTER_CHAIN_DEPTH
    )

    companion object {
        private const val TAG = "MemoryService"
    }

    private fun byteOrderFor(platform: String?): ByteOrder =
        if (platform == "GCN" || platform == "WII") ByteOrder.BIG_ENDIAN
        else ByteOrder.LITTLE_ENDIAN

    fun start(configs: List<ConsoleConfig>) {
        this.consoleConfigs = configs
        startDetection()
    }

    fun stop() {
        detectionJob?.cancel()
        pollingJob?.cancel()
        activePort = null
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
                val allPorts = consoleConfigs.map { it.port }.distinct()

                // Try active port first, then remaining ports
                val orderedPorts = activePort?.let { ap ->
                    listOf(ap) + allPorts.filter { it != ap }
                } ?: allPorts

                for (port in orderedPorts) {
                    repository.setPort(port)
                    val v2Json = repository.identifyV2() ?: continue
                    try {
                        val response = gson.fromJson(v2Json, IdentityResponse::class.java)
                        val gid = response?.game_id
                        if (gid != null && gid.isNotEmpty()
                            && gid.any { it.isLetterOrDigit() }) {
                            found = true
                            activePort = port
                            detectionFailures = 0
                            wasGameDetected = true
                            _detectedGameHash.value = response.game_hash
                            if (response.game_id != _detectedGameId.value || response.platform != _detectedConsole.value) {
                                _detectedGameId.value = response.game_id
                                _detectedConsole.value = response.platform
                            }
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "V2 hit on port $port: id=${response.game_id} hash=${response.game_hash} platform=${response.platform}")
                            }
                            break
                        }
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "V2 parse failed on port $port: ${e.message}")
                    }
                }

                if (!found) {
                    detectionFailures++
                    if (detectionFailures >= MemoryConstants.MAX_DETECTION_FAILURES) {
                        if (wasGameDetected) {
                            _detectedGameId.value = null
                            _detectedConsole.value = null
                            _detectedGameHash.value = null
                            _uiState.value = _uiState.value.copy(isConnected = false)
                            wasGameDetected = false
                        }
                    }
                    // After extended failures, clear active port to re-probe all
                    if (detectionFailures >= MemoryConstants.IDENTITY_REFRESH_FAILURES) {
                        activePort = null
                        detectionFailures = MemoryConstants.MAX_DETECTION_FAILURES
                    }
                }
                delay(if (found) MemoryConstants.DETECTION_SUCCESS_DELAY_MS else MemoryConstants.DETECTION_RETRY_DELAY_MS)
            }
        }
    }

    fun setProfile(
        profile: ProfileConfig,
        pollingIntervalMs: Long = MemoryConstants.POLLING_INTERVAL_MS,
        confidence: MatchConfidence = MatchConfidence.MATCHED
    ) {
        currentProfile = profile
        currentConfidence = confidence
        _matchConfidence.value = confidence
        startPolling(profile, pollingIntervalMs, confidence)
    }

    private fun startPolling(profile: ProfileConfig, pollingIntervalMs: Long, confidence: MatchConfidence) {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive) {
                val newValues = mutableMapOf<String, Any>()
                val rawValues = mutableMapOf<String, Any>()
                var successCount = 0
                val gameId = _detectedGameId.value
                val byteOrder = byteOrderFor(profile.platform)

                for (point in profile.dataPoints) {
                    // In FALLBACK mode, skip unstable data points
                    if (confidence == MatchConfidence.FALLBACK && !point.stable) continue

                    val addressLong = addressResolver.resolve(point, profile, gameId, byteOrder) ?: continue
                    val rawData = repository.readMemory(addressLong, point.size)

                    if (rawData != null) {
                        val rawNum = parseValue(rawData, point.type)
                        rawValues[point.id] = rawNum

                        var processedValue = rawNum
                        val formula = point.formula
                        if (formula != null && rawNum is Number) {
                            processedValue = MathEngine.evaluate(formula, rawNum.toDouble())
                        }

                        newValues[point.id] = processedValue
                        successCount++
                    }
                }

                _uiState.value = _uiState.value.copy(
                    isConnected = successCount > 0,
                    values = newValues,
                    raw = rawValues,
                    confidence = currentConfidence?.name,
                    gameHash = _detectedGameHash.value
                )

                delay(pollingIntervalMs)
            }
        }
    }

    fun writeVariable(varId: String, value: Int) {
        val profile = currentProfile ?: return
        val gameId = _detectedGameId.value ?: return
        val point = profile.dataPoints.find { it.id == varId } ?: return
        val byteOrder = byteOrderFor(profile.platform)
        val addr = addressResolver.resolve(point, profile, gameId, byteOrder) ?: return
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
                val stepDelay = step.delay
                if (stepDelay != null) delay(stepDelay)
                val stepVarId = step.varId
                val stepValue = step.value
                if (stepVarId != null && stepValue != null) {
                    val targetValue = if (stepValue.all { it.isDigit() || it == '-' }) {
                        stepValue.toIntOrNull() ?: 0
                    } else {
                        (_uiState.value.raw[stepValue] as? Number)?.toInt() ?: 0
                    }
                    writeVariable(stepVarId, targetValue)
                }
            }
            onLog("Macro Finished: $macroId")
        }
    }

    fun writeMemory(address: Long, data: ByteArray) {
        repository.writeMemory(address, data)
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
            "bytes" -> android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
            else -> 0
        }
    }

    fun updateState(transform: (GameData) -> GameData) {
        _uiState.update(transform)
    }
}
