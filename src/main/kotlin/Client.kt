package net.roninmud.mudengine

import net.roninmud.mudengine.utility.*
import java.nio.ByteBuffer

import java.nio.channels.SocketChannel
import java.util.LinkedList
import java.util.Queue

enum class ConnectionState {
  DISCONNECTED, PLAYING, GET_NAME
}

class Client(val socket: SocketChannel, val addr: String, val port: Int) {
  var state: ConnectionState = ConnectionState.DISCONNECTED
  var hasPrompt: Boolean = false
  var lastCommand: String = String()
  var inputBuffer: ByteBuffer = ByteBuffer.allocate(MAX_STRING_LENGTH)
  var outputBuffer: ByteBuffer = ByteBuffer.allocate(MAX_STRING_LENGTH)
  val inputQueue: Queue<String> = LinkedList()
  val outputQueue: Queue<String> = LinkedList()
  var ch: Character = Character()
}
