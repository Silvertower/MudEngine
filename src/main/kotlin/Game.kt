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
import java.util.*
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource.Monotonic

const val MICROSECONDS_PER_PULSE: Int = 100_000
const val PULSES_PER_SECOND: Int = 1_000_000 / MICROSECONDS_PER_PULSE
const val PULSE_RATE: Int = 1_000 / PULSES_PER_SECOND

val game_info: GameInfo = GameInfo()
val client_list: LinkedList<Client> = LinkedList()

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

  Log.info { "Server listening [ ${serverSocketChannel.socket().inetAddress.hostAddress}:${serverSocketChannel.socket().localPort} ]" }

  gameLoop(serverSocketChannel)
}

@OptIn(ExperimentalTime::class)
fun gameLoop(serverSocketChannel: ServerSocketChannel) {
  var pulse = 0
  var pulseQueue = 0

  val sleepSelector = Selector.open()
  val selector = Selector.open()

  game_info.mode = GameMode.RUNNING
  serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT, serverSocketChannel)

  var timer = Monotonic.markNow()

  while (game_info.mode != GameMode.SHUTDOWN) {
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
      val server = key.attachment() as ServerSocketChannel

      acceptClient(selector, server)
    }

    // process input
    for (key in readList) {
      val client = key.attachment() as Client

      if (processInput(client) < 0) {
        closeClient(client)
      }
    }

    // process commands
    for (client in client_list) {
      if ((client.ch.wait > 0 && --client.ch.wait > 0) || client.input.isEmpty()) continue

      val input = client.input.remove()

      client.ch.wait = 1

      if (true || client.state == ConnectionState.PLAYING) {
        processCommand(client.ch, input)
      } else {
        handleConnectionState(client, input)
      }
    }

    // process output
    for (key in writeList) {
      val client = key.attachment() as Client

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

  shutdownGame(serverSocketChannel)
}

fun shutdownGame(serverSocketChannel: ServerSocketChannel) {
  while (client_list.isNotEmpty()) {
    closeClient(client_list.remove())
  }

  serverSocketChannel.close()
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

    client_list.add(client)

    Log.info { "New client [ ${client.addr}:${client.port} ]" }
  } catch (e: Throwable) {
    Log.warn { "Error accepting client connection." }
    Log.debug { e.printStackTrace() }
  }
}

fun closeClient(client: Client) {
  Log.info { "Closing client [ ${client.addr} ]." }

  client.socket.close()
  client_list.remove(client)
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

  if (bytesRead <= 0) {
    return bytesRead
  }

  readBuffer.flip()

  // Setup an ASCII decoder that ignores malformed or unmappable characters.
  val decoder = StandardCharsets.US_ASCII
    .newDecoder()
    .onMalformedInput(CodingErrorAction.IGNORE)
    .onUnmappableCharacter(CodingErrorAction.IGNORE)

  val decoded = decoder.decode(readBuffer)

  val offset = client.buffer.indexOfFirst { c -> isAsciiNull(c) }
  val length = decoded.remaining()

  // Return -1 if the buffer would overflow.
  if (offset >= 0 && offset + length > client.buffer.size) {
    return -1
  }

  decoded.get(client.buffer, if (offset > -1) offset else 0, length)

  var read = 0
  var nl = client.buffer.indexOfFirst { c -> isAsciiNewline(c) }
  while (nl > -1) {
    var write = 0
    val temp = CharArray(MAX_INPUT_LENGTH)
    while (read < nl && write < temp.size - if (client.buffer[read] != '$') 0 else 1) {
      when {
        isAsciiPrintable(client.buffer[read]) -> {
          temp[write] = client.buffer[read]

          if (temp[write] == '$') {
            write++

            temp[write] = '$'
          }

          write++
        }
        isAsciiBackspaceOrDelete(client.buffer[read]) && write > 0 -> {
          write--

          if (temp[write] == '$') {
            write--
          }
        }
      }

      read++
    }

    if (write < temp.lastIndex) {
      temp[write] = ASCII_NULL
    }

    val input = String(temp.sliceArray(0 until write))

    if (read < nl && write == temp.lastIndex) {
      if (writeToClient(client, "Line too long.  Truncated to:\n\r${input}\n\r") < 0) {
        return -1
      }
    }

    if (!input.startsWith('!')) {
      client.lastInput = input
    }

    client.input.add(client.lastInput)
    client.output.add("${client.lastInput}\n\r") // debug

    while (read < client.buffer.size && isAsciiNewline(client.buffer[read])) {
      read++
    }

    nl = -1
    while (read < client.buffer.size && client.buffer[read] != ASCII_NULL && nl < 0) {
      read++

      if (isAsciiNewline(client.buffer[read])) {
        nl = read
      }
    }
  }

  var i = 0
  while (read + i < client.buffer.size && client.buffer[i] != ASCII_NULL) {
    client.buffer[i] = client.buffer[read + i]
    i++
  }

  if (i < client.buffer.lastIndex) {
    client.buffer[i] = ASCII_NULL
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

fun writeToClient(client: Client, message: String): Int {
  val bytesWritten = try {
    client.socket.write(ByteBuffer.wrap(message.toByteArray()))
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
