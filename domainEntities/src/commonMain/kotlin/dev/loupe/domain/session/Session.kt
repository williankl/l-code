package dev.loupe.domain.session

import kotlin.jvm.JvmInline

@JvmInline
public value class SessionId(public val value: String)

/** A terminal: the user's login shell in a pseudo-terminal, following wherever they `cd`. */
public data class Session(
  val id: SessionId,
  val name: String,
  /** Short name of the shell, e.g. `zsh`. */
  val shell: String,
  /** The shell's current folder, absolute. */
  val workingDirectory: String,
  /** Top-level folder of the git checkout, or null outside a repository. */
  val repositoryRoot: String?,
  val branch: String?,
  val parentId: SessionId?,
  val placement: SessionPlacement,
  val origin: SessionOrigin,
  val status: SessionStatus,
)

/** Where a session runs relative to its parent. */
public enum class SessionPlacement { Root, SameFolder, Worktree }

public sealed interface SessionOrigin {
  public data object Opened : SessionOrigin

  /** Started from the split button. */
  public data object Split : SessionOrigin

  /** Started with review comments pasted in, ready to be addressed. */
  public data class Review(val commentCount: Int) : SessionOrigin
}

/**
 * What a session is doing, as the agent running in it reports. [Shell] is a plain terminal with no
 * agent reporting: nothing is shown for it. [Exited] means the shell itself ended.
 */
public enum class SessionStatus { Shell, Idle, Working, NeedsInput, Exited }
