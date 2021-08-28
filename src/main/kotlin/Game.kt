package net.roninmud.mudengine

import net.roninmud.mudengine.utility.*

import java.net.BindException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource.Monotonic

const val MICROSECONDS_PER_PULSE: Int = 100_000
const val PULSES_PER_SECOND: Int = 1_000_000 / MICROSECONDS_PER_PULSE
const val PULSE_RATE: Int = 1_000 / PULSES_PER_SECOND

val ClientList: MutableList<Client> = mutableListOf()

fun runGame(port: Int) {
  val serverInetSocketAddress = try {
    InetSocketAddress(port)
  } catch (e: IllegalArgumentException) {
    Log.error { "Port out of range: $port" }
    return
  }

  val serverSocketChannel = ServerSocketChannel.open()
  serverSocketChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true)
  serverSocketChannel.configureBlocking(false)

  try {
    serverSocketChannel.bind(serverInetSocketAddress)
  } catch (e: BindException) {
    Log.error { "Address ${serverInetSocketAddress.address.hostAddress}:${serverInetSocketAddress.port} is already bound." }
    return
  }

  val server = Server(
    serverSocketChannel,
    serverSocketChannel.socket().inetAddress.hostAddress,
    serverSocketChannel.socket().localPort
  )

  Log.info { "Server listening [ ${serverSocketChannel.socket().inetAddress.hostAddress}:${serverSocketChannel.socket().localPort} ]" }

  gameLoop(server)
}

@OptIn(ExperimentalTime::class)
fun gameLoop(server: Server) {
  var pulse = 0
  var pulseQueue = 0

  val sleepSelector = Selector.open()
  val selector = Selector.open()

  server.mode = ServerMode.RUNNING
  server.socket.register(selector, SelectionKey.OP_ACCEPT, server.socket)

  var timer = Monotonic.markNow()

  while (server.mode != ServerMode.SHUTDOWN && pulse < 600) {
    var processTime = timer.elapsedNow()

    if (processTime.inWholeMicroseconds >= MICROSECONDS_PER_PULSE) {
      pulseQueue = processTime.toComponents { seconds, microseconds ->
        (seconds.toInt() * PULSES_PER_SECOND) + (microseconds / MICROSECONDS_PER_PULSE)
      }

      processTime = Duration.microseconds(processTime.inWholeMicroseconds % MICROSECONDS_PER_PULSE)
    }

    sleepSelector.select(PULSE_RATE - processTime.inWholeMilliseconds)

    timer = Monotonic.markNow()

    selector.selectNow()

    val acceptList = selector.selectedKeys().filter { key: SelectionKey -> key.isAcceptable }
    val readList = selector.selectedKeys().filter { key: SelectionKey -> key.isReadable }
    val writeList = selector.selectedKeys().filter { key: SelectionKey -> key.isWritable }

    // handle new connections
    for (key in acceptList) {
      val serverSocketChannel = if (key.isValid) key.attachment() as ServerSocketChannel else continue
      acceptClient(selector, serverSocketChannel)
    }

    // process input
    for (key in readList) {
      val client = if (key.isValid) key.attachment() as Client else continue
      if (processInput(client) < 0) {
        closeClient(client)
      }
    }

    // process commands
    for (client in ClientList) {
      val input = if (client.input.isNotEmpty()) client.input.remove() else continue

      when (input.lowercase()) {
        "shutdown" -> server.mode = ServerMode.SHUTDOWN
      }
    }

    // process output
    for (key in writeList) {
      val client = if (key.isValid) key.attachment() as Client else continue
      if (client.output.isNotEmpty()) {
        if (processOutput(client) < 0) {
          closeClient(client)
        }
      }
    }

    // close clients that are disconnected

    pulseQueue++

    if (pulseQueue > 1) {
      Log.warn { "Missed $pulseQueue pulses (${PULSES_PER_SECOND}/sec)." }
    }

    while (pulseQueue > 0) {
      pulseQueue--

      heartbeat(++pulse)
    }

    if (pulse >= 24 * 60 * 60 * PULSES_PER_SECOND) {
      pulse = 0
    }

    selector.selectedKeys().clear()
  }

  shutdownGame(server)
}

fun shutdownGame(server: Server) {
  for (client in ClientList) {
    closeClient(client)
  }

  server.socket.close()
}

fun acceptClient(selector: Selector, serverSocketChannel: ServerSocketChannel) {
  try {
    val clientSocketChannel = serverSocketChannel.accept()

    val client = Client(
      clientSocketChannel,
      clientSocketChannel.socket().inetAddress.hostAddress,
      clientSocketChannel.socket().port
    )

    client.socket.configureBlocking(false)
    client.socket.register(selector, SelectionKey.OP_READ or SelectionKey.OP_WRITE, client)

    ClientList.add(client)

    Log.info { "New client [ ${client.addr}:${client.port} ]" }
  } catch (e: Throwable) {
    Log.warn { "Error accepting client connection." }
    Log.debug { e.printStackTrace() }
  }
}

fun closeClient(client: Client) {
  Log.info { "Closing client [ ${client.addr} ]." }

  client.socket.close()
  ClientList.remove(client)
}

fun processInput(client: Client): Int {
  val readBuffer = ByteBuffer.allocate(MAX_STRING_LENGTH)

  val bytesRead = try {
    client.socket.read(readBuffer)
  } catch (e: Throwable) {
    Log.warn { "Error reading from client [ ${client.addr} ]." }
    Log.debug { e.printStackTrace() }
    return -1
  }

  // If we didn't read any bytes, return.
  if (bytesRead <= 0) {
    return bytesRead
  }

  // Flip the buffer for reading.
  readBuffer.flip()

  // Setup an ASCII decoder that ignores malformed or unmappable characters.
  val decoder = StandardCharsets.US_ASCII
    .newDecoder()
    .onMalformedInput(CodingErrorAction.IGNORE)
    .onUnmappableCharacter(CodingErrorAction.IGNORE)

  // Decode the read buffer into a byte array of ASCII characters, then check for a CR/LF.
  val decoded = decoder.decode(readBuffer)

  val offset = client.buffer.indexOfFirst { c -> isAsciiNull(c) }
  val length = decoded.remaining()

  // Return -1 if the buffer would overflow.
  if (offset < 0 || offset + length >= client.buffer.lastIndex) {
    return -1
  }

  decoded.get(client.buffer, offset, length)

  client.buffer[offset + length] = ASCII_NULL

  val nl = client.buffer.indexOfFirst { c -> isAsciiNewline(c) }

  // Return if no newline was found.
  if (nl < 0) {
    Log.debug { String(client.buffer) }
    return bytesRead
  }

  // At this point, we know we have input to process.
  val input = CharArray(MAX_INPUT_LENGTH)
  var read = 0
  var write = 0
  while (read < client.buffer.size && !isAsciiNull(client.buffer[read])) {
    var truncate = false
    while (!isAsciiNewline(client.buffer[read]) && !truncate) {
      when {
        isAsciiPrintable(client.buffer[read]) -> {
          input[write] = client.buffer[read]
          if (client.buffer[read] == '$') {
            write++
            input[write] = '$'
          }
          write++
        }
        isAsciiBackspaceOrDelete(client.buffer[read]) -> {
          write--
          if (client.buffer[read] == '$') {
            write--
          }
        }
      }
      read++
      truncate = write >= input.size - if (client.buffer[read] == '$') 1 else 2
    }

    input[write] = ASCII_NULL

    when (input[0]) {
      '!' -> truncate = false
      else -> client.lastInput = String(input.sliceArray(0 until write))
    }

    client.input.add(client.lastInput)
    client.output.add(client.lastInput + "\n\r") // debug

    if (truncate) {
      // inform the player
      Log.debug { "Truncate" }

      while (!isAsciiNewline(client.buffer[read])) {
        read++
      }
    }

    while (isAsciiNewline(client.buffer[read])) {
      read++
    }

    var copy = 0
    while (copy < client.buffer.size && client.buffer[copy] != ASCII_NULL) {
      client.buffer[copy++] = client.buffer[read + copy]
    }

    read = 0
    write = 0
  }

  return bytesRead
}

fun processOutput(client: Client): Int {
  var bytesWritten = 0
  while (client.output.isNotEmpty()) {
    bytesWritten += writeToClient(client, client.output.remove())
    if (bytesWritten < 0) {
      return bytesWritten
    }
  }

  return bytesWritten
}

fun writeToClient(client: Client, s: String): Int {
  val bytesWritten = try {
    client.socket.write(ByteBuffer.wrap(s.toByteArray()))
  } catch (e: Throwable) {
    -1
  }

  return bytesWritten
}

fun heartbeat(pulse: Int) {
  if (pulse % (1 * PULSES_PER_SECOND) == 0) {
    Log.debug { "Seconds passed: ${pulse / (1 * PULSES_PER_SECOND)}" }
  }
}
