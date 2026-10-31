package dev.loupe.platform.terminal

/**
 * What a program in the terminal says it is doing, sent over OSC 7501 ("program status"). Claude
 * Code sends one for itself and one per background agent, once the terminal answers its probe.
 *
 * [id] is null for the program itself and set for each of its agents. [state] is the program's own
 * word (`working`, `blocked`, `idle`, `done`, `error`); `clear` withdraws a report, or all of them
 * when [id] is null. [kind] says what a blocked program waits for (`permission`, `question`, `auth`).
 */
public data class ProgramStatus(
  val id: String?,
  val state: String,
  val kind: String? = null,
  val app: String? = null,
)

/**
 * Picks OSC 7501 sequences out of the terminal's output as it streams past, across read
 * boundaries. The probe (`7501;?`) is answered through [reply]; reports go to [report]. The text
 * itself still goes on to the emulator untouched.
 */
internal class ProgramStatusScanner(
  private val reply: (String) -> Unit,
  private val report: (ProgramStatus) -> Unit,
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
        body.length >= MaxBody || (body.length < Prefix.length && char != Prefix[body.length]) -> State.Text
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

  private fun startOsc(): State {
    body.setLength(0)
    return State.Osc
  }

  private fun finish(terminator: String): State {
    if (body.startsWith(Prefix)) {
      val data = body.substring(Prefix.length)
      if (data.startsWith("?")) reply("$Esc]$Code;?$terminator") else parse(data)?.let(report)
    }
    return State.Text
  }

  private fun parse(data: String): ProgramStatus? {
    val fields = data.split(':').mapNotNull { field ->
      val equals = field.indexOf('=')
      if (equals > 0) field.substring(0, equals) to field.substring(equals + 1) else null
    }.toMap()
    val state = fields["state"]?.takeIf { it.isNotEmpty() } ?: return null
    return ProgramStatus(
      id = fields["id"]?.takeIf { it.isNotEmpty() },
      state = state,
      kind = fields["kind"]?.takeIf { it.isNotEmpty() },
      app = fields["app"]?.takeIf { it.isNotEmpty() },
    )
  }

  private companion object {
    const val Esc = '\u001b'
    const val Bel = '\u0007'
    const val Code = "7501"
    const val Prefix = "$Code;"

    // Reports carry a base64 title and message; anything longer isn't one.
    const val MaxBody = 8_192
  }
}
