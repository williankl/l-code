package dev.lemonadecode.platform.terminal

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

/** The fields of an OSC 7501 report (what follows `7501;`), or null when it names no state. */
internal fun parseProgramStatus(data: String): ProgramStatus? {
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
