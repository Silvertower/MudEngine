package net.roninmud.mudengine

import java.nio.channels.ServerSocketChannel

enum class ServerMode {
  SHUTDOWN, RUNNING, REBOOT, HOTBOOT
}

data class Server(val socket: ServerSocketChannel, val addr: String, val port: Int) {
  var mode: ServerMode = ServerMode.SHUTDOWN
}
