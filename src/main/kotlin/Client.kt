package net.roninmud.mudengine

import net.roninmud.mudengine.utility.*

import java.nio.channels.SocketChannel
import java.util.LinkedList
import java.util.Queue

enum class ConnectionState {
  DISCONNECTED, PLAYING, GET_NAME
}

class Client(val socket: SocketChannel, val addr: String, val port: Int) {
  var state: ConnectionState = ConnectionState.DISCONNECTED
  var buffer: CharArray = CharArray(MAX_STRING_LENGTH)
  var lastInput: String = String()
  val input: Queue<String> = LinkedList()
  val output: Queue<String> = LinkedList()
  //lateinit var ch: Character
  var ch: Character = Character()
}
