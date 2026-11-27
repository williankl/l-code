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
   * Where it works, absolute: the current folder of the program holding the terminal, else of the
   * shell; or, while the agent holding it says it works in another checkout, that folder.
   */
  val workingDirectory: String,
  /** Top-level folder of the git checkout, or null outside a repository. */
  val repositoryRoot: String?,
  val branch: String?,
  val parentId: SessionId?,
  /**
   * Where it was started relative to its parent. Nothing reads it any more: the header tells from
   * [mainCheckout] and where the parent runs. It's still saved, for older builds' headers.
   */
  val placement: SessionPlacement,
  val origin: SessionOrigin,
  val status: SessionStatus,
  /** The name the user gave it, kept as the shell moves; null shows [name]. */
  val customName: String? = null,
  /** The agent running in it, e.g. `claude`; null while no agent reports from it. */
  val agent: String? = null,
  /**
   * The current folder of the program holding the terminal, as the system sees it, while the agent
   * in it works in another checkout ([workingDirectory]); null otherwise. Claude Code carries a `cd`
   * forward without moving, and keeps its conversation by the folder it started in, so the session
   * starts again here.
   */
  val processDirectory: String? = null,
  /** Top-level folder of the git checkout [processDirectory] is in, while there is one. */
  val processRepositoryRoot: String? = null,
  /** The main checkout of the repository, while [repositoryRoot] is a linked worktree of it; null otherwise. */
  val mainCheckout: String? = null,
  /**
   * The folders every agent running in it says it works in, the one holding the terminal or not:
   * a suspended Claude Code carries on there after `fg`. For an archived session, as when it was
   * archived. Only keeps a worktree from being offered for deletion; [workingDirectory] is where
   * the session shows it works.
   */
  val agentDirectories: List<String> = emptyList(),
) {
  /** What the session is called on screen. */
  public val displayName: String get() = customName ?: name

  /** The folder its programs run in: [processDirectory], else [workingDirectory]. */
  public val runsIn: String get() = processDirectory ?: workingDirectory

  /** Top-level folder of the checkout its programs run in, or null outside git. */
  public val runsInRepository: String? get() = if (processDirectory != null) processRepositoryRoot else repositoryRoot
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
