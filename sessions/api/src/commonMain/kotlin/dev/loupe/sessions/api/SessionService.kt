package dev.loupe.sessions.api

import dev.loupe.domain.session.Session
import dev.loupe.domain.session.SessionId
import dev.loupe.domain.session.SessionOrigin
import dev.loupe.domain.session.SubSessionLocation
import dev.loupe.domain.session.SubSessionPlan
import dev.loupe.platform.core.LoupeResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Every open terminal, in display order: each sub-session sits right after its parent's
 * descendants. A session's folder, repository and branch follow the shell as it `cd`s.
 */
public interface SessionService {
  public val sessions: StateFlow<List<Session>>

  /** Opens a new terminal in [directory], or in the home folder when null. */
  public suspend fun openTerminal(directory: String? = null): LoupeResult<SessionId, SessionError>

  /** What [startSubSession] would create, for showing before anything happens. Null if it can't. */
  public fun planSubSession(
    parentId: SessionId,
    location: SubSessionLocation,
    namePrefix: String = SubSessionPrefix,
  ): SubSessionPlan?

  /**
   * Opens a terminal in the parent's folder, or in a new worktree of its repository. When
   * [initialInput] is given it is pasted once the shell is ready, without pressing Enter.
   */
  public suspend fun startSubSession(
    parentId: SessionId,
    location: SubSessionLocation,
    initialInput: String? = null,
    origin: SessionOrigin = SessionOrigin.Split,
    namePrefix: String = SubSessionPrefix,
  ): LoupeResult<SessionId, SessionError>

  /** Pastes [text] into the session's terminal, as a paste: nothing runs until the user says so. */
  public fun paste(id: SessionId, text: String): LoupeResult<Unit, SessionError>

  /** Stops the session and all of its sub-sessions. */
  public fun close(id: SessionId)

  /**
   * Brings back the sessions open when Loupe last quit, each in a fresh shell in the folder it was
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

  public data class LaunchFailed(val message: String) : SessionError
}
