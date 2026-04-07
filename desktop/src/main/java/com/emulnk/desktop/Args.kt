package com.emulnk.desktop

import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default

class DesktopRunArgs(parser: ArgParser) {
    val themePath by parser.storing(
        "-t", "--theme",
        help = "The path of the theme to load, e.g. ../emulnk-repo/themes/SMS.json"
    )

    val emulatorHost by parser.storing(
        "-h", "--host",
        help = "The hostname of the device running the emulator."
    ).default("localhost")

    val port by parser.storing(
        "-p", "--port",
        help = "The port to host the websocket server on."
    ) { toInt() }.default(8080)
}