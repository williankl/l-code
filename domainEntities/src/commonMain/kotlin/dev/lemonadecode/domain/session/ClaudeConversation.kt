package dev.lemonadecode.domain.session

/**
 * Claude Code's conversations, as Lemonade Code picks them up again: each has a session id, a UUID,
 * and `claude --resume <id>` in the folder it ran in opens it, once Claude has written its
 * transcript.
 */
public object ClaudeConversation {
  /**
   * Whether [id] is a Claude Code session id. Only those go into a command, so nothing the shell
   * would read as code, or Claude as an option like `--dangerously-skip-permissions`, gets there.
   */
  public fun isId(id: String): Boolean = Id.matches(id)

  /** The command that resumes conversation [id]; null when [id] isn't a session id. */
  public fun resumeCommand(id: String): String? = if (isId(id)) "claude --resume $id" else null

  /** Claude Code's own folder: `CLAUDE_CONFIG_DIR` when set, else `.claude` in [home]. */
  public fun configFolder(home: String, configDir: String?): String = configDir?.takeIf { it.isNotEmpty() } ?: "$home/.claude"

  /**
   * Where Claude keeps conversation [id] that ran in [folder], within its [configFolder]: a project
   * folder named after the path, every character but letters and digits made a dash.
   */
  public fun transcript(id: String, folder: String): String = "projects/${folder.replace(NotAlphanumeric, "-")}/$id.jsonl"

  private val Id = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
  private val NotAlphanumeric = Regex("[^A-Za-z0-9]")
}
