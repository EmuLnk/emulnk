package com.emulnk.data

import android.util.Log
import com.emulnk.BuildConfig
import com.emulnk.core.MemoryConstants
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Communicates with the emulator via UDP.
 */
class MemoryRepository(
    host: String = "127.0.0.1",
    private var port: Int = 55355
) {
    private val address: InetAddress = InetAddress.getByName(host)
    private var socket: DatagramSocket? = null

    companion object {
        private const val TAG = "MemoryRepository"
    }

    @Synchronized
    fun setPort(newPort: Int) {
        if (this.port != newPort) {
            this.port = newPort
            socket?.close()
            socket = null
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

    @Synchronized
    fun readMemory(memoryAddress: Long, size: Int): ByteArray? {
        if (memoryAddress < 0 || memoryAddress > MemoryConstants.MAX_ADDRESS) {
            if (BuildConfig.DEBUG) Log.w(TAG, "readMemory: address 0x${memoryAddress.toString(16)} out of valid range")
            return null
        }
        if (size <= 0 || size > MemoryConstants.MAX_READ_SIZE) {
            if (BuildConfig.DEBUG) Log.w(TAG, "readMemory: invalid size $size (must be 1..${MemoryConstants.MAX_READ_SIZE})")
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

            // Drain stale packets from previous requests
            val savedTimeout = currentSocket.soTimeout
            currentSocket.soTimeout = 1
            val drain = DatagramPacket(ByteArray(1), 1)
            repeat(10) {
                try {
                    currentSocket.receive(drain)
                } catch (_: java.net.SocketTimeoutException) { return@repeat }
            }
            currentSocket.soTimeout = savedTimeout

            currentSocket.send(requestPacket)

            val receiveBuffer = ByteArray(maxOf(size, 256))
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            currentSocket.receive(receivePacket)
            
            receivePacket.data.copyOf(receivePacket.length)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "UDP read failed at 0x${memoryAddress.toString(16)}: ${e.message}", e)
            }
            null
        }
    }

    @Synchronized
    fun close() {
        socket?.close()
        socket = null
    }

    @Synchronized
    fun writeMemory(memoryAddress: Long, data: ByteArray): Boolean {
        if (memoryAddress < 0 || memoryAddress > MemoryConstants.MAX_ADDRESS) {
            if (BuildConfig.DEBUG) Log.w(TAG, "writeMemory: address 0x${memoryAddress.toString(16)} out of valid range")
            return false
        }
        if (data.isEmpty()) {
            if (BuildConfig.DEBUG) Log.w(TAG, "writeMemory: empty data array")
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
                Log.e(TAG, "UDP write failed at 0x${memoryAddress.toString(16)}: ${e.message}", e)
            }
            false
        }
    }
}
