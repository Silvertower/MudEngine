package net.roninmud.mudengine

import net.roninmud.mudengine.utility.*

fun processCommand(ch: Character, input: String) {
  when (input.lowercase()) {
    "shutdown" -> game_info.mode = GameMode.SHUTDOWN
  }
}

fun handleConnectionState(client: Client, input: String) {
  when (client.state) {
    ConnectionState.GET_NAME -> {
      handleGetName(client, input)
    }
    else -> return
  }
}

fun handleGetName(client: Client, input: String) {

}
