package dev.lemonadecode.sessions.fake

import dev.lemonadecode.domain.session.ArchivedSession
import dev.lemonadecode.domain.session.LinkedWorktree
import dev.lemonadecode.domain.session.Session
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionOrder
import dev.lemonadecode.domain.session.SessionOrigin
import dev.lemonadecode.domain.session.SessionPlacement
import dev.lemonadecode.domain.session.SessionStatus
import dev.lemonadecode.domain.session.SubSessionLocation
import dev.lemonadecode.domain.session.SubSessionPlan
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.sessions.api.ImportedSession
import dev.lemonadecode.sessions.api.SessionError
import dev.lemonadecode.sessions.api.SessionService
import dev.lemonadecode.sessions.api.Unarchived
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** In-memory [SessionService]: sessions are plain values, nothing runs; calls are recorded. */
public class FakeSessionService(initial: List<Session> = emptyList()) : SessionService {
  private val state = MutableStateFlow(initial)
  override val sessions: StateFlow<List<Session>> = state

  public val opened: MutableList<String?> = mutableListOf()
  public val started: MutableList<StartCall> = mutableListOf()
  public val pasted: MutableList<Pair<SessionId, String>> = mutableListOf()
  public val closed: MutableList<SessionId> = mutableListOf()

  /** Every rename, with the name kept: trimmed, or null for back to the automatic one. */
  public val renamed: MutableList<Pair<SessionId, String?>> = mutableListOf()

  /** Every move: the session and the place among its siblings it was asked to go to. */
  public val moved: MutableList<Pair<SessionId, Int>> = mutableListOf()
  public var openResult: LemonadeCodeResult<SessionId, SessionError>? = null

  /** What [startSubSession] answers, once recorded; null starts it. */
  public var startResult: LemonadeCodeResult<SessionId, SessionError>? = null

  /** Sessions [restore] brings back; recorded as restored once it runs. */
  public var saved: List<Session> = emptyList()
  public var restoreCount: Int = 0
  public var shutDown: Boolean = false
  public var nextId: Int = 1

  /** What [suggestWorktreeBranch] answers. */
  public var worktreeBranchSuggestion: String = "worktree/testing-things"

  /** Branches git already has: starting a worktree on one fails. */
  public var existingBranches: Set<String> = emptySet()

  /**
   * Linked worktrees, which a session is in when its folder is inside one. A sub-session started in
   * a new worktree adds its own; removing one takes it away.
   */
  public var worktrees: List<LinkedWorktree> = emptyList()

  /** What [removeWorktree] answers; null removes it. */
  public var removeWorktreeResult: LemonadeCodeResult<Unit, SessionError>? = null
  public val removedWorktrees: MutableList<RemoveWorktreeCall> = mutableListOf()

  /** [stillOpen]: the sessions still in the worktree when it was asked to go. */
  public data class RemoveWorktreeCall(val worktree: LinkedWorktree, val force: Boolean, val stillOpen: List<SessionId> = emptyList())

  public data class StartCall(
    val parentId: SessionId,
    val location: SubSessionLocation,
    val initialInput: String?,
    val origin: SessionOrigin,
    val namePrefix: String,
    val branch: String? = null,
  )

  public fun setSessions(sessions: List<Session>) {
    state.value = sessions
  }

  public fun setStatus(id: SessionId, status: SessionStatus) {
    state.update { list -> list.map { if (it.id == id) it.copy(status = status) else it } }
  }

  /** Folder new terminals open in when none is given. */
  public var homeFolder: String = "/home/you"

  override suspend fun openTerminal(directory: String?): LemonadeCodeResult<SessionId, SessionError> {
    opened += directory
    openResult?.let { return it }
    val id = SessionId("terminal-${nextId++}")
    val folder = directory ?: homeFolder
    state.update {
      it + Session(
        id = id,
        name = if (directory == null) "~" else folder.substringAfterLast('/'),
        shell = "zsh",
        workingDirectory = folder,
        repositoryRoot = null,
        branch = null,
        parentId = null,
        placement = SessionPlacement.Root,
        origin = SessionOrigin.Opened,
        status = SessionStatus.Shell,
      )
    }
    return LemonadeCodeResult.success(id)
  }

  /** Every [planSubSession] call, by the session it plans under. */
  public val planned: MutableList<SessionId> = mutableListOf()

  /**
   * The name a plan for a new worktree on a typed branch gives, folder and sub-session alike, like
   * the real one's `generating-bugs-2` beside a folder already there. Null takes the branch's last
   * part: this fake keeps nothing apart itself.
   */
  public var plannedWorktreeName: String? = null

  override fun planSubSession(parentId: SessionId, location: SubSessionLocation, namePrefix: String, branch: String?): SubSessionPlan? {
    planned += parentId
    val parent = state.value.firstOrNull { it.id == parentId } ?: return null
    val worktree = location == SubSessionLocation.NewWorktree
    val name = if (worktree && branch != null) {
      plannedWorktreeName ?: branch.substringAfterLast('/')
    } else {
      generateSequence(1) { it + 1 }.map { "$namePrefix-$it" }.first { n -> state.value.none { it.name == n } }
    }
    val folder = "${parent.repositoryRoot}.worktrees"
    return SubSessionPlan(
      name = name,
      location = location,
      workingDirectory = if (worktree) "$folder/$name" else parent.workingDirectory,
      branch = if (worktree) branch ?: "${parent.branch}-$name" else parent.branch,
      createsBranch = worktree,
      baseBranch = parent.branch,
    )
  }

  override suspend fun startSubSession(
    parentId: SessionId,
    location: SubSessionLocation,
    initialInput: String?,
    origin: SessionOrigin,
    namePrefix: String,
    branch: String?,
  ): LemonadeCodeResult<SessionId, SessionError> {
    started += StartCall(parentId, location, initialInput, origin, namePrefix, branch)
    startResult?.let { return it }
    val parent = state.value.firstOrNull { it.id == parentId } ?: return LemonadeCodeResult.failure(SessionError.UnknownSession)
    val plan = planSubSession(parentId, location, namePrefix, branch) ?: return LemonadeCodeResult.failure(SessionError.NotARepository)
    if (plan.createsBranch && plan.branch in existingBranches) return LemonadeCodeResult.failure(SessionError.BranchExists(plan.branch.orEmpty()))
    val id = SessionId("sub-${nextId++}")
    if (plan.createsBranch) {
      worktrees = worktrees + LinkedWorktree(plan.workingDirectory, plan.branch, parent.repositoryRoot ?: parent.workingDirectory, hasChanges = false)
    }
    state.update {
      it + parent.copy(
        id = id,
        name = plan.name,
        parentId = parentId,
        workingDirectory = plan.workingDirectory,
        branch = plan.branch,
        placement = if (plan.createsBranch) SessionPlacement.Worktree else SessionPlacement.SameFolder,
        origin = origin,
        status = SessionStatus.Shell,
      )
    }
    return LemonadeCodeResult.success(id)
  }

  override fun suggestWorktreeBranch(): String = worktreeBranchSuggestion

  /** Every session [openImported] was asked to open. */
  public val imported: MutableList<ImportedSession> = mutableListOf()

  /**
   * Parents archived just as a session imported under them starts, as when the user archives one
   * meanwhile: [openImported] archives the parent and fails with [SessionError.ParentArchived].
   */
  public var archivedWhileImporting: Set<SessionId> = emptySet()

  /** A folder in [missingFolders] fails; one under a parent in [archivedWhileImporting] fails as that parent goes. */
  override suspend fun openImported(session: ImportedSession): LemonadeCodeResult<SessionId, SessionError> {
    imported += session
    if (session.directory in missingFolders) return LemonadeCodeResult.failure(SessionError.FolderNotFound(session.directory))
    session.parentId?.takeIf { it in archivedWhileImporting }?.let { parentId ->
      archive(parentId)
      return LemonadeCodeResult.failure(SessionError.ParentArchived)
    }
    val id = SessionId("imported-${nextId++}")
    val parent = session.parentId?.takeIf { parentId -> state.value.any { it.id == parentId } }
    val opened = Session(
      id = id,
      name = if (parent == null) session.directory.substringAfterLast('/') else session.name,
      shell = "zsh",
      workingDirectory = session.directory,
      repositoryRoot = null,
      branch = null,
      parentId = parent,
      placement = if (parent == null) SessionPlacement.Root else session.placement,
      origin = SessionOrigin.Opened,
      status = SessionStatus.Shell,
      customName = session.customName,
    )
    state.update { sessions ->
      if (parent == null) {
        sessions + opened
      } else {
        // Right after the parent's last descendant, as the real service places sub-sessions.
        val family = mutableSetOf(parent)
        sessions.forEach { if (it.parentId in family) family += it.id }
        val at = sessions.indexOfLast { it.id in family } + 1
        sessions.take(at) + opened + sessions.drop(at)
      }
    }
    return LemonadeCodeResult.success(id)
  }

  override fun paste(id: SessionId, text: String): LemonadeCodeResult<Unit, SessionError> {
    pasted += id to text
    return LemonadeCodeResult.success(Unit)
  }

  override fun close(id: SessionId) {
    closed += id
    if (archivedState.value.any { it.id == id }) {
      archivedState.update { list -> list.filterNot { it.id == id } }
      return
    }
    state.update { list -> list.filterNot { it.id in subtree(list, id) } }
  }

  /** [id] and its sub-sessions: they always come after their parent, so one pass finds every level. */
  private fun subtree(list: List<Session>, id: SessionId): Set<SessionId> {
    val found = mutableSetOf(id)
    list.forEach { if (it.parentId in found) found += it.id }
    return found
  }

  private val archivedState = MutableStateFlow<List<ArchivedSession>>(emptyList())
  override val archived: StateFlow<List<ArchivedSession>> = archivedState

  public val archivedIds: MutableList<SessionId> = mutableListOf()
  public val unarchivedIds: MutableList<SessionId> = mutableListOf()

  /**
   * Folders that are gone: [unarchive] reports them instead of bringing their sessions back there,
   * and [openImported] fails with [SessionError.FolderNotFound].
   */
  public var missingFolders: Set<String> = emptySet()

  /** What [unarchive] answers; null brings the session back. */
  public var unarchiveResult: LemonadeCodeResult<Unarchived, SessionError>? = null

  /** While set, [unarchive] waits for it before doing anything, as a slow restore would. */
  public var unarchiveGate: CompletableDeferred<Unit>? = null

  /** Takes the session and its sub-sessions out of the list, the most recently archived first, with no status. */
  override fun archive(id: SessionId) {
    archivedIds += id
    val list = state.value
    val going = subtree(list, id)
    val sessions = list.filter { it.id in going }.map { it.copy(status = SessionStatus.Shell) }
    if (sessions.isEmpty()) return
    state.value = list.filterNot { it.id in going }
    archivedState.update { listOf(ArchivedSession(sessions.first(), sessions.drop(1))) + it }
  }

  /** Sessions whose shell [unarchive] can't start: they, and those under them, stay archived where they were. */
  public var failingToStart: Set<SessionId> = emptySet()

  /** Puts the sessions back last, under the parent if it's there; a folder in [missingFolders] comes back at home. */
  override suspend fun unarchive(id: SessionId): LemonadeCodeResult<Unarchived, SessionError> {
    unarchivedIds += id
    unarchiveGate?.await()
    unarchiveResult?.let { return it }
    val archive = archivedState.value.firstOrNull { it.id == id } ?: return LemonadeCodeResult.failure(SessionError.UnknownSession)
    val stayed = mutableSetOf<SessionId>()
    archive.sessions.forEach { if (it.id in failingToStart || it.parentId in stayed) stayed += it.id }
    if (id in stayed) return LemonadeCodeResult.failure(SessionError.LaunchFailed("no shell"))
    val kept = archive.sessions.filter { it.id in stayed }
    archivedState.update { list -> list.mapNotNull { if (it !== archive) it else kept.takeIf(List<Session>::isNotEmpty)?.let { left -> ArchivedSession(left.first(), left.drop(1)) } } }
    val missing = archive.sessions.filter { it.id !in stayed && it.workingDirectory in missingFolders }.associate { it.id to it.workingDirectory }
    val back = archive.sessions.filterNot { it.id in stayed }.map { session ->
      val moved = if (session.id in missing) session.copy(workingDirectory = homeFolder) else session
      if (session.id == id && state.value.none { it.id == session.parentId }) moved.copy(parentId = null, placement = SessionPlacement.Root) else moved
    }
    state.update { it + back }
    return LemonadeCodeResult.success(Unarchived(id, missing, kept.map { it.id }))
  }

  override suspend fun worktreeOf(id: SessionId): LinkedWorktree? {
    val session = (state.value + archivedState.value.flatMap { it.sessions }).firstOrNull { it.id == id } ?: return null
    return worktrees.firstOrNull { it.contains(session.workingDirectory) }
  }

  override suspend fun removeWorktree(worktree: LinkedWorktree, force: Boolean): LemonadeCodeResult<Unit, SessionError> {
    removedWorktrees += RemoveWorktreeCall(worktree, force, state.value.filter { worktree.contains(it.workingDirectory) }.map { it.id })
    removeWorktreeResult?.let { return it }
    worktrees = worktrees.filterNot { it.path == worktree.path }
    return LemonadeCodeResult.success(Unit)
  }

  override fun rename(id: SessionId, name: String?) {
    val custom = name?.trim()?.takeIf { it.isNotEmpty() }
    renamed += id to custom
    state.update { list -> list.map { if (it.id == id) it.copy(customName = custom) else it } }
  }

  override fun move(id: SessionId, index: Int) {
    moved += id to index
    state.update { SessionOrder.move(it, id, index) }
  }

  override suspend fun restore(): Int {
    restoreCount++
    state.update { it + saved }
    return saved.size
  }

  override suspend fun shutdown() {
    shutDown = true
  }
}
