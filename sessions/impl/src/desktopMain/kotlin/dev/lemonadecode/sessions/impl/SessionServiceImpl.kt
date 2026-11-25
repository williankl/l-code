package dev.lemonadecode.sessions.impl

import dev.lemonadecode.data.git.GitDataSource
import dev.lemonadecode.data.git.GitError
import dev.lemonadecode.domain.session.ArchivedSession
import dev.lemonadecode.domain.session.BranchName
import dev.lemonadecode.domain.session.LinkedWorktree
import dev.lemonadecode.domain.session.Session
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionOrigin
import dev.lemonadecode.domain.session.SessionPlacement
import dev.lemonadecode.domain.session.SessionStatus
import dev.lemonadecode.domain.session.SubSessionLocation
import dev.lemonadecode.domain.session.SubSessionPlan
import dev.lemonadecode.platform.architecture.LemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.core.getOrNull
import dev.lemonadecode.platform.core.mapError
import dev.lemonadecode.platform.core.safeSuspendRunCatching
import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.platform.process.HomeDirectory
import dev.lemonadecode.platform.terminal.TerminalSession
import dev.lemonadecode.platform.terminal.TerminalSignal
import dev.lemonadecode.sessions.api.SessionError
import dev.lemonadecode.sessions.api.SessionService
import dev.lemonadecode.sessions.api.Unarchived
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
internal class SessionServiceImpl(
  private val git: GitDataSource,
  private val launcher: ShellLauncher,
  private val registry: SessionRegistry,
  private val home: HomeDirectory,
  private val dispatchers: LemonadeCodeCoroutineDispatchers,
  private val scope: CoroutineScope,
  private val store: SessionStore,
  private val agents: AgentLocator,
) : SessionService {
  private val state = MutableStateFlow<List<Session>>(emptyList())
  private val mutations = Mutex()
  private val watchers = ConcurrentHashMap<SessionId, Job>()

  /** For each session with an agent running, the command that resumes it. */
  private val resumes = MutableStateFlow<Map<SessionId, String>>(emptyMap())

  /** The archived sessions as kept, the most recently archived first; [archivedSessions] shows them. */
  private val archives = MutableStateFlow<List<SavedArchive>>(emptyList())
  private val archivedSessions = MutableStateFlow<List<ArchivedSession>>(emptyList())
  private val archiveLock = Any()
  private val saver = AtomicReference<Job?>(null)
  private val shuttingDown = AtomicBoolean(false)

  /**
   * Process ids of the shells just closed and what they ran, with how long they're waited for:
   * until they end, or [EndingWait] after closing, whichever comes first.
   */
  private val ending = ConcurrentHashMap<Long, TimeSource.Monotonic.ValueTimeMark>()

  /** How long a resumed agent has to show up before it counts as gone, unless it is still running. */
  @Volatile internal var resumeGrace: Duration = 20.seconds

  override val sessions: StateFlow<List<Session>> = state.asStateFlow()

  override val archived: StateFlow<List<ArchivedSession>> = archivedSessions.asStateFlow()

  override suspend fun openTerminal(directory: String?): LemonadeCodeResult<SessionId, SessionError> {
    val folder = File(directory ?: home.path)
    if (!folder.isDirectory) return LemonadeCodeResult.failure(SessionError.FolderNotFound(folder.path))
    val checkout = git.checkout(folder.absolutePath).getOrNull()
    return launch(folder.absolutePath, initialInput = null) { id ->
      Session(
        id = id,
        name = rootName(folder.absolutePath),
        shell = launcher.shellName,
        workingDirectory = folder.absolutePath,
        repositoryRoot = checkout?.root,
        branch = checkout?.branch,
        parentId = null,
        placement = SessionPlacement.Root,
        origin = SessionOrigin.Opened,
        status = SessionStatus.Shell,
      )
    }
  }

  override fun planSubSession(parentId: SessionId, location: SubSessionLocation, namePrefix: String, branch: String?): SubSessionPlan? {
    val sessions = state.value
    val parent = sessions.firstOrNull { it.id == parentId } ?: return null
    return SubSessionPlanner.plan(parent, sessions, location, namePrefix, branch)
  }

  override fun suggestWorktreeBranch(): String = WorktreeNames.suggest(Random.Default)

  override suspend fun startSubSession(
    parentId: SessionId,
    location: SubSessionLocation,
    initialInput: String?,
    origin: SessionOrigin,
    namePrefix: String,
    branch: String?,
  ): LemonadeCodeResult<SessionId, SessionError> {
    val parent = state.value.firstOrNull { it.id == parentId } ?: return LemonadeCodeResult.failure(SessionError.UnknownSession)
    // Checked here too: a name like `../x` would put the folder somewhere else entirely.
    if (branch != null && !BranchName.isValid(branch)) return LemonadeCodeResult.failure(SessionError.InvalidBranch(branch))
    val plan = planSubSession(parentId, location, namePrefix, branch)
      ?: return LemonadeCodeResult.failure(SessionError.NotARepository)
    if (plan.createsBranch) {
      val root = parent.repositoryRoot ?: return LemonadeCodeResult.failure(SessionError.NotARepository)
      val taken = git.branches(root).getOrNull().orEmpty()
      if (plan.branch in taken) return LemonadeCodeResult.failure(SessionError.BranchExists(plan.branch.orEmpty()))
      val created = git.addWorktree(root, plan.workingDirectory, plan.branch.orEmpty(), plan.baseBranch ?: "HEAD")
      if (created is LemonadeCodeResult.Failure) return LemonadeCodeResult.failure(SessionError.WorktreeFailed(created.error.toString()))
    }
    return launch(plan.workingDirectory, initialInput) { id ->
      Session(
        id = id,
        name = plan.name,
        shell = launcher.shellName,
        workingDirectory = plan.workingDirectory,
        repositoryRoot = if (plan.createsBranch) plan.workingDirectory else parent.repositoryRoot,
        branch = plan.branch,
        parentId = parent.id,
        placement = if (plan.createsBranch) SessionPlacement.Worktree else SessionPlacement.SameFolder,
        origin = origin,
        status = SessionStatus.Shell,
      )
    }
  }

  override fun paste(id: SessionId, text: String): LemonadeCodeResult<Unit, SessionError> {
    val terminal = registry.terminal(id) ?: return LemonadeCodeResult.failure(SessionError.UnknownSession)
    terminal.paste(text)
    return LemonadeCodeResult.success(Unit)
  }

  override fun close(id: SessionId) {
    // An archived one has nothing running: it's only let go of.
    if (updateArchives { archived -> archived.filterNot { it.id == id.value } }) return
    val doomed = SubSessionPlanner.subtree(state.value, id)
    end(doomed)
    resumes.update { it - doomed.toSet() }
    state.update { sessions -> sessions.filterNot { it.id in doomed } }
  }

  /** Ends the terminals of [ids] and whatever runs in them; [removeWorktree] waits a moment for them. */
  private fun end(ids: Set<SessionId>) {
    forgetEnded()
    val until = TimeSource.Monotonic.markNow() + EndingWait
    ids.forEach { sessionId ->
      watchers.remove(sessionId)?.cancel()
      registry.remove(sessionId)?.let { terminal ->
        processTree(terminal.pid).forEach { ending[it] = until }
        terminal.close()
      }
    }
  }

  override fun archive(id: SessionId) {
    val sessions = state.value
    val session = sessions.firstOrNull { it.id == id } ?: return
    val going = SubSessionPlanner.subtree(sessions, id)
    val siblings = sessions.filter { it.parentId == session.parentId }
    val at = siblings.indexOf(session)
    val agents = resumes.value
    // Read while the terminals still run: what they showed, and the agents to resume.
    val archive = SavedArchive(
      sessions = sessions.filter { it.id in going }.map { saved(it, agents[it.id], withOutput = true, archived = true) },
      after = siblings.getOrNull(at - 1)?.id?.value,
      before = siblings.getOrNull(at + 1)?.id?.value,
    )
    end(going)
    resumes.update { it - going }
    state.update { list -> list.filterNot { it.id in going } }
    val _ = updateArchives { listOf(archive) + it }
  }

  override suspend fun unarchive(id: SessionId): LemonadeCodeResult<Unarchived, SessionError> {
    val archive = takeArchive(id) ?: return LemonadeCodeResult.failure(SessionError.UnknownSession)
    val missing = mutableMapOf<SessionId, String>()
    archive.sessions.forEachIndexed { index, entry ->
      val gone = !File(entry.workingDirectory).isDirectory
      if (gone) missing[SessionId(entry.id)] = entry.workingDirectory
      // An agent's conversation belongs to its folder; at home it would find none.
      val relaunched = relaunch(
        entry = if (gone) entry.copy(resume = null) else entry,
        after = archive.after.takeIf { index == 0 },
        before = archive.before.takeIf { index == 0 },
      )
      // Without its first shell nothing came back: it stays archived.
      if (index == 0 && relaunched is LemonadeCodeResult.Failure) {
        val _ = updateArchives { listOf(archive) + it }
        return LemonadeCodeResult.failure(relaunched.error)
      }
    }
    return LemonadeCodeResult.success(Unarchived(id, missing))
  }

  /** Takes the archive [id] heads out of the archived ones, so it comes back once only. */
  private fun takeArchive(id: SessionId): SavedArchive? = synchronized(archiveLock) {
    val found = archives.value.firstOrNull { it.id == id.value } ?: return null
    val _ = updateArchives { archived -> archived - found }
    found
  }

  /** Changes the archived sessions as kept, and as shown; false when nothing changed. */
  private fun updateArchives(transform: (List<SavedArchive>) -> List<SavedArchive>): Boolean = synchronized(archiveLock) {
    val current = archives.value
    val next = transform(current)
    if (next == current) return false
    archives.value = next
    archivedSessions.value = next.map { archive ->
      val shown = archive.sessions.map { it.archivedSession() }
      ArchivedSession(session = shown.first(), subSessions = shown.drop(1))
    }
    true
  }

  /** How an archived session shows: as it was when archived, with nothing running in it. */
  private fun SavedSession.archivedSession(): Session = Session(
    id = SessionId(id),
    name = name,
    shell = launcher.shellName,
    workingDirectory = workingDirectory,
    repositoryRoot = repositoryRoot,
    branch = branch,
    parentId = parentId?.let(::SessionId),
    placement = placement(),
    origin = origin(),
    status = SessionStatus.Shell,
    customName = customName,
    agent = agent,
  )

  override suspend fun worktreeOf(id: SessionId): LinkedWorktree? {
    val session = state.value.firstOrNull { it.id == id }
      ?: archivedSessions.value.flatMap { it.sessions }.firstOrNull { it.id == id }
      ?: return null
    val worktree = git.linkedWorktree(session.workingDirectory).getOrNull() ?: return null
    return LinkedWorktree(path = worktree.root, branch = worktree.branch, mainCheckout = worktree.mainRoot, hasChanges = worktree.hasChanges)
  }

  override suspend fun removeWorktree(worktree: LinkedWorktree, force: Boolean): LemonadeCodeResult<Unit, SessionError> {
    // A shell, or an agent it ran, still in the folder could write into it as it goes, and on
    // Windows would keep it from being deleted at all.
    while (forgetEnded()) delay(EndingPoll)
    return git.removeWorktree(worktree.mainCheckout, worktree.path, force).mapError { error ->
      SessionError.WorktreeNotRemoved(worktree.path, error.describe())
    }
  }

  /** Drops the closed processes that have ended or been waited for long enough; true while any are left. */
  private fun forgetEnded(): Boolean {
    ending.entries.removeIf { (pid, until) -> until.hasPassedNow() || !isRunning(pid) }
    return ending.isNotEmpty()
  }

  override fun rename(id: SessionId, name: String?) {
    val custom = name?.trim()?.takeIf { it.isNotEmpty() }
    update(id) { it.copy(customName = custom) }
  }

  override suspend fun restore(): Int {
    val loaded = safeSuspendRunCatching { store.load() }.getOrNull()
    // Archived sessions stay archived: nothing starts for them.
    val shelved = loaded?.archived.orEmpty().filter { it.id != null }
    val _ = updateArchives { current -> current + shelved.filter { archive -> current.none { it.id == archive.id } } }
    var count = 0
    for (entry in loaded?.sessions.orEmpty()) {
      if (state.value.any { it.id.value == entry.id }) continue
      val restored = relaunch(entry)
      if (restored is LemonadeCodeResult.Success) count++
    }
    startSaving()
    return count
  }

  /**
   * Starts a saved session again in a fresh shell, under its parent if that's open, else at the top.
   * The first of a group goes back between its siblings [after] and [before], if they're still there.
   */
  private suspend fun relaunch(entry: SavedSession, after: String? = null, before: String? = null): LemonadeCodeResult<SessionId, SessionError> {
    // A worktree removed since, or a folder deleted: start at home rather than not at all.
    val folder = File(entry.workingDirectory).takeIf { it.isDirectory }?.absolutePath ?: home.path
    val parent = entry.parentId?.let(::SessionId)?.takeIf { parentId -> state.value.any { it.id == parentId } }
    val checkout = git.checkout(folder).getOrNull()
    return launch(folder, initialInput = null, restored = entry, after = after?.let(::SessionId), before = before?.let(::SessionId)) { id ->
      Session(
        id = id,
        name = if (parent == null) rootName(folder) else entry.name,
        shell = launcher.shellName,
        workingDirectory = folder,
        repositoryRoot = checkout?.root,
        branch = checkout?.branch,
        parentId = parent,
        placement = if (parent == null) SessionPlacement.Root else entry.placement(),
        origin = entry.origin(),
        status = SessionStatus.Shell,
        customName = entry.customName,
      )
    }
  }

  override suspend fun shutdown() {
    if (!shuttingDown.compareAndSet(false, true)) return
    saver.getAndSet(null)?.cancel()
    val sessions = state.value
    val _ = safeSuspendRunCatching { store.save(snapshot(sessions, resumes.value, archives.value, withOutput = true)) }
    sessions.forEach { session ->
      watchers.remove(session.id)?.cancel()
      registry.remove(session.id)?.close()
    }
  }

  /** Keeps the saved sessions in step with the open ones, so even a crash loses little. */
  @OptIn(FlowPreview::class)
  private fun startSaving() {
    val job = scope.launch(start = CoroutineStart.LAZY) {
      combine(state, resumes, archives) { sessions, agents, archived -> snapshot(sessions, agents, archived, withOutput = false) }
        .distinctUntilChanged()
        .debounce(SaveDelay)
        .collect { saved ->
          if (!shuttingDown.get()) {
            val _ = safeSuspendRunCatching { store.save(saved) }
          }
        }
    }
    if (saver.compareAndSet(null, job)) job.start() else job.cancel()
  }

  private fun snapshot(sessions: List<Session>, agents: Map<SessionId, String>, archived: List<SavedArchive>, withOutput: Boolean) = SavedSessions(
    sessions = sessions.map { session -> saved(session, agents[session.id], withOutput) },
    archived = archived,
  )

  /**
   * [session] as saved, with [resume], the command that resumes its agent. An [archived] one keeps
   * what it shows while archived: its checkout, branch and agent.
   */
  private fun saved(session: Session, resume: String?, withOutput: Boolean, archived: Boolean = false) = SavedSession(
    id = session.id.value,
    name = session.name,
    customName = session.customName,
    workingDirectory = session.workingDirectory,
    parentId = session.parentId?.value,
    placement = session.placement.name,
    origin = SavedSession.originName(session.origin),
    reviewCommentCount = (session.origin as? SessionOrigin.Review)?.commentCount,
    resume = resume,
    // A resumed agent shows its own history; a plain shell gets back what it showed.
    output = if (withOutput && resume == null) registry.terminal(session.id)?.recentOutput(MaxRestoredLines)?.takeIf { it.isNotEmpty() } else null,
    repositoryRoot = session.repositoryRoot.takeIf { archived },
    branch = session.branch.takeIf { archived },
    // The program the resume command starts, when the agent isn't reporting.
    agent = (session.agent ?: resume?.substringBefore(' ')).takeIf { archived },
  )

  private suspend fun launch(
    directory: String,
    initialInput: String?,
    restored: SavedSession? = null,
    after: SessionId? = null,
    before: SessionId? = null,
    build: (SessionId) -> Session,
  ): LemonadeCodeResult<SessionId, SessionError> = mutations.withLock {
    val id = SessionId(restored?.id ?: UUID.randomUUID().toString())
    val output = restored?.output.orEmpty()
    val terminal = when (val started = safeSuspendRunCatching { launcher.start(directory, output) }) {
      is LemonadeCodeResult.Success -> started.value
      is LemonadeCodeResult.Failure -> return@withLock LemonadeCodeResult.failure(SessionError.LaunchFailed(started.error.message ?: "Couldn't start the shell"))
    }
    val session = build(id)
    registry.register(id, terminal)
    state.update { sessions -> SubSessionPlanner.insertBack(sessions, session, after, before) }
    val resume = restored?.resume
    if (resume != null) resumes.update { it + (id to resume) }
    watchers[id] = scope.launch {
      launch { trackStatus(id, terminal) }
      launch { followFolder(id, terminal) }
      launch { trackAgent(id, terminal, pendingResume = resume) }
      if (initialInput != null) launch { whenReady(terminal) { terminal.paste(initialInput) } }
      if (resume != null) launch { whenReady(terminal) { terminal.send("$resume\r") } }
    }
    LemonadeCodeResult.success(id)
  }

  /**
   * Notes the agent running in the terminal, so the next launch can resume it. One brought back
   * from last time counts until it shows up: for [resumeGrace], and after that for as long as the
   * program it was started as still runs (Claude asking whether to trust a folder hasn't shown up
   * yet). Once seen, it counts only while it runs, so an agent you quit yourself isn't started again.
   */
  private suspend fun trackAgent(id: SessionId, terminal: TerminalSession, pendingResume: String?) {
    val started = TimeSource.Monotonic.markNow()
    var seen = false
    while (terminal.isRunning) {
      delay(AgentPollInterval)
      val found = withContext(dispatchers.io) { agents.resumeCommand(terminal.pid) }
      if (found != null) seen = true
      val pending = pendingResume?.takeIf { !seen }
      val starting = pending != null &&
        (started.elapsedNow() < resumeGrace || withContext(dispatchers.io) { terminal.programInForeground() } == true)
      val resume = found ?: pending?.takeIf { starting }
      resumes.update { current ->
        when {
          current[id] == resume -> current
          resume == null -> current - id
          else -> current + (id to resume)
        }
      }
    }
  }

  /**
   * Shows what the agent in the terminal reports about itself, and its name: the program holding
   * the terminal, else the app it names in its report. A plain shell shows nothing.
   */
  private suspend fun trackStatus(id: SessionId, terminal: TerminalSession): Unit = coroutineScope {
    val tracker = AgentStatusTracker()
    val lock = Mutex()
    var agent: String? = null
    suspend fun apply(reportedApp: String? = null, change: AgentStatusTracker.() -> Boolean) = lock.withLock {
      val changed = tracker.change()
      val reporting = tracker.status != SessionStatus.Shell && tracker.status != SessionStatus.Exited
      val name = when {
        !reporting -> null
        agent != null -> agent
        else -> withContext(dispatchers.io) { terminal.foregroundProgram() } ?: reportedApp
      }
      if (changed || name != agent) {
        agent = name
        update(id) { it.copy(status = tracker.status, agent = name) }
      }
    }
    // An agent that crashes can't withdraw its reports; once the shell has the terminal back, they're stale.
    val sweeper = launch {
      while (true) {
        delay(StaleReportCheck)
        if (!lock.withLock { tracker.hasReports }) continue
        if (withContext(dispatchers.io) { terminal.programInForeground() } == false) apply { onProgramGone() }
      }
    }
    terminal.signals.collect { signal ->
      when (signal) {
        is TerminalSignal.Program -> apply(signal.status.app) { onReport(signal.status.id, signal.status.state) }
        is TerminalSignal.Exited -> {
          apply { onExited() }
          sweeper.cancel()
        }
        TerminalSignal.Output -> Unit
      }
    }
  }

  /**
   * Keeps folder, repository and branch in step with the shell, or the program holding its terminal,
   * as they move and switch branches.
   */
  private suspend fun followFolder(id: SessionId, terminal: TerminalSession) {
    var lastFolder: String? = null
    var tick = 0
    while (terminal.isRunning) {
      delay(FolderPollInterval)
      val folder = withContext(dispatchers.io) { terminal.currentDirectory() } ?: continue
      val moved = folder != lastFolder
      if (!moved && tick++ % BranchCheckEvery != 0) continue
      lastFolder = folder
      val checkout = git.checkout(folder).getOrNull()
      update(id) { session ->
        session.copy(
          workingDirectory = folder,
          repositoryRoot = checkout?.root,
          branch = checkout?.branch,
          name = if (session.parentId == null) rootName(folder) else session.name,
        )
      }
    }
  }

  /** Waits for the shell to draw its prompt and go quiet, so what is typed or pasted lands at the prompt. */
  private suspend fun whenReady(terminal: TerminalSession, action: () -> Unit) {
    withTimeoutOrNull(ShellStartTimeout) {
      terminal.outputs.first { it > 0 }
      while (true) {
        val seen = terminal.outputs.value
        delay(PromptSettleTime)
        if (terminal.outputs.value == seen) break
      }
    }
    action()
  }

  private fun update(id: SessionId, transform: (Session) -> Session) {
    state.update { sessions -> sessions.map { if (it.id == id) transform(it).takeIf { new -> new != it } ?: it else it } }
  }

  // Windows paths ignore case: `cd c:\users\me` is home too.
  private fun rootName(folder: String): String = if (folder.equals(home.path, ignoreCase = IsWindows)) "~" else folderName(folder, IsWindows).ifEmpty { folder }

  private companion object {
    val StaleReportCheck = 1.seconds
    val FolderPollInterval = 1.seconds
    const val BranchCheckEvery = 3
    val ShellStartTimeout = 10.seconds
    val PromptSettleTime = 400.milliseconds
    val AgentPollInterval = 1.seconds
    val SaveDelay = 500.milliseconds
    const val MaxRestoredLines = 1_000
    val EndingWait = 3.seconds
    val EndingPoll = 50.milliseconds
  }
}

/** [pid] and every process it started, still running. */
private fun processTree(pid: Long): List<Long> = runCatching {
  ProcessHandle.of(pid).map { handle -> listOf(pid) + handle.descendants().map { it.pid() }.toList() }.orElse(emptyList())
}.getOrDefault(listOf(pid))

private fun isRunning(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

/** What git said, without its `fatal:`. */
private fun GitError.describe(): String = when (this) {
  is GitError.CommandFailed -> message.removePrefix("fatal: ").removePrefix("error: ")
  is GitError.Unavailable -> message
  is GitError.RefNotFound -> ref
  GitError.NotARepository -> "not a git repository"
}

/** The last part of [path]: `app` for `/Users/me/app`, and on [windows] for `C:\Users\me\app` too. */
internal fun folderName(path: String, windows: Boolean): String {
  val name = path.substringAfterLast('/')
  return if (windows) name.substringAfterLast('\\') else name
}
