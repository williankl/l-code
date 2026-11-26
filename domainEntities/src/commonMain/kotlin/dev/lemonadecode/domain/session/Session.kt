package dev.lemonadecode.domain.session

import kotlin.jvm.JvmInline

@JvmInline
public value class SessionId(public val value: String)

/** A terminal: the user's login shell in a pseudo-terminal, following wherever they `cd`. */
public data class Session(
  val id: SessionId,
  /** The name Lemonade Code gives it; [customName], when set, is shown instead. */
  val name: String,
  /** Short name of the shell, e.g. `zsh`. */
  val shell: String,
  /**
   * Where it works, absolute: the folder an agent holding the terminal says it works in, else the
   * current folder of the program holding the terminal, else of the shell.
   */
  val workingDirectory: String,
  /** Top-level folder of the git checkout, or null outside a repository. */
  val repositoryRoot: String?,
  val branch: String?,
  val parentId: SessionId?,
  /** Where it was started relative to its parent. Where it works now can differ; see [worksInOtherCheckoutThan]. */
  val placement: SessionPlacement,
  val origin: SessionOrigin,
  val status: SessionStatus,
  /** The name the user gave it, kept as the shell moves; null shows [name]. */
  val customName: String? = null,
  /** The agent running in it, e.g. `claude`; null while no agent reports from it. */
  val agent: String? = null,
  /**
   * The current folder of the program holding the terminal, as the system sees it, while the agent
   * in it works in another one; null when that's [workingDirectory]. Claude Code carries a `cd`
   * forward without moving, and keeps its conversation by the folder it started in, so the session
   * starts again here.
   */
  val processDirectory: String? = null,
) {
  /** What the session is called on screen. */
  public val displayName: String get() = customName ?: name

  /**
   * Whether it works in another git checkout than [parent] does now: a worktree of the same
   * repository, or another repository altogether.
   */
  public fun worksInOtherCheckoutThan(parent: Session): Boolean = repositoryRoot != null && repositoryRoot != parent.repositoryRoot
}

/** Where a session was started relative to its parent. */
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
