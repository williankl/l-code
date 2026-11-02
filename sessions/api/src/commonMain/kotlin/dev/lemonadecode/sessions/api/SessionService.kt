package dev.lemonadecode.sessions.api

import dev.lemonadecode.domain.session.Session
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionOrigin
import dev.lemonadecode.domain.session.SubSessionLocation
import dev.lemonadecode.domain.session.SubSessionPlan
import dev.lemonadecode.platform.core.LemonadeCodeResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Every open terminal, in display order: each sub-session sits right after its parent's
 * descendants. A session's folder, repository and branch follow the shell as it `cd`s.
 */
public interface SessionService {
  public val sessions: StateFlow<List<Session>>

  /** Opens a new terminal in [directory], or in the home folder when null. */
  public suspend fun openTerminal(directory: String? = null): LemonadeCodeResult<SessionId, SessionError>

  /**
   * What [startSubSession] would create, for showing before anything happens. Null if it can't.
   * A new worktree goes on [branch] when given, in a folder named after its last part.
   */
  public fun planSubSession(
    parentId: SessionId,
    location: SubSessionLocation,
    namePrefix: String = SubSessionPrefix,
    branch: String? = null,
  ): SubSessionPlan?

  /**
   * Opens a terminal in the parent's folder, or in a new worktree of its repository, on [branch]
   * when given. When [initialInput] is given it is pasted once the shell is ready, without pressing
   * Enter.
   */
  public suspend fun startSubSession(
    parentId: SessionId,
    location: SubSessionLocation,
    initialInput: String? = null,
    origin: SessionOrigin = SessionOrigin.Split,
    namePrefix: String = SubSessionPrefix,
    branch: String? = null,
  ): LemonadeCodeResult<SessionId, SessionError>

  /** A playful branch name for a new worktree, like `worktree/generating-bugs`, to keep or type over. */
  public fun suggestWorktreeBranch(): String

  /** Pastes [text] into the session's terminal, as a paste: nothing runs until the user says so. */
  public fun paste(id: SessionId, text: String): LemonadeCodeResult<Unit, SessionError>

  /** Stops the session and all of its sub-sessions. */
  public fun close(id: SessionId)

  /**
   * Gives the session a name of the user's own, kept as the shell moves and across launches.
   * Null or blank goes back to the name Lemonade Code gives it.
   */
  public fun rename(id: SessionId, name: String?)

  /**
   * Brings back the sessions open when Lemonade Code last quit, each in a fresh shell in the folder it was
   * in, with the output it showed. An agent that was running is started again where it left off.
   * From then on the sessions are kept up to date on disk. Returns how many came back.
   */
  public suspend fun restore(): Int

  /** Saves every session for next time, then ends them all, and whatever runs in them. */
  public suspend fun shutdown()

  public companion object {
    public const val SubSessionPrefix: String = "sub"
    public const val ReviewPrefix: String = "review"
  }
}

public sealed interface SessionError {
  public data class FolderNotFound(val path: String) : SessionError

  public data object UnknownSession : SessionError

  public data object NotARepository : SessionError

  public data class WorktreeFailed(val message: String) : SessionError

  /** Git already has a branch called [branch], so a new worktree can't be created on it. */
  public data class BranchExists(val branch: String) : SessionError

  /** Git won't take [branch] as a branch name. */
  public data class InvalidBranch(val branch: String) : SessionError

  public data class LaunchFailed(val message: String) : SessionError
}
