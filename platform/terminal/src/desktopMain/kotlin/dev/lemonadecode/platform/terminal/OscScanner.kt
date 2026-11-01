package dev.lemonadecode.platform.terminal

/**
 * Picks the OSC sequences Lemonade Code reads out of the terminal's output as it streams past,
 * across read boundaries: program status (OSC 7501, see [ProgramStatus]) and the shell's current
 * folder (OSC 7, and OSC 9;9 as Windows shells send it). The status probe (`7501;?`) is answered
 * through [reply]; reports go to [report], folders to [folder]. The text itself still goes on to
 * the emulator untouched.
 */
internal class OscScanner(
  private val reply: (String) -> Unit,
  private val report: (ProgramStatus) -> Unit,
  private val folder: (String) -> Unit = {},
  private val windows: Boolean = false,
) {
  private enum class State { Text, Escape, Osc, OscEscape }

  private var state = State.Text
  private val body = StringBuilder()

  fun scan(chars: CharArray, offset: Int, length: Int) {
    for (index in offset until offset + length) step(chars[index])
  }

  private fun step(char: Char) {
    state = when (state) {
      State.Text -> if (char == Esc) State.Escape else State.Text
      State.Escape -> when (char) {
        ']' -> startOsc()
        Esc -> State.Escape
        else -> State.Text
      }
      State.Osc -> when {
        char == Bel -> finish(terminator = Bel.toString())
        char == Esc -> State.OscEscape
        // Another OSC (a title, a link) or an oversized one: not ours, stop collecting.
        body.length >= MaxBody || !couldBeOurs(char) -> State.Text
        else -> {
          body.append(char)
          State.Osc
        }
      }
      State.OscEscape -> when (char) {
        '\\' -> finish(terminator = "$Esc\\")
        ']' -> startOsc()
        else -> State.Text
      }
    }
  }

  /** Whether the body, with [next] added, still starts like one of the sequences read here. */
  private fun couldBeOurs(next: Char): Boolean = Prefixes.any { prefix ->
    if (body.length >= prefix.length) body.startsWith(prefix) else prefix.startsWith(body) && prefix[body.length] == next
  }

  private fun startOsc(): State {
    body.setLength(0)
    return State.Osc
  }

  private fun finish(terminator: String): State {
    when {
      body.startsWith(StatusPrefix) -> {
        val data = body.substring(StatusPrefix.length)
        if (data.startsWith("?")) reply("$Esc]$StatusCode;?$terminator") else parseProgramStatus(data)?.let(report)
      }
      body.startsWith(FolderPrefix) -> folderFromOsc7(body.substring(FolderPrefix.length), windows)?.let(folder)
      body.startsWith(WindowsFolderPrefix) -> folderFromOsc9(body.substring(WindowsFolderPrefix.length))?.let(folder)
    }
    return State.Text
  }

  private companion object {
    const val Esc = '\u001b'
    const val Bel = '\u0007'
    const val StatusCode = "7501"
    const val StatusPrefix = "$StatusCode;"
    const val FolderPrefix = "7;"
    const val WindowsFolderPrefix = "9;9;"
    val Prefixes = listOf(StatusPrefix, FolderPrefix, WindowsFolderPrefix)

    // Reports carry a base64 title and message; anything longer isn't one.
    const val MaxBody = 8_192
  }
}
