package dev.lemonadecode.sessions.api

import dev.lemonadecode.domain.session.ArchivedSession
import dev.lemonadecode.domain.session.LinkedWorktree
import dev.lemonadecode.domain.session.Session
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionOrigin
import dev.lemonadecode.domain.session.SessionPlacement
import dev.lemonadecode.domain.session.SubSessionLocation
import dev.lemonadecode.domain.session.SubSessionPlan
import dev.lemonadecode.platform.core.LemonadeCodeResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Every open terminal, in display order: each sub-session sits right after its parent's
 * descendants. A session's folder, repository and branch follow the shell as it `cd`s. Archived
 * sessions aren't among them.
 */
public interface SessionService {
  public val sessions: StateFlow<List<Session>>

  /** Opens a new terminal in [directory], or in the home folder when null. */
  public suspend fun openTerminal(directory: String? = null): LemonadeCodeResult<SessionId, SessionError>

  /**
   * What [startSubSession] would create, for showing before anything happens. Null if it can't.
   * A new worktree goes on [branch] when given, in a folder named after its last part, and the
   * sub-session is named after that folder; `-2`, `-3`… keep it apart from a folder already there
   * and from its siblings' names.
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

  /**
   * Opens a session brought over from another tool, the way a saved one comes back after a restart:
   * a fresh shell in its folder, under its parent when given, and its Claude Code conversation
   * resumed. Fails with [SessionError.FolderNotFound] when the folder is gone.
   */
  public suspend fun openImported(session: ImportedSession): LemonadeCodeResult<SessionId, SessionError>

  /** Pastes [text] into the session's terminal, as a paste: nothing runs until the user says so. */
  public fun paste(id: SessionId, text: String): LemonadeCodeResult<Unit, SessionError>

  /** Stops the session and all of its sub-sessions. An archived one, with those archived with it, is let go of for good. */
  public fun close(id: SessionId)

  /** Sessions put away with [archive], the most recently archived first. Nothing runs in them. */
  public val archived: StateFlow<List<ArchivedSession>>

  /**
   * Puts the session and its sub-sessions away: their terminals end, and whatever runs in them, but
   * they're kept, across launches too, for [unarchive] to bring back. No worktree or branch is touched.
   * A sub-session still starting then isn't created: [startSubSession] fails with
   * [SessionError.ParentArchived].
   */
  public fun archive(id: SessionId)

  /**
   * Brings an archived session and its sub-sessions back where they were, each in a fresh shell in its
   * folder with the output it showed, and an agent that was running resumed, as at launch. A session
   * whose folder has gone since starts at home instead, without its agent, and says so. Each leaves
   * the archive only as it comes back: a sub-session that can't stays archived, with those under it,
   * and is named in [Unarchived.stillArchived]. If the first can't, nothing changes; that's
   * [SessionError.ParentArchived] when the session it goes under was archived as it started.
   */
  public suspend fun unarchive(id: SessionId): LemonadeCodeResult<Unarchived, SessionError>

  /**
   * The linked git worktrees the session is in, each once, with whether it has changes now: the one
   * its folder is in, and the one its programs run in when an agent works elsewhere. None for a
   * repository's main checkout, outside git, or when git can't tell. Archived sessions have theirs too.
   */
  public suspend fun worktreesOf(id: SessionId): List<LinkedWorktree>

  /**
   * Deletes [worktree]'s folder with `git worktree remove`; its branch stays. Without [force] git
   * refuses while it has changes; with it they are lost. Close the sessions in it first: this waits
   * a moment for their shells to end, so none is left in the folder.
   */
  public suspend fun removeWorktree(worktree: LinkedWorktree, force: Boolean): LemonadeCodeResult<Unit, SessionError>

  /**
   * Gives the session a name of the user's own, kept as the shell moves and across launches.
   * Null or blank goes back to the name Lemonade Code gives it.
   */
  public fun rename(id: SessionId, name: String?)

  /**
   * Moves the session, with its sub-sessions, to [index] among its siblings: the sessions under the
   * same parent, or the top-level ones, counted as they'll be once it has moved. It stays under its
   * parent. The order is kept across launches.
   */
  public fun move(id: SessionId, index: Int)

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

/**
 * An archived session back in the tree as [id]. [missingFolders] are the folders that had gone, by
 * the session that was in them; those sessions started at home instead. [stillArchived] are the
 * sub-sessions that couldn't come back, their shell failing or their parent archived again, and
 * those under them: they stay archived.
 */
public data class Unarchived(
  val id: SessionId,
  val missingFolders: Map<SessionId, String> = emptyMap(),
  val stillArchived: List<SessionId> = emptyList(),
)

/** A session from another tool, to open with [SessionService.openImported]. */
public data class ImportedSession(
  /** Absolute. */
  val directory: String,
  /** What it was called there, for a sub-session's name. */
  val name: String,
  /** The name the user gave it there, kept as its own; null for none. */
  val customName: String? = null,
  /** The session it goes under; null, or one that's gone, puts it at the top. */
  val parentId: SessionId? = null,
  /** Where it runs relative to its parent: [SessionPlacement.Worktree] for a worktree of the parent's repository. */
  val placement: SessionPlacement = SessionPlacement.SameFolder,
  /** The Claude Code conversation to resume, by its session id; null opens a plain shell. */
  val claudeConversation: String? = null,
)

public sealed interface SessionError {
  public data class FolderNotFound(val path: String) : SessionError

  public data object UnknownSession : SessionError

  public data object NotARepository : SessionError

  public data class WorktreeFailed(val message: String) : SessionError

  /** Git wouldn't remove the worktree at [path]. */
  public data class WorktreeNotRemoved(val path: String, val message: String) : SessionError

  /** Git already has a branch called [branch], so a new worktree can't be created on it. */
  public data class BranchExists(val branch: String) : SessionError

  /** Git won't take [branch] as a branch name. */
  public data class InvalidBranch(val branch: String) : SessionError

  public data class LaunchFailed(val message: String) : SessionError

  /**
   * The session it goes under was archived while its shell started, so it didn't join the open
   * sessions: a new sub-session wasn't created, and one coming back from the archive stayed there.
   */
  public data object ParentArchived : SessionError
}
