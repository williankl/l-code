package dev.lemonadecode.sessions.impl

import dev.lemonadecode.domain.session.SessionStatus

/**
 * A session's status, from what the agent running in it reports about itself and its background
 * agents (OSC 7501, see `ProgramStatus`). Nothing is guessed from output: a plain shell reports
 * nothing and stays [SessionStatus.Shell].
 *
 * With reports in: anything waiting on you means needs input, else anything working means working
 * (so a background agent keeps the session working while the main one sits at its prompt), else idle.
 */
internal class AgentStatusTracker {
  var status: SessionStatus = SessionStatus.Shell
    private set

  /** State by reporter: [Main] for the agent itself, an id for each of its background agents. */
  private val reports = mutableMapOf<String, String>()
  private var exited = false

  val hasReports: Boolean get() = reports.isNotEmpty()

  /** Returns true when [status] changed. */
  @IgnorableReturnValue
  fun onReport(id: String?, state: String): Boolean = change {
    when {
      state == Clear && id == null -> reports.clear()
      state == Clear -> reports.remove(id)
      else -> reports[id ?: Main] = state
    }
  }

  /** The shell has the terminal back, so whatever the agent left behind is stale. */
  @IgnorableReturnValue
  fun onProgramGone(): Boolean = change { reports.clear() }

  @IgnorableReturnValue
  fun onExited(): Boolean = change { exited = true }

  private inline fun change(block: () -> Unit): Boolean {
    if (exited) return false
    val before = status
    block()
    status = derive()
    return status != before
  }

  private fun derive(): SessionStatus = when {
    exited -> SessionStatus.Exited
    reports.isEmpty() -> SessionStatus.Shell
    reports.values.any { it in WaitingOnYou } -> SessionStatus.NeedsInput
    reports.values.any { it == Working } -> SessionStatus.Working
    else -> SessionStatus.Idle
  }

  private companion object {
    const val Main = ""
    const val Clear = "clear"
    const val Working = "working"

    // "error": the agent's last turn failed and it waits for you to retry or change course.
    val WaitingOnYou = setOf("blocked", "error")
  }
}
