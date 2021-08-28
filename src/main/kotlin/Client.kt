package net.roninmud.mudengine

import net.roninmud.mudengine.utility.*

import java.nio.channels.SocketChannel
import java.util.LinkedList
import java.util.Queue

data class Client(val socket: SocketChannel, val addr: String, val port: Int) {
  var buffer: CharArray = CharArray(MAX_STRING_LENGTH)
  var lastInput: String = String()
  val input: Queue<String> = LinkedList()
  val output: Queue<String> = LinkedList()
}
