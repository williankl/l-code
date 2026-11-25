package dev.lemonadecode.sessions.impl

import dev.lemonadecode.domain.session.ClaudeConversation
import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.platform.process.HomeDirectory
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Finds an agent running in a terminal that can be picked up again later, and how. */
internal fun interface AgentLocator {
  /** The command that resumes the agent running under the shell [shellPid], or null when none is. */
  fun resumeCommand(shellPid: Long): String?
}

/**
 * Claude Code keeps a file per running interactive session, `~/.claude/sessions/<pid>.json`, with
 * the conversation it has open. A session whose process descends from the shell runs in that
 * terminal. Claude only writes a conversation's transcript once something is said in it, so one
 * still empty has nothing to resume: plain `claude` starts it afresh instead.
 */
@ContributesBinding(AppScope::class)
@Inject
internal class ClaudeAgentLocator(home: HomeDirectory) : AgentLocator {
  private val config = File(ClaudeConversation.configFolder(home.path, System.getenv("CLAUDE_CONFIG_DIR")))
  private val folder = File(config, "sessions")
  private val json = Json { ignoreUnknownKeys = true }

  override fun resumeCommand(shellPid: Long): String? {
    val running = folder.listFiles { file -> file.name.endsWith(".json") }.orEmpty()
      .mapNotNull { file -> file.name.removeSuffix(".json").toLongOrNull()?.let { pid -> pid to file } }
      .filter { (pid, _) -> descendsFrom(pid, shellPid) }
      .mapNotNull { (_, file) -> runCatching { json.decodeFromString(ClaudeSessionFile.serializer(), file.readText()) }.getOrNull() }
      .filter { it.kind == null || it.kind == "interactive" }
      .filter { ClaudeConversation.isId(it.sessionId) }
    val newest = running.maxByOrNull { it.startedAt ?: 0L } ?: return null
    return if (hasTranscript(newest)) ClaudeConversation.resumeCommand(newest.sessionId) else "claude"
  }

  private fun hasTranscript(session: ClaudeSessionFile): Boolean {
    val cwd = session.cwd ?: return false
    return File(config, ClaudeConversation.transcript(session.sessionId, cwd)).isFile
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
    val startedAt: Long? = null,
  )

  private companion object {
    const val MaxDepth = 8
  }
}
