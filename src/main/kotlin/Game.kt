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
import java.util.LinkedList
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource.Monotonic

const val NANOSECONDS_PER_PULSE: Int = 100_000_000
const val PULSES_PER_SECOND: Int = 1_000_000_000 / NANOSECONDS_PER_PULSE
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

  val acceptSet: MutableList<ServerSocketChannel> = mutableListOf()
  val readSet: HashSet<Client> = HashSet()
  val writeSet: HashSet<Client> = HashSet()

  game_info.mode = GameMode.RUNNING

  serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT, serverSocketChannel)

  var timer = Monotonic.markNow()

  while (game_info.mode != GameMode.SHUTDOWN) {
    var processTime = timer.elapsedNow()

    if (processTime.inWholeMicroseconds >= NANOSECONDS_PER_PULSE) {
      pulseQueue = processTime.toComponents { seconds, nanoseconds ->
        (seconds.toInt() * PULSES_PER_SECOND) + (nanoseconds / NANOSECONDS_PER_PULSE)
      }

      processTime = Duration.nanoseconds(processTime.inWholeNanoseconds % NANOSECONDS_PER_PULSE)
    }

    sleepSelector.select(PULSE_RATE - processTime.inWholeMilliseconds)

    timer = Monotonic.markNow()

    selector.selectNow()

    for (key in selector.selectedKeys()) {
      if (!key.isValid) continue

      if (key.isAcceptable) {
        acceptSet.add(key.attachment() as ServerSocketChannel)
      }

      if (key.isReadable) {
        readSet.add(key.attachment() as Client)
      }

      if (key.isWritable) {
        writeSet.add(key.attachment() as Client)
      }
    }

    // Accept new client connections.
    for (ssc in acceptSet) {
      acceptClient(selector, ssc)
    }

    // Process client input.
    for (client in client_list) {
      if (!readSet.contains(client)) continue

      if (processInput(client) < 0) {
        closeClient(client)
      }
    }

    // Process client commands.
    for (client in client_list) {
      client.ch.wait -= if (client.ch.wait > 0) 1 else 0

      if (client.ch.wait > 0 || client.input.isEmpty()) continue

      val input = client.input.remove()

      client.ch.wait++
      client.hasPrompt = false

      if (client.state == ConnectionState.PLAYING) {
        processCommand(client.ch, input)
      } else {
        handleConnectionState(client, input)
      }
    }

    // Process client output.
    for (client in client_list) {
      if (!writeSet.contains(client) || client.output.isEmpty()) continue

      if (processOutput(client) < 0) {
        closeClient(client)
      } else {
        client.hasPrompt = false
      }
    }

    // Print client prompts.
    for (client in client_list) {
      if (!client.hasPrompt) {
        writeToClient(client, makePrompt(client))

        client.hasPrompt = true
      }
    }

    // Close clients that are disconnected.
    for (client in client_list) {
      if (client.state == ConnectionState.DISCONNECTED) {
        closeClient(client)
      }
    }

    // Increment the pulse queue counter and check for missed pulses.
    if (++pulseQueue > 1) {
      Log.warn { "Missed $pulseQueue pulses (${PULSES_PER_SECOND}/sec)." }
    }

    // Execute heartbeat functions.
    do {
      heartbeat(++pulse)
    } while (--pulseQueue > 0)

    // Reset the pulse counter after 24 hours.
    if (pulse >= 24 * 60 * 60 * PULSES_PER_SECOND) {
      pulse = 0
    }

    // Clear the selected keys and the filtered selected key sets before the next iteration.
    selector.selectedKeys().clear()
    acceptSet.clear()
    readSet.clear()
    writeSet.clear()
  }

  shutdownGame(serverSocketChannel)
}

fun shutdownGame(serverSocketChannel: ServerSocketChannel) {
  for (client in client_list) {
    closeClient(client)
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

    client.state = ConnectionState.PLAYING
    client.ch.client = client

    client_list.add(client)

    Log.info { "New client [ ${client.addr}:${client.port} ]" }
  } catch (e: Throwable) {
    Log.error { "Error accepting client connection." }
    Log.debug { e.printStackTrace() }
  }
}

fun closeClient(client: Client) {
  Log.info { "Closing client [ ${client.addr} ]." }

  client.socket.close()

  client_list.remove(client)
}

fun processInput(client: Client): Int {
  val readBuffer = ByteBuffer.allocate(client.buffer.size)
  var totalBytesRead = 0
  do {
    val bytesRead = try {
      client.socket.read(readBuffer)
    } catch (e: Throwable) {
      Log.error { "Error reading from client [ ${client.addr} ]." }
      Log.debug { e.printStackTrace() }
      return -1
    }

    if (bytesRead > 0) {
      totalBytesRead += bytesRead
    }
  } while (bytesRead > 0)

  // If we didn't read any bytes, return the result (0 = try again later, -1 = close the client).
  if (totalBytesRead <= 0) {
    return totalBytesRead
  }

  // Flip the buffer for reading.
  readBuffer.flip()

  // Setup an ASCII decoder that ignores malformed or unmappable characters.
  val decoder = StandardCharsets.US_ASCII
    .newDecoder()
    .onMalformedInput(CodingErrorAction.IGNORE)
    .onUnmappableCharacter(CodingErrorAction.IGNORE)

  // Decode the buffer into ASCII.
  val decoded = decoder.decode(readBuffer)

  // Determine the existing offset of the client's buffer and the number of decoded characters to process.
  val offset = client.buffer.indexOfFirst { c -> isAsciiNull(c) }
  val length = decoded.remaining()

  // Return -1 to close the client if the client's buffer would overflow after writing the decoded characters into it.
  if (offset >= 0 && offset + length > client.buffer.size) {
    return -1
  }

  // Write the decoded characters into the client's buffer, starting at the offset, or zero if the buffer is empty.
  decoded.get(client.buffer, if (offset > -1) offset else 0, length)

  // Process the decoded characters and the resulting command(s). The '$' character is doubled for special use.
  var read = 0
  var nl = client.buffer.indexOfFirst { c -> isAsciiNewline(c) }
  while (nl > -1) {
    var write = 0
    val temp = CharArray(MAX_INPUT_LENGTH)
    while (read < nl && write < temp.size - if (client.buffer[read] != '$') 0 else 1) {
      when {
        isAsciiPrintable(client.buffer[read]) -> {
          temp[write] = client.buffer[read]

          if (temp[write++] == '$') {
            temp[write++] = '$'
          }
        }
        isAsciiBackspaceOrDelete(client.buffer[read]) && write > 0 -> {
          if (temp[--write] == '$') {
            --write
          }
        }
      }

      read++
    }

    // Convert the temp character array into a string by taking the slice of characters that were written to it.
    val input = String(temp.sliceArray(0 until write))

    // Check if the input was truncated because we didn't process all the way to the first newline characters.
    if (read < nl && write == temp.lastIndex) {
      if (writeToClient(client, "Line too long.  Truncated to:\n\r${input}\n\r") < 0) {
        return -1
      }
    }

    // Update the client's last input text if we aren't repeating it.
    if (!input.startsWith('!')) {
      client.lastInput = input
    }

    // Enqueue the client's last input text into its input queue.
    client.input.add(client.lastInput)

    // Read past any remaining newline character(s) in the client's buffer.
    while (read < client.buffer.size && isAsciiNewline(client.buffer[read])) {
      read++
    }

    // Check for the next newline character. If one is found, there is additional input to process.
    nl = -1
    while (read < client.buffer.size && client.buffer[read] != ASCII_NULL && nl < 0) {
      if (isAsciiNewline(client.buffer[++read])) {
        nl = read
      }
    }
  }

  // Move any remaining characters in the client's buffer to the front.
  var i = 0
  while (read + i < client.buffer.size && client.buffer[i] != ASCII_NULL) {
    client.buffer[i++] = client.buffer[read + i]
  }

  // Mark the end of the client's buffer with an ASCII null character, unless the buffer is already full.
  if (i < client.buffer.lastIndex) {
    client.buffer[i] = ASCII_NULL
  }

  return totalBytesRead
}

fun processOutput(client: Client): Int {
  var bytesWritten = 0

  val preCRLF = client.hasPrompt && client.state == ConnectionState.PLAYING
  val postCRLF = client.state == ConnectionState.PLAYING

  val capacity = client.socket.socket().sendBufferSize - if (preCRLF) 2 else 0 - if (postCRLF) 2 else 0
  val output: StringBuilder = StringBuilder(capacity)

  if (preCRLF) {
    output.append("\n\r")
  }

  while (client.output.isNotEmpty()) {
    val text = client.output.remove()

    if (text.length > output.capacity()) {
      return -1
    }

    if (text.length + output.length > output.capacity()) {
      bytesWritten += writeToClient(client, output.toString())

      if (bytesWritten < 0) {
        return -1
      }

      output.clear()
    }

    output.append(text)
  }

  if (postCRLF) {
    output.append("\n\r")
  }

  bytesWritten += writeToClient(client, output.toString())

  return bytesWritten
}

fun writeToClient(client: Client, message: String): Int {
  if (message.isEmpty()) return 0

  val writeBuffer: ByteBuffer = ByteBuffer.wrap(message.toByteArray())
  var totalBytesWritten = 0
  do {
    val bytesWritten = try {
      client.socket.write(writeBuffer)
    } catch (e: Throwable) {
      Log.error { "Error writing to client [ ${client.addr} ]." }
      Log.debug { e.printStackTrace() }
      return -1
    }

    if (bytesWritten > 0) {
      totalBytesWritten += bytesWritten
    }
  } while (bytesWritten > 0 && writeBuffer.remaining() > 0)

  if (writeBuffer.remaining() > 0) {
    client.output.add(message.slice(totalBytesWritten..message.lastIndex))
  }

  return totalBytesWritten
}

fun heartbeat(pulse: Int) {
  if (pulse % (1 * PULSES_PER_SECOND) == 0) {
    Log.debug { "Seconds passed: ${pulse / (1 * PULSES_PER_SECOND)}" }
  }
}

fun makePrompt(client: Client): String {
  val prompt: StringBuilder = StringBuilder()

  prompt.append(client.ch.name + "> ")

  return prompt.toString()
}
