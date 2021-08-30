package net.roninmud.mudengine

enum class GameMode {
  SHUTDOWN, RUNNING, REBOOT, HOTBOOT
}

class GameInfo {
  var mode: GameMode = GameMode.SHUTDOWN
}
