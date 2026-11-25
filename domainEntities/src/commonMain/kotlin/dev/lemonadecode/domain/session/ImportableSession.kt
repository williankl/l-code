package dev.lemonadecode.domain.session

/** Another tool Lemonade Code can bring sessions over from, like herdr. */
public data class ImportSource(
  /** Stable, e.g. `herdr`. */
  val id: String,
  /** As the tool calls itself, e.g. `herdr`. */
  val name: String,
)

/**
 * A session another tool has, as Lemonade Code would open it: a copy, the tool keeps its own. Parents
 * come before their sub-sessions.
 */
public data class ImportableSession(
  /** Stable from one look to the next and unique within its tool: how an import is remembered. */
  val key: String,
  /** What the tool shows it as: the name given there, else its folder's. */
  val name: String,
  /** The name the user gave it in the tool, kept as the session's own; null when it had none. */
  val customName: String?,
  /** Where it opens, absolute. */
  val folder: String,
  /** [key] of the session it sits under in the tool; null at the top. */
  val parentKey: String? = null,
  /** It's in a linked git worktree of its parent's repository. */
  val worktree: Boolean = false,
  /** The Claude Code conversation to pick up, by its session id; null when there's none to resume. */
  val conversation: String? = null,
  /** Why it can't be brought over; null when it can. */
  val blocked: ImportBlock? = null,
)

/** Why a session can't be imported. */
public enum class ImportBlock {
  /** It was imported before; importing it again would make a second copy. */
  AlreadyImported,

  /** Its folder doesn't exist any more. */
  FolderMissing,
}

/** What one tool has, or why that couldn't be read. */
public sealed interface SourceScan {
  public val source: ImportSource

  /**
   * [running]: the tool still has them open, so a conversation could end up open in both.
   * [skipped]: how many of the tool's saved layouts couldn't be read, so their sessions aren't listed.
   */
  public data class Found(
    override val source: ImportSource,
    val sessions: List<ImportableSession>,
    val running: Boolean = false,
    val skipped: Int = 0,
  ) : SourceScan

  public data class NotInstalled(override val source: ImportSource) : SourceScan

  /** The tool keeps its sessions in a way Lemonade Code doesn't know, e.g. a newer version's. */
  public data class UnknownFormat(override val source: ImportSource) : SourceScan

  /** [message] is what the tool or the system said, if anything. */
  public data class Failed(override val source: ImportSource, val message: String?) : SourceScan
}

/** What an import did: the sessions it opened, in order, and those it couldn't. */
public data class ImportResult(
  val opened: List<SessionId>,
  val failed: List<ImportableSession> = emptyList(),
)
