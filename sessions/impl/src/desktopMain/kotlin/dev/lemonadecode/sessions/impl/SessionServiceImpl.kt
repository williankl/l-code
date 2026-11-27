package dev.lemonadecode.sessions.impl

import dev.lemonadecode.data.git.GitCheckout
import dev.lemonadecode.data.git.GitDataSource
import dev.lemonadecode.data.git.GitError
import dev.lemonadecode.domain.session.ArchivedSession
import dev.lemonadecode.domain.session.BranchName
import dev.lemonadecode.domain.session.ClaudeConversation
import dev.lemonadecode.domain.session.LinkedWorktree
import dev.lemonadecode.domain.session.Session
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionOrder
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
import dev.lemonadecode.sessions.api.ImportedSession
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

  /** For each session, the agents running in it, the most recently started first. */
  private val runningAgents = ConcurrentHashMap<SessionId, List<RunningAgent>>()

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
    val main = mainCheckoutOf(checkout)
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
        mainCheckout = main,
      )
    }
  }

  override fun planSubSession(parentId: SessionId, location: SubSessionLocation, namePrefix: String, branch: String?): SubSessionPlan? {
    val sessions = state.value
    val parent = sessions.firstOrNull { it.id == parentId } ?: return null
    // Archived ones count too, so one brought back never shares a name with a newer one.
    val named = sessions + archivedSessions.value.flatMap { it.sessions }
    // A folder already there, like a worktree kept from an earlier sub-session, would make git refuse.
    return SubSessionPlanner.plan(parent, named, location, namePrefix, branch) { File(it).exists() }
  }

  override fun suggestWorktreeBranch(): String = WorktreeNames.suggest(Random.Default)

  override suspend fun openImported(session: ImportedSession): LemonadeCodeResult<SessionId, SessionError> {
    val folder = File(session.directory)
    if (!folder.isDirectory) return LemonadeCodeResult.failure(SessionError.FolderNotFound(folder.path))
    val parent = session.parentId?.takeIf { parentId -> state.value.any { it.id == parentId } }
    val checkout = git.checkout(folder.absolutePath).getOrNull()
    val main = mainCheckoutOf(checkout)
    // Brought in as if saved at the last quit, so its agent is picked up the same way.
    val entry = SavedSession(
      id = UUID.randomUUID().toString(),
      name = session.name,
      workingDirectory = folder.absolutePath,
      resume = session.claudeConversation?.let(ClaudeConversation::resumeCommand),
      customName = session.customName?.trim()?.takeIf { it.isNotEmpty() },
    )
    return launch(folder.absolutePath, initialInput = null, restored = entry) { id ->
      Session(
        id = id,
        name = if (parent == null) rootName(folder.absolutePath) else session.name,
        shell = launcher.shellName,
        workingDirectory = folder.absolutePath,
        repositoryRoot = checkout?.root,
        branch = checkout?.branch,
        parentId = parent,
        placement = if (parent == null) SessionPlacement.Root else session.placement.takeIf { it != SessionPlacement.Root } ?: SessionPlacement.SameFolder,
        origin = SessionOrigin.Opened,
        status = SessionStatus.Shell,
        customName = entry.customName,
        mainCheckout = main,
      )
    }
  }

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
        // A new worktree is one of the repository the parent works in.
        mainCheckout = if (plan.createsBranch) parent.mainCheckout ?: parent.repositoryRoot else parent.mainCheckout,
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

  /** Ends the terminals of [ids] and whatever runs in them. */
  private fun end(ids: Set<SessionId>) {
    val _ = forgetEnded()
    ids.forEach { sessionId ->
      watchers.remove(sessionId)?.cancel()
      registry.remove(sessionId)?.let(::endShell)
      val _ = runningAgents.remove(sessionId)
    }
  }

  /** Closes [terminal] and notes its processes, so [removeWorktree] waits a moment for them to end. */
  private fun endShell(terminal: TerminalSession) {
    val until = TimeSource.Monotonic.markNow() + EndingWait
    processTree(terminal.pid).forEach { ending[it] = until }
    terminal.close()
  }

  override fun archive(id: SessionId) {
    // Out of the open sessions and into the archived ones in one step, for whoever saves or adds to them.
    val going = synchronized(archiveLock) {
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
      state.update { list -> list.filterNot { it.id in going } }
      val _ = updateArchives { listOf(archive) + it }
      going
    }
    end(going)
    resumes.update { it - going }
  }

  /**
   * Brings the archive back a session at a time. Each leaves the archive only as it joins the open
   * sessions, so one whose shell can't start, those not reached when this is cancelled, and what is
   * saved meanwhile all keep every session.
   */
  override suspend fun unarchive(id: SessionId): LemonadeCodeResult<Unarchived, SessionError> {
    val archive = archives.value.firstOrNull { it.id == id.value } ?: return LemonadeCodeResult.failure(SessionError.UnknownSession)
    val group = archive.sessions.mapTo(HashSet()) { it.id }
    val missing = mutableMapOf<SessionId, String>()
    // Those that couldn't come back, and those under them: they stay archived, and are said to.
    val kept = mutableListOf<String>()
    for ((index, entry) in archive.sessions.withIndex()) {
      val parentId = entry.parentId
      // Under one that stayed, or that was archived again meanwhile: it stays too.
      if (index > 0 && parentId in group && (parentId in kept || state.value.none { it.id.value == parentId })) {
        kept += entry.id
        continue
      }
      val gone = !File(entry.workingDirectory).isDirectory
      // An agent's conversation belongs to its folder; at home it would find none.
      val relaunched = relaunch(
        entry = if (gone) entry.copy(resume = null) else entry,
        after = archive.after.takeIf { index == 0 },
        before = archive.before.takeIf { index == 0 },
        claim = { takeFromArchive(entry.id) },
      )
      if (relaunched is LemonadeCodeResult.Success) {
        if (gone) missing[relaunched.value] = entry.workingDirectory
        continue
      }
      val error = (relaunched as LemonadeCodeResult.Failure).error
      // Out of the archive without this call: back already, through another restore of it, which sees
      // to the rest; or deleted for good, with the rest.
      val backAlready = error == SessionError.UnknownSession && state.value.any { it.id.value == entry.id }
      when {
        index == 0 && backAlready -> return LemonadeCodeResult.success(Unarchived(id))
        // Without its first session nothing came back: it all stays archived, where it was.
        index == 0 -> return LemonadeCodeResult.failure(error)
        error == SessionError.UnknownSession -> break
        else -> kept += entry.id
      }
    }
    return LemonadeCodeResult.success(Unarchived(id, missing, kept.map(::SessionId)))
  }

  /**
   * Takes the session [id] out of the archive it's in, as it joins the open sessions; false when it
   * isn't there any more. What's left of an archive whose first session went keeps its place in the
   * list, split so each session left at the top heads its own, as the list shows them.
   */
  private fun takeFromArchive(id: String): Boolean = updateArchives { archived ->
    archived.flatMap { archive ->
      when {
        archive.sessions.none { it.id == id } -> listOf(archive)
        archive.id == id -> separate(archive.sessions.drop(1))
        else -> listOf(archive.copy(sessions = archive.sessions.filterNot { it.id == id }))
      }
    }
  }

  /** [sessions] as archives: each whose parent isn't among them heads one, with those under it. */
  private fun separate(sessions: List<SavedSession>): List<SavedArchive> {
    val groups = mutableListOf<MutableList<SavedSession>>()
    val groupOf = HashMap<String, MutableList<SavedSession>>()
    sessions.forEach { session ->
      val group = session.parentId?.let(groupOf::get) ?: mutableListOf<SavedSession>().also(groups::add)
      group += session
      groupOf[session.id] = group
    }
    return groups.map { SavedArchive(it) }
  }

  /** The session it goes under has been archived, so it can't join the open sessions. Call with the archive locked. */
  private fun parentArchived(session: Session): Boolean {
    val parentId = session.parentId ?: return false
    return state.value.none { it.id == parentId } && archives.value.any { archive -> archive.sessions.any { it.id == parentId.value } }
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
    workingDirectory = agentDirectory ?: workingDirectory,
    processDirectory = workingDirectory.takeIf { agentDirectory != null },
    repositoryRoot = repositoryRoot,
    branch = branch,
    parentId = parentId?.let(::SessionId),
    placement = placement(),
    origin = origin(),
    status = SessionStatus.Shell,
    customName = customName,
    agent = agent,
    agentDirectories = agentDirectories.orEmpty(),
  )

  override suspend fun worktreeOf(id: SessionId): LinkedWorktree? {
    val session = state.value.firstOrNull { it.id == id }
      ?: archivedSessions.value.flatMap { it.sessions }.firstOrNull { it.id == id }
      ?: return null
    // Where its programs run, not a worktree its agent only went into: that one may be someone else's.
    val worktree = git.linkedWorktree(session.runsIn).getOrNull() ?: return null
    return LinkedWorktree(path = worktree.root, branch = worktree.branch, mainCheckout = worktree.mainRoot, hasChanges = worktree.hasChanges)
  }

  /** The main checkout [checkout] is a linked worktree of; null for a main checkout, or outside git. */
  private suspend fun mainCheckoutOf(checkout: GitCheckout?): String? = checkout?.let { git.mainCheckout(it.root).getOrNull() }

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

  // Saved like any other change: the saved sessions are in display order, and come back in it.
  override fun move(id: SessionId, index: Int) {
    state.update { sessions -> SessionOrder.move(sessions, id, index) }
  }

  override suspend fun restore(): Int {
    val loaded = safeSuspendRunCatching { store.load() }.getOrNull()
    // Archived sessions stay archived: nothing starts for them. Each is kept once, and not at all
    // when it's open too, so a file saved twice over, or caught between the two lists, brings back no copies.
    val _ = updateArchives { current ->
      val seen = (current.flatMap { archive -> archive.sessions.map { it.id } } + loaded?.sessions.orEmpty().map { it.id } + state.value.map { it.id.value }).toMutableSet()
      current + loaded?.archived.orEmpty().flatMap { archive ->
        val kept = archive.sessions.filter { seen.add(it.id) }
        if (kept.isNotEmpty() && kept.first().id == archive.id) listOf(archive.copy(sessions = kept)) else separate(kept)
      }
    }
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
   * [claim] is asked as it joins the open sessions; see [launch].
   */
  private suspend fun relaunch(
    entry: SavedSession,
    after: String? = null,
    before: String? = null,
    claim: () -> Boolean = { true },
  ): LemonadeCodeResult<SessionId, SessionError> {
    // A worktree removed since, or a folder deleted: start at home rather than not at all.
    val folder = File(entry.workingDirectory).takeIf { it.isDirectory }?.absolutePath ?: home.path
    val parent = entry.parentId?.let(::SessionId)?.takeIf { parentId -> state.value.any { it.id == parentId } }
    val checkout = git.checkout(folder).getOrNull()
    val main = mainCheckoutOf(checkout)
    return launch(folder, initialInput = null, restored = entry, after = after?.let(::SessionId), before = before?.let(::SessionId), claim = claim) { id ->
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
        mainCheckout = main,
      )
    }
  }

  override suspend fun shutdown() {
    if (!shuttingDown.compareAndSet(false, true)) return
    saver.getAndSet(null)?.cancel()
    val (sessions, toSave) = synchronized(archiveLock) { state.value.let { it to snapshot(it, resumes.value, archives.value, withOutput = true) } }
    val _ = safeSuspendRunCatching { store.save(toSave) }
    sessions.forEach { session ->
      watchers.remove(session.id)?.cancel()
      registry.remove(session.id)?.close()
    }
  }

  /** Keeps the saved sessions in step with the open ones, so even a crash loses little. */
  @OptIn(FlowPreview::class)
  private fun startSaving() {
    val job = scope.launch(start = CoroutineStart.LAZY) {
      // Read together under the archive's lock, so a session moving between the lists is in exactly one.
      combine(state, resumes, archives) { _, _, _ -> synchronized(archiveLock) { snapshot(state.value, resumes.value, archives.value, withOutput = false) } }
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
   * [session] as saved, with [resume], the command that resumes its agent. It starts again where its
   * programs ran, where an agent's conversation is kept. An [archived] one keeps what it shows while
   * archived: the folder its agent worked in, its checkout, branch and agent.
   */
  private fun saved(session: Session, resume: String?, withOutput: Boolean, archived: Boolean = false) = SavedSession(
    id = session.id.value,
    name = session.name,
    customName = session.customName,
    // Saved, workingDirectory is where it starts again, not the folder shown: copying the session's own across would resume Claude where it has no conversation.
    workingDirectory = session.runsIn,
    agentDirectory = session.workingDirectory.takeIf { archived && session.processDirectory != null },
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
    agentDirectories = session.agentDirectories.takeIf { archived && it.isNotEmpty() },
  )

  private suspend fun launch(
    directory: String,
    initialInput: String?,
    restored: SavedSession? = null,
    after: SessionId? = null,
    before: SessionId? = null,
    /** Asked as it joins the open sessions; false keeps it out, as one deleted for good meanwhile. */
    claim: () -> Boolean = { true },
    build: (SessionId) -> Session,
  ): LemonadeCodeResult<SessionId, SessionError> = mutations.withLock {
    val id = SessionId(restored?.id ?: UUID.randomUUID().toString())
    val output = restored?.output.orEmpty()
    val terminal = when (val started = safeSuspendRunCatching { launcher.start(directory, output) }) {
      is LemonadeCodeResult.Success -> started.value
      is LemonadeCodeResult.Failure -> return@withLock LemonadeCodeResult.failure(SessionError.LaunchFailed(started.error.message ?: "Couldn't start the shell"))
    }
    val session = build(id)
    // In one step with archiving. A session whose parent was archived while its shell started
    // doesn't run on its own outside it: a new one isn't created, a restored one stays archived.
    val placement = synchronized(archiveLock) {
      when {
        parentArchived(session) -> Placement.ParentArchived
        !claim() -> Placement.Dropped
        else -> {
          registry.register(id, terminal)
          state.update { sessions -> SubSessionPlanner.insertBack(sessions, session, after, before) }
          Placement.Listed
        }
      }
    }
    if (placement != Placement.Listed) {
      endShell(terminal)
      return@withLock LemonadeCodeResult.failure(if (placement == Placement.ParentArchived) SessionError.ParentArchived else SessionError.UnknownSession)
    }
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
   * Notes the agent running in the terminal, so the next launch can resume it, and where it says it
   * works, for [followFolder]. One brought back from last time counts until it shows up: for
   * [resumeGrace], and after that for as long as the program it was started as still runs (Claude
   * asking whether to trust a folder hasn't shown up yet). Once seen, it counts only while it runs,
   * so an agent you quit yourself isn't started again.
   */
  private suspend fun trackAgent(id: SessionId, terminal: TerminalSession, pendingResume: String?) {
    val started = TimeSource.Monotonic.markNow()
    var seen = false
    while (terminal.isRunning) {
      delay(AgentPollInterval)
      val running = withContext(dispatchers.io) { agents.find(terminal.pid) }
      runningAgents[id] = running
      val found = running.firstOrNull()?.resume
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
      // Each one's folder, in front or not: a worktree a suspended Claude works in isn't offered for deletion.
      update(id) { it.copy(agentDirectories = running.mapNotNull(RunningAgent::workingDirectory).distinct()) }
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
   * Keeps folder, repository and branch in step with where the session works, as it moves and
   * switches branches: the folder of the program holding the terminal, else the shell's. While an
   * agent holds it and says it works in another checkout, that folder instead: Claude Code carries a
   * `cd` forward without moving, so the folder its process is in is kept apart, as where the session
   * starts again. Within the process's own checkout the branch and the diff are the same, so there
   * the session stays where the process is.
   */
  private suspend fun followFolder(id: SessionId, terminal: TerminalSession) {
    var last: Pair<String, String>? = null
    var tick = 0
    val processRoot = RootOf()
    val agentRoot = RootOf()
    var main: Pair<String?, String?> = null to null
    // A process stays in the group it started in, so each agent's is asked once.
    val groups = HashMap<Long, Long>()
    while (terminal.isRunning) {
      delay(FolderPollInterval)
      val foreground = withContext(dispatchers.io) { terminal.foreground() }
      val process = foreground.directory ?: continue
      val agents = runningAgents[id].orEmpty()
      val _ = groups.keys.retainAll(agents.map { it.pid }.toSet())
      // Only the agent in the group holding the terminal: not one that's suspended, or another under it,
      // nor one whose group can't be read, like one that just quit. Where the terminal's own can't be
      // told, as on Windows, the most recently started counts.
      val holding = agents.firstOrNull { agent ->
        val front = foreground.group ?: return@firstOrNull true
        val group = groups[agent.pid] ?: withContext(dispatchers.io) { terminal.processGroupOf(agent.pid) }?.also { groups[agent.pid] = it }
        group == front
      }
      val elsewhere = holding?.workingDirectory?.takeIf { it != process }
      val folder = if (elsewhere == null) {
        process
      } else {
        // Until git can tell where both are, the session stays as it is, and asks again next time.
        val processAt = processRoot(process)
        if (processAt !is LemonadeCodeResult.Success) continue
        val shared = processAt.value
        if (shared == null) {
          elsewhere
        } else {
          val agentAt = agentRoot(elsewhere)
          if (agentAt !is LemonadeCodeResult.Success) continue
          if (agentAt.value == shared) process else elsewhere
        }
      }
      val moved = (folder to process) != last
      if (!moved && tick++ % BranchCheckEvery != 0) continue
      last = folder to process
      val checkout = git.checkout(folder).getOrNull()
      if (checkout?.root != main.first) {
        when (val found = checkout?.let { git.mainCheckout(it.root) } ?: LemonadeCodeResult.success(null)) {
          is LemonadeCodeResult.Success -> main = checkout?.root to found.value
          // Not known yet: asked again on the next tick.
          is LemonadeCodeResult.Failure -> last = null
        }
      }
      val processRepository = if (folder == process) null else processRoot(process).getOrNull()
      update(id) { session ->
        session.copy(
          workingDirectory = folder,
          processDirectory = process.takeIf { it != folder },
          processRepositoryRoot = processRepository,
          repositoryRoot = checkout?.root,
          branch = checkout?.branch,
          mainCheckout = main.second.takeIf { main.first == checkout?.root },
          name = if (session.parentId == null) rootName(folder) else session.name,
        )
      }
    }
  }

  /**
   * The top-level folder of the checkout a folder is in, null outside git, as git said when last
   * asked: once each time the folder changes. When git can't tell, as when it times out, that's a
   * failure, and git is asked again next time.
   */
  private inner class RootOf {
    private var folder: String? = null
    private var root: String? = null

    suspend operator fun invoke(folder: String): LemonadeCodeResult<String?, GitError> {
      if (folder == this.folder) return LemonadeCodeResult.success(root)
      val found = when (val checkout = git.checkout(folder)) {
        is LemonadeCodeResult.Success -> checkout.value.root
        is LemonadeCodeResult.Failure -> if (checkout.error == GitError.NotARepository) null else return LemonadeCodeResult.failure(checkout.error)
      }
      this.folder = folder
      root = found
      return LemonadeCodeResult.success(found)
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

  /** Where a launched session went: into the open sessions, or nowhere, as its parent was archived or it was already out of the archive. */
  private enum class Placement { Listed, ParentArchived, Dropped }

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
