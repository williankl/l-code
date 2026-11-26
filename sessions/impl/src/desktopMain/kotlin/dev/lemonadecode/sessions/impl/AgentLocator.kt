package dev.lemonadecode.sessions.impl

import dev.lemonadecode.domain.session.ClaudeConversation
import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.platform.process.HomeDirectory
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Finds the agents running in a terminal that can be picked up again later, how, and where they work. */
internal fun interface AgentLocator {
  /** The agents running under the shell [shellPid], the most recently started first; none when none is. */
  fun find(shellPid: Long): List<RunningAgent>
}

/**
 * An agent running in a terminal, as process [pid], running or stopped. [resume] is the command that
 * picks it up again in the folder it started in. [workingDirectory] is the folder it says it works in
 * now, for an agent that keeps one of its own apart from its process's; null until it says, or once
 * that folder is gone.
 */
internal data class RunningAgent(val pid: Long, val resume: String, val workingDirectory: String?)

/**
 * Claude Code keeps a file per running interactive session, `~/.claude/sessions/<pid>.json`, with
 * the conversation it has open. A session whose process descends from the shell runs in that
 * terminal. Claude only writes a conversation's transcript once something is said in it, so one
 * still empty has nothing to resume: plain `claude` starts it afresh instead. Where it works now is
 * read from the end of that transcript; see [TranscriptTail].
 */
@ContributesBinding(AppScope::class)
@Inject
internal class ClaudeAgentLocator(home: HomeDirectory) : AgentLocator {
  private val config = File(ClaudeConversation.configFolder(home.path, System.getenv("CLAUDE_CONFIG_DIR")))
  private val folder = File(config, "sessions")
  private val json = Json { ignoreUnknownKeys = true }

  /** The transcript each Claude Code process, by its id, has been seen to have open; the least recently asked about go first. */
  private val transcripts = object : LinkedHashMap<Long, Transcript>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Transcript>?): Boolean = size > MaxTranscripts
  }

  override fun find(shellPid: Long): List<RunningAgent> = folder.listFiles { file -> file.name.endsWith(".json") }.orEmpty()
    .mapNotNull { file -> file.name.removeSuffix(".json").toLongOrNull()?.let { pid -> pid to file } }
    .filter { (pid, _) -> descendsFrom(pid, shellPid) }
    .mapNotNull { (pid, file) -> runCatching { pid to json.decodeFromString(ClaudeSessionFile.serializer(), file.readText()) }.getOrNull() }
    .filter { (_, session) -> session.kind == null || session.kind == "interactive" }
    .filter { (_, session) -> ClaudeConversation.isId(session.sessionId) }
    .sortedByDescending { (_, session) -> session.startedAt ?: 0L }
    .mapNotNull { (pid, session) ->
      val transcript = transcriptOf(pid, session)
      val resume = if (transcript == null) "claude" else ClaudeConversation.resumeCommand(session.sessionId)
      resume?.let { RunningAgent(pid, it, transcript?.workingDirectory()) }
    }

  /**
   * The transcript of the conversation Claude Code process [pid] has open, once there is one. Kept
   * once found, as it stays where the conversation began even if the folder the session file names
   * changes meanwhile.
   */
  private fun transcriptOf(pid: Long, session: ClaudeSessionFile): TranscriptTail? {
    val known = synchronized(transcripts) { transcripts[pid] }
    if (known != null && known.sessionId == session.sessionId && known.tail.file.isFile) return known.tail
    val cwd = session.cwd ?: return null
    val file = File(config, ClaudeConversation.transcript(session.sessionId, cwd)).takeIf { it.isFile } ?: return null
    // Without a start time, nothing in it can be told apart from what an earlier run wrote.
    val tail = TranscriptTail(file, since = session.startedAt ?: Long.MAX_VALUE)
    synchronized(transcripts) { transcripts[pid] = Transcript(session.sessionId, tail) }
    return tail
  }

  private fun descendsFrom(pid: Long, ancestor: Long): Boolean {
    var current = ProcessHandle.of(pid).orElse(null)?.takeIf { it.isAlive } ?: return false
    repeat(MaxDepth) {
      val parent = current.parent().orElse(null) ?: return false
      if (parent.pid() == ancestor) return true
      current = parent
    }
    return false
  }

  @Serializable
  private data class ClaudeSessionFile(
    val sessionId: String,
    val cwd: String? = null,
    val kind: String? = null,
    /** When the process started, in milliseconds since the epoch. */
    val startedAt: Long? = null,
  )

  private class Transcript(val sessionId: String, val tail: TranscriptTail)

  private companion object {
    const val MaxDepth = 8
    const val MaxTranscripts = 64
  }
}
