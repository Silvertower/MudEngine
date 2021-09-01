package net.roninmud.mudengine

import net.roninmud.mudengine.utility.*

import java.net.BindException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
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

      if (client.ch.wait > 0 || client.inputQueue.isEmpty()) continue

      val input = client.inputQueue.remove()

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
      if (!writeSet.contains(client) || client.outputQueue.isEmpty()) continue

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
  var totalBytesRead = 0

  do {
    val bytesRead = try {
      client.socket.read(client.inputBuffer)
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

  // Flip the client's input buffer for reading.
  client.inputBuffer.flip()

  // Setup an ASCII decoder that ignores malformed or unmappable characters.
  val decoder = StandardCharsets.US_ASCII
    .newDecoder()
    .onMalformedInput(CodingErrorAction.IGNORE)
    .onUnmappableCharacter(CodingErrorAction.IGNORE)

  // Decode the client's input buffer into ASCII.
  val buffer = decoder.decode(client.inputBuffer)

  // Clear the client's input buffer so it's empty for next time.
  client.inputBuffer.clear()

  // Process the decoded characters and the resulting command(s). The '$' character is doubled for special use.
  var read = 0
  var nl = buffer.indexOfFirst { c -> isAsciiNewline(c) }
  while (nl > -1) {
    var write = 0
    val temp = CharArray(MAX_INPUT_LENGTH)
    while (read < nl && write < temp.size - if (buffer[read] != '$') 0 else 1) {
      when {
        isAsciiPrintable(buffer[read]) -> {
          temp[write] = buffer[read]

          if (temp[write++] == '$') {
            temp[write++] = '$'
          }
        }
        isAsciiBackspaceOrDelete(buffer[read]) && write > 0 -> {
          if (temp[--write] == '$') {
            --write
          }
        }
      }

      read++
    }

    // Convert the temp character array into a string by taking the slice of characters that were written to it.
    val input = String(temp.sliceArray(0 until write))

    // Check if the input was truncated because we didn't process all the way to the first newline character.
    if (read < nl && write == temp.lastIndex) {
      client.outputQueue.add("Line too long.  Truncated to:\n\r${input}\n\r")
    }

    // Update the client's last input text if we aren't repeating it.
    if (!input.startsWith('!')) {
      client.lastInput = input
    }

    // Enqueue the client's last input text into its input queue.
    client.inputQueue.add(client.lastInput)

    // Read past any remaining newline character(s) in the client's buffer.
    while (read < buffer.length && isAsciiNewline(buffer[read])) {
      read++
    }

    // Check for the next newline character. If one is found, there is additional input to process.
    nl = -1
    while (read < buffer.length && buffer[read] != ASCII_NULL && nl < 0) {
      if (isAsciiNewline(buffer[++read])) {
        nl = read
      }
    }
  }

  // Compact whatever is left in the character buffer (e.g. an unfinished command).
  buffer.compact()

  // Write whatever is left in the character buffer into the client's read buffer for next time.
  try {
    client.inputBuffer.put(StandardCharsets.US_ASCII.encode(buffer))
  } catch (e: Throwable) {
    Log.error { "Error writing to the client's read buffer [ ${client.addr} ]." }
    Log.debug { e.printStackTrace() }
    return -1
  }

  return totalBytesRead
}

fun processOutput(client: Client): Int {
  var bytesWritten = 0

  val preCRLF = client.hasPrompt && client.state == ConnectionState.PLAYING
  val postCRLF = client.state == ConnectionState.PLAYING

  val capacity = client.socket.socket().sendBufferSize - if (preCRLF) CRLF.length else 0 - if (postCRLF) CRLF.length else 0
  val output: StringBuilder = StringBuilder(capacity)

  if (preCRLF) {
    output.append(CRLF)
  }

  while (client.outputQueue.isNotEmpty()) {
    val text = client.outputQueue.remove()

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
    output.append(CRLF)
  }

  bytesWritten += writeToClient(client, output.toString())

  return bytesWritten
}

fun writeToClient(client: Client, message: String): Int {
  var totalBytesWritten = 0

  if (message.isEmpty()) return 0

  try {
    client.outputBuffer.put(message.toByteArray(StandardCharsets.US_ASCII))
  } catch (e: Throwable) {
    Log.error { "Error writing to client's output buffer [ ${client.addr} ]." }
    Log.debug { e.printStackTrace() }
    return -1
  }

  client.outputBuffer.flip()

  do {
    val bytesWritten = try {
      client.socket.write(client.outputBuffer)
    } catch (e: Throwable) {
      Log.error { "Error writing to client [ ${client.addr} ]." }
      Log.debug { e.printStackTrace() }
      return -1
    }

    if (bytesWritten > 0) {
      totalBytesWritten += bytesWritten
    }
  } while (bytesWritten > 0 && client.outputBuffer.remaining() > 0)

  client.outputBuffer.compact()

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
