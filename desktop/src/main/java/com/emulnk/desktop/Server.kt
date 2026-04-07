package com.emulnk.desktop

import com.emulnk.core.constants.ConsoleConfigDefaults
import com.emulnk.core.constants.MemoryConstants
import com.emulnk.core.memory.MemoryRepository
import com.emulnk.core.memory.MemoryService
import com.emulnk.core.model.ConsoleConfig
import com.emulnk.core.model.ProfileConfig
import com.google.gson.Gson
import com.xenomachina.argparser.ArgParser
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.send
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileReader
import kotlin.io.encoding.Base64

fun main(argsRaw: Array<String>) {
    val args = ArgParser(argsRaw).parseInto(::DesktopRunArgs)
    val gson = Gson()
    val profile = gson.fromJson(FileReader(args.themePath), ProfileConfig::class.java)

    val configs = ConsoleConfigDefaults
    val memory = MemoryRepository(args.emulatorHost)

    val memoryService = MemoryService(memory)
    memoryService.setProfile(profile)
    memoryService.start(configs)
    embeddedServer(
        Netty,
        port = args.port,
        host = "0.0.0.0",
    ) {
        install(WebSockets)
        routing {
            webSocket("/payload") {
                while(true) {
                    val gameData = memoryService.uiState.value
                    val encoded = Base64.encode(gson.toJson(gameData).toByteArray())

                    send(encoded)
                    delay(MemoryConstants.POLLING_INTERVAL_MS)
                }
            }
        }
    }.start(wait = true)
}