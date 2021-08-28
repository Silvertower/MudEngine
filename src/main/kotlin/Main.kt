package net.roninmud.mudengine

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.output.CliktHelpFormatter
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import mu.KotlinLogging
import kotlin.system.exitProcess

val Log = KotlinLogging.logger {}

const val DEFAULT_PORT: Int = 5_000

fun main(args: Array<String>) = MudEngine().main(args)

class MudEngine : CliktCommand(name = "MudEngine", help = "A Multi-User Dungeon engine, for a more civilized age.") {
  private val port: Int by option("-p", "--port", help = "Run the game on the specified port.").int()
    .default(DEFAULT_PORT)

  init {
    context { helpFormatter = CliktHelpFormatter(showDefaultValues = true) }
  }

  override fun run() {
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
      Log.error { e.printStackTrace() }
      exitProcess(1)
    }

    runGame(port)

    exitProcess(0)
  }
}
