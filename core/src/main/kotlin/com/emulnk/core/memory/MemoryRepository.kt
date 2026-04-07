package com.emulnk.core.memory

import co.touchlab.kermit.Logger
import com.emulnk.BuildConfig
import com.emulnk.core.constants.MemoryConstants
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Communicates with the emulator via UDP.
 */
class MemoryRepository(
    host: String,
    private var port: Int = 55355
) : Closeable {
    private var address: InetAddress = InetAddress.getByName(host)
    private var socket: DatagramSocket? = null
    private var batchSupported: Boolean? = null

    companion object {
        private const val TAG = "MemoryRepository"
        private const val V2_RECEIVE_BUFFER_SIZE = 1024
        private const val BATCH_MAGIC_E = 0x45.toByte() // 'E'
        private const val BATCH_MAGIC_L = 0x4C.toByte() // 'L'
    }

    @Synchronized
    fun setPort(newPort: Int) {
        if (this.port != newPort) {
            this.port = newPort
            socket?.close()
            socket = null
            batchSupported = null
        }
    }

    @Synchronized
    fun setHost(newHost: String) {
        val newAddress = InetAddress.getByName(newHost)
        if (this.address != newAddress) {
            this.address = newAddress
            socket?.close()
            socket = null
            batchSupported = null
        }
    }

    @Synchronized
    private fun getSocket(): DatagramSocket {
        val s = socket
        if (s == null || s.isClosed) {
            val newSocket = DatagramSocket()
            newSocket.soTimeout = MemoryConstants.SOCKET_TIMEOUT_MS
            socket = newSocket
            return newSocket
        }
        return s
    }

    private fun drainStalePackets(sock: DatagramSocket) {
        val saved = sock.soTimeout
        try {
            sock.soTimeout = 1
            val drain = DatagramPacket(ByteArray(1), 1)
            repeat(10) {
                try { sock.receive(drain) } catch (_: SocketTimeoutException) { return }
            }
        } finally {
            sock.soTimeout = saved
        }
    }

    @Synchronized
    fun readMemory(memoryAddress: Long, size: Int): ByteArray? {
        if (memoryAddress < 0 || memoryAddress > MemoryConstants.MAX_ADDRESS) {
            if (BuildConfig.DEBUG) Logger.Companion.w("readMemory: address 0x${memoryAddress.toString(16)} out of valid range")
            return null
        }
        if (size <= 0 || size > MemoryConstants.MAX_READ_SIZE) {
            if (BuildConfig.DEBUG) Logger.Companion.w("readMemory: invalid size $size (must be 1..${MemoryConstants.MAX_READ_SIZE})")
            return null
        }
        return try {
            val buffer = ByteBuffer.allocate(8).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(memoryAddress.toInt())
                putInt(size)
            }
            val requestPacket = DatagramPacket(buffer.array(), 8, address, port)
            val currentSocket = getSocket()

            drainStalePackets(currentSocket)

            currentSocket.send(requestPacket)

            val receiveBuffer = ByteArray(maxOf(size, 256))
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            currentSocket.receive(receivePacket)

            receivePacket.data.copyOf(receivePacket.length)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Logger.Companion.e("UDP read failed at 0x${memoryAddress.toString(16)}: ${e.message}", e)
            }
            null
        }
    }

    /**
     * V2 identify: sends EMLKV2 magic (6 bytes) and expects a JSON response.
     * Returns the raw JSON string, or null if the emulator doesn't support V2.
     */
    @Synchronized
    fun identifyV2(): String? {
        batchSupported = null
        val currentSocket = getSocket()
        val savedTimeout = currentSocket.soTimeout
        return try {
            currentSocket.soTimeout = MemoryConstants.IDENTIFY_V2_TIMEOUT_MS
            drainStalePackets(currentSocket)

            val requestPacket = DatagramPacket(
                MemoryConstants.IDENTIFY_V2_MAGIC,
                MemoryConstants.IDENTIFY_V2_MAGIC.size,
                address,
                port
            )
            currentSocket.send(requestPacket)

            val receiveBuffer = ByteArray(V2_RECEIVE_BUFFER_SIZE)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            currentSocket.receive(receivePacket)

            val response = receivePacket.data.copyOf(receivePacket.length)
                .decodeToString().trim()
            // V2 response must be JSON (starts with '{')
            if (response.startsWith("{")) response else null
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Logger.Companion.d("identifyV2() on port $port: ${e.message}")
            }
            null
        } finally {
            currentSocket.soTimeout = savedTimeout
        }
    }

    @Synchronized
    fun supportsBatch(): Boolean {
        batchSupported?.let { return it }

        val requestSize = 4 + 1 * 8
        val buffer = ByteBuffer.allocate(requestSize).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put(BATCH_MAGIC_E)
            put(BATCH_MAGIC_L)
            putShort(1)
            putInt(0)   // addr 0
            putInt(1)   // size 1
        }

        return try {
            val currentSocket = getSocket()
            drainStalePackets(currentSocket)
            currentSocket.send(DatagramPacket(buffer.array(), requestSize, address, port))

            val recv = ByteArray(64)
            val pkt = DatagramPacket(recv, recv.size)
            currentSocket.receive(pkt)

            val supported = pkt.length >= 4 &&
                recv[0] == BATCH_MAGIC_E && recv[1] == BATCH_MAGIC_L
            batchSupported = supported
            supported
        } catch (e: Exception) {
            batchSupported = false
            false
        }
    }

    @Synchronized
    fun readBatch(requests: List<Pair<Long, Int>>): List<ByteArray?> {
        if (requests.isEmpty()) return emptyList()

        val count = requests.size.coerceAtMost(MemoryConstants.BATCH_MAX_ENTRIES)
        if (requests.size > MemoryConstants.BATCH_MAX_ENTRIES) {
            Logger.Companion.w("Batch truncated: ${requests.size} > ${MemoryConstants.BATCH_MAX_ENTRIES}")
        }

        // Batch wire format: "EL" + count:u16le + N x (addr:u32le + size:u32le)
        return try {
            val requestSize = 4 + count * 8
            val buffer = ByteBuffer.allocate(requestSize).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                put(BATCH_MAGIC_E)
                put(BATCH_MAGIC_L)
                putShort(count.toShort())
                for (i in 0 until count) {
                    putInt(requests[i].first.toInt())
                    putInt(requests[i].second)
                }
            }

            val currentSocket = getSocket()
            drainStalePackets(currentSocket)
            currentSocket.send(DatagramPacket(buffer.array(), requestSize, address, port))

            val receiveBuffer = ByteArray(MemoryConstants.BATCH_RESPONSE_BUFFER)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            currentSocket.receive(receivePacket)

            val resp = ByteBuffer.wrap(receivePacket.data, 0, receivePacket.length)
                .order(ByteOrder.LITTLE_ENDIAN)

            if (resp.remaining() < 4) return List(count) { null }
            if (resp.get() != BATCH_MAGIC_E || resp.get() != BATCH_MAGIC_L)
                return List(count) { null }

            val respCount = resp.short.toInt() and 0xFFFF
            val results = mutableListOf<ByteArray?>()

            for (i in 0 until respCount.coerceAtMost(count)) {
                if (resp.remaining() < 2) { results.add(null); continue }
                val len = resp.short.toInt() and 0xFFFF
                if (len == 0 || resp.remaining() < len) { results.add(null); continue }
                val data = ByteArray(len)
                resp.get(data)
                results.add(data)
            }

            while (results.size < count) results.add(null)
            results
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Logger.Companion.d("readBatch(${requests.size} entries): ${e.message}")
            }
            List(count) { null }
        }
    }

    @Synchronized
    override fun close() {
        socket?.close()
        socket = null
    }

    @Synchronized
    fun writeMemory(memoryAddress: Long, data: ByteArray): Boolean {
        if (memoryAddress < 0 || memoryAddress > MemoryConstants.MAX_ADDRESS) {
            if (BuildConfig.DEBUG) Logger.Companion.w("writeMemory: address 0x${memoryAddress.toString(16)} out of valid range")
            return false
        }
        if (data.isEmpty()) {
            if (BuildConfig.DEBUG) Logger.Companion.w("writeMemory: empty data array")
            return false
        }
        return try {
            val size = data.size
            val buffer = ByteBuffer.allocate(8 + size).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(memoryAddress.toInt())
                putInt(size)
                put(data)
            }
            val requestPacket = DatagramPacket(buffer.array(), buffer.capacity(), address, port)
            getSocket().send(requestPacket)
            true
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Logger.Companion.e("UDP write failed at 0x${memoryAddress.toString(16)}: ${e.message}", e)
            }
            false
        }
    }
}