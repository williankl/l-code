package dev.lemonadecode.sessions.fake

import dev.lemonadecode.domain.session.Session
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionOrigin
import dev.lemonadecode.domain.session.SessionPlacement
import dev.lemonadecode.domain.session.SessionStatus
import dev.lemonadecode.domain.session.SubSessionLocation
import dev.lemonadecode.domain.session.SubSessionPlan
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.sessions.api.SessionError
import dev.lemonadecode.sessions.api.SessionService
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
  public var openResult: LemonadeCodeResult<SessionId, SessionError>? = null

  /** Sessions [restore] brings back; recorded as restored once it runs. */
  public var saved: List<Session> = emptyList()
  public var restoreCount: Int = 0
  public var shutDown: Boolean = false
  public var nextId: Int = 1

  public data class StartCall(
    val parentId: SessionId,
    val location: SubSessionLocation,
    val initialInput: String?,
    val origin: SessionOrigin,
    val namePrefix: String,
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

  override fun planSubSession(parentId: SessionId, location: SubSessionLocation, namePrefix: String): SubSessionPlan? {
    val parent = state.value.firstOrNull { it.id == parentId } ?: return null
    val name = generateSequence(1) { it + 1 }.map { "$namePrefix-$it" }.first { n -> state.value.none { it.name == n } }
    val worktree = location == SubSessionLocation.NewWorktree
    return SubSessionPlan(
      name = name,
      location = location,
      workingDirectory = if (worktree) "${parent.repositoryRoot}.worktrees/$name" else parent.workingDirectory,
      branch = if (worktree) "${parent.branch}-$name" else parent.branch,
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
  ): LemonadeCodeResult<SessionId, SessionError> {
    started += StartCall(parentId, location, initialInput, origin, namePrefix)
    val parent = state.value.firstOrNull { it.id == parentId } ?: return LemonadeCodeResult.failure(SessionError.UnknownSession)
    val plan = planSubSession(parentId, location, namePrefix) ?: return LemonadeCodeResult.failure(SessionError.NotARepository)
    val id = SessionId("sub-${nextId++}")
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

  override fun paste(id: SessionId, text: String): LemonadeCodeResult<Unit, SessionError> {
    pasted += id to text
    return LemonadeCodeResult.success(Unit)
  }

  override fun close(id: SessionId) {
    closed += id
    state.update { list ->
      // Sub-sessions always come after their parent, so one pass finds every level.
      val doomed = mutableSetOf(id)
      list.forEach { if (it.parentId in doomed) doomed += it.id }
      list.filterNot { it.id in doomed }
    }
  }

  override fun rename(id: SessionId, name: String?) {
    val custom = name?.trim()?.takeIf { it.isNotEmpty() }
    renamed += id to custom
    state.update { list -> list.map { if (it.id == id) it.copy(customName = custom) else it } }
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
