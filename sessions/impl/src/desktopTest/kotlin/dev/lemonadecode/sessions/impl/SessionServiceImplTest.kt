package dev.lemonadecode.sessions.impl

import dev.lemonadecode.data.git.GitCheckout
import dev.lemonadecode.data.git.GitDataSource
import dev.lemonadecode.data.git.GitError
import dev.lemonadecode.data.git.GitWorktree
import dev.lemonadecode.data.git.fake.FakeGitDataSource
import dev.lemonadecode.domain.session.LinkedWorktree
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionOrigin
import dev.lemonadecode.domain.session.SessionStatus
import dev.lemonadecode.domain.session.SubSessionLocation
import dev.lemonadecode.platform.architecture.testing.FakeLemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.core.getOrNull
import dev.lemonadecode.platform.process.HomeDirectory
import dev.lemonadecode.platform.terminal.TerminalLaunch
import dev.lemonadecode.platform.terminal.TerminalSession
import dev.lemonadecode.sessions.api.SessionError
import dev.lemonadecode.sessions.api.Unarchived
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SessionServiceImplTest {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val git = FakeGitDataSource()
  private val registry = SessionRegistry()
  private val home = Files.createTempDirectory("lemonade-code-home").toFile().canonicalFile
  private var shell: ShellLauncher = ShLauncher
  private val store = MemorySessionStore()

  /** What the agent finder reports, by shell process id. */
  private var agent: (Long) -> String? = { null }
  private val services = mutableListOf<SessionServiceImpl>()

  /** Runs before each shell starts, with its folder: a test can hold a start there, or make it fail. */
  @Volatile private var beforeStart: (String) -> Unit = {}

  /** Runs before git is asked about a folder: a test can hold a launch there. */
  @Volatile private var beforeCheckout: suspend (String) -> Unit = {}

  /** A service as one launch of Lemonade Code sees it; a second one over the same [store] is the next launch. */
  private fun newService(registry: SessionRegistry) = SessionServiceImpl(
    git = object : GitDataSource by git {
      override suspend fun checkout(directory: String): LemonadeCodeResult<GitCheckout, GitError> {
        beforeCheckout(directory)
        return git.checkout(directory)
      }
    },
    launcher = object : ShellLauncher {
      override val shellName: String get() = shell.shellName

      override fun start(directory: String, restoredOutput: List<String>): TerminalSession {
        beforeStart(directory)
        return shell.start(directory, restoredOutput)
      }
    },
    registry = registry,
    home = object : HomeDirectory {
      override val path: String = home.path
    },
    dispatchers = FakeLemonadeCodeCoroutineDispatchers(Dispatchers.IO),
    scope = scope,
    store = store,
    agents = AgentLocator { pid -> agent(pid) },
  ).also { services += it }

  private val service = newService(registry)

  @AfterTest
  fun tearDown() {
    services.forEach { service -> service.sessions.value.forEach { service.close(it.id) } }
    scope.cancel()
  }

  @Test
  fun `a new terminal starts at home`() = runBlocking<Unit> {
    val id = service.openTerminal().getOrNull()!!
    val session = service.sessions.value.single()
    assertEquals(id, session.id)
    assertEquals(home.path, session.workingDirectory)
    assertEquals("~", session.name)
    assertEquals("sh", session.shell)
    assertTrue(registry.terminal(id) != null)
  }

  @Test
  fun `the session follows the shell into a repository`() = runBlocking<Unit> {
    val repo = File(home, "storefront").apply { mkdirs() }
    git.checkoutResult = LemonadeCodeResult.success(GitCheckout(root = repo.path, branch = "feat/x"))
    val id = service.openTerminal().getOrNull()!!
    registry.terminal(id)!!.send("cd storefront\r")
    val moved = withTimeout(10_000) {
      service.sessions.first { it.single().workingDirectory.let(::File).canonicalPath == repo.canonicalPath }.single()
    }
    assertEquals("storefront", moved.name)
    assertEquals("feat/x", moved.branch)
  }

  @Test
  fun `pasting puts the text in the terminal without running it`() = runBlocking<Unit> {
    val id = service.openTerminal().getOrNull()!!
    delay(300)
    val _ = service.paste(id, "echo pasted")
    val terminal = registry.terminal(id)!!
    withTimeout(5_000) {
      while (!terminal.screen().contains("echo pasted")) delay(20)
    }
    delay(300)
    assertTrue(terminal.screen().lines().none { it.trimEnd() == "pasted" }, "the command shouldn't run")
  }

  @Test
  fun `a plain shell shows no status, whatever it prints`() = runBlocking<Unit> {
    if (!File("/bin/zsh").exists()) return@runBlocking
    shell = ZshLauncher
    val id = service.openTerminal().getOrNull()!!
    val terminal = registry.terminal(id)!!
    val statuses = mutableListOf<SessionStatus?>()
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) { service.sessions.collect { statuses += it.singleOrNull()?.status } }
    terminal.send("for i in 1 2 3; do echo line \$i; sleep 0.2; done\r")
    // Dragging a window edge: a resize every few frames, each one redrawing the prompt.
    repeat(20) { step ->
      terminal.resize(80 + step, 24 + step % 3)
      delay(50)
    }
    delay(500)
    watcher.cancel()
    assertEquals(setOf<SessionStatus?>(SessionStatus.Shell), statuses.toSet())
  }

  @Test
  fun `an agent's reports drive the status, and are dropped once it is gone`() = runBlocking<Unit> {
    val id = service.openTerminal().getOrNull()!!
    delay(300)
    // Plays an agent: idle itself with a background agent working, then blocked, then it dies without clearing.
    val main = "\\033]7501;state=idle:app=claude-code\\007"
    val background = "\\033]7501;state=working:app=claude-code:id=a1\\007"
    val blocked = "\\033]7501;state=blocked:app=claude-code:kind=permission\\007"
    registry.terminal(id)!!.send("printf '$main$background'; sleep 1.5; printf '$blocked'; sleep 1.5\r")
    withTimeout(5_000) { service.sessions.first { it.single().status == SessionStatus.Working && it.single().agent != null } }
    withTimeout(5_000) { service.sessions.first { it.single().status == SessionStatus.NeedsInput } }
    withTimeout(8_000) { service.sessions.first { it.single().status == SessionStatus.Shell && it.single().agent == null } }
  }

  @Test
  fun `quitting saves the sessions and the next launch brings them back`() = runBlocking<Unit> {
    val repo = File(home, "storefront").apply { mkdirs() }
    git.checkoutResult = LemonadeCodeResult.success(GitCheckout(root = repo.path, branch = "main"))
    val root = service.openTerminal().getOrNull()!!
    registry.terminal(root)!!.send("cd storefront; echo saved-line\r")
    withTimeout(10_000) {
      service.sessions.first { it.single().workingDirectory.let(::File).canonicalPath == repo.canonicalPath }
    }
    val sub = service.startSubSession(root, SubSessionLocation.SameFolder).getOrNull()!!
    withTimeout(5_000) {
      while (registry.terminal(root)!!.screen().lines().none { it.trim() == "saved-line" }) delay(20)
    }

    service.shutdown()
    val saved = store.saved!!.sessions
    assertEquals(listOf(root.value, sub.value), saved.map { it.id })
    assertEquals(repo.canonicalPath, File(saved[0].workingDirectory).canonicalPath)
    assertTrue("saved-line" in saved[0].output.orEmpty(), "output: ${saved[0].output}")
    assertEquals(null, registry.terminal(root), "quitting ends the terminals")

    val nextRegistry = SessionRegistry()
    val next = newService(nextRegistry)
    assertEquals(2, next.restore())
    val restored = next.sessions.value
    assertEquals(listOf(root, sub), restored.map { it.id })
    assertEquals(listOf("storefront", "sub-1"), restored.map { it.name })
    assertEquals(root, restored[1].parentId)
    assertEquals(repo.canonicalPath, File(restored[0].workingDirectory).canonicalPath)
    withTimeout(5_000) {
      while (nextRegistry.terminal(root)!!.screen().lines().none { it.trim() == "saved-line" }) delay(20)
    }
  }

  @Test
  fun `a renamed session keeps its name as the shell moves and on the next launch`() = runBlocking<Unit> {
    val repo = File(home, "storefront").apply { mkdirs() }
    git.checkoutResult = LemonadeCodeResult.success(GitCheckout(root = repo.path, branch = "main"))
    val id = service.openTerminal().getOrNull()!!
    service.rename(id, "  rate limits ")
    assertEquals("rate limits", service.sessions.value.single().displayName)

    registry.terminal(id)!!.send("cd storefront\r")
    val moved = withTimeout(10_000) { service.sessions.first { it.single().name == "storefront" }.single() }
    assertEquals("rate limits", moved.displayName, "the folder changes the automatic name only")

    service.shutdown()
    assertEquals("rate limits", store.saved!!.sessions.single().customName)
    val next = newService(SessionRegistry())
    assertEquals(1, next.restore())
    assertEquals("rate limits", next.sessions.value.single().displayName)

    next.rename(id, " ")
    val reset = next.sessions.value.single()
    assertEquals(null, reset.customName)
    assertEquals("storefront", reset.displayName)
  }

  @Test
  fun `an agent running at quit is resumed on the next launch, one you quit isn't`() = runBlocking<Unit> {
    assertEquals(0, service.restore())
    val running = service.openTerminal().getOrNull()!!
    val exited = service.openTerminal().getOrNull()!!
    val runningPid = registry.terminal(running)!!.pid
    val exitedPid = registry.terminal(exited)!!.pid
    var exitedAgentUp = true
    agent = { pid ->
      when (pid) {
        runningPid -> "echo resumed-agent"
        exitedPid -> "echo should-not-run".takeIf { exitedAgentUp }
        else -> null
      }
    }
    fun resumeOf(id: dev.lemonadecode.domain.session.SessionId) = store.saved?.sessions?.firstOrNull { it.id == id.value }?.resume
    withTimeout(5_000) {
      while (resumeOf(running) == null || resumeOf(exited) == null) delay(50)
    }
    exitedAgentUp = false
    withTimeout(5_000) {
      while (resumeOf(exited) != null) delay(50)
    }

    service.shutdown()
    assertEquals("echo resumed-agent", resumeOf(running))
    assertEquals(null, store.saved!!.sessions.first { it.id == running.value }.output, "a resumed agent shows its own history")
    assertEquals(null, resumeOf(exited))

    val nextRegistry = SessionRegistry()
    assertEquals(2, newService(nextRegistry).restore())
    withTimeout(10_000) {
      while (nextRegistry.terminal(running)!!.screen().lines().none { it.trim() == "resumed-agent" }) delay(50)
    }
    delay(500)
    assertTrue(nextRegistry.terminal(exited)!!.screen().lines().none { it.trim() == "should-not-run" })
  }

  @Test
  fun `a resumed agent still starting up is kept, one that failed to start is dropped`() = runBlocking<Unit> {
    // Neither ever registers as an agent: one sits at a prompt (as Claude does asking to trust a folder), one exits.
    store.saved = SavedSessions(
      listOf(
        SavedSession(id = "waiting", name = "~", workingDirectory = home.path, resume = "sleep 60"),
        SavedSession(id = "failed", name = "~", workingDirectory = home.path, resume = "true"),
      ),
    )
    service.resumeGrace = 1.seconds
    assertEquals(2, service.restore())
    delay(4_000)
    service.shutdown()
    assertEquals("sleep 60", store.saved!!.sessions.first { it.id == "waiting" }.resume)
    assertEquals(null, store.saved!!.sessions.first { it.id == "failed" }.resume)
  }

  @Test
  fun `a folder that's gone since starts at home`() = runBlocking<Unit> {
    store.saved = SavedSessions(listOf(SavedSession(id = "gone", name = "sub-1", workingDirectory = "/no/such/folder")))
    assertEquals(1, service.restore())
    assertEquals(home.path, service.sessions.value.single().workingDirectory)
  }

  @Test
  fun `sub-sessions nest under their parent and closing the parent closes them`() = runBlocking<Unit> {
    git.checkoutResult = LemonadeCodeResult.success(GitCheckout(root = home.path, branch = "main"))
    val parent = service.openTerminal().getOrNull()!!
    val review = service.startSubSession(
      parent,
      SubSessionLocation.SameFolder,
      initialInput = "fix it",
      origin = SessionOrigin.Review(2),
      namePrefix = "review",
    ).getOrNull()!!
    File(home.parentFile, "${home.name}.worktrees/sub-1").mkdirs()
    val worktree = service.startSubSession(parent, SubSessionLocation.NewWorktree).getOrNull()!!
    val sessions = service.sessions.value
    assertEquals(listOf(parent, review, worktree), sessions.map { it.id })
    assertEquals("review-1", sessions[1].name)
    assertEquals("main-sub-1", git.worktreeCalls.single().branch)
    withTimeout(5_000) {
      while (!registry.terminal(review)!!.screen().contains("fix it")) delay(20)
    }
    service.close(parent)
    assertTrue(service.sessions.value.isEmpty())
    assertEquals(null, registry.terminal(review))
  }

  @Test
  fun `a worktree goes on the branch the user typed, in a folder named after it`() = runBlocking<Unit> {
    git.checkoutResult = LemonadeCodeResult.success(GitCheckout(root = home.path, branch = "main"))
    val parent = service.openTerminal().getOrNull()!!
    // The fake git creates nothing, and the shell needs its folder.
    val folder = File(home.parentFile, "${home.name}.worktrees/generating-bugs").apply { mkdirs() }
    val id = service.startSubSession(parent, SubSessionLocation.NewWorktree, branch = "worktree/generating-bugs").getOrNull()!!
    assertEquals(FakeGitDataSource.WorktreeCall(home.path, folder.path, "worktree/generating-bugs", "main"), git.worktreeCalls.single())
    val session = service.sessions.value.single { it.id == id }
    assertEquals("worktree/generating-bugs", session.branch)
    assertEquals("sub-1", session.name)
  }

  @Test
  fun `a branch git already has, or a name it won't take, creates nothing`() = runBlocking<Unit> {
    git.checkoutResult = LemonadeCodeResult.success(GitCheckout(root = home.path, branch = "main"))
    git.branchesResult = LemonadeCodeResult.success(listOf("main", "worktree/generating-bugs"))
    val parent = service.openTerminal().getOrNull()!!
    assertEquals(
      LemonadeCodeResult.failure(SessionError.BranchExists("worktree/generating-bugs")),
      service.startSubSession(parent, SubSessionLocation.NewWorktree, branch = "worktree/generating-bugs"),
    )
    assertEquals(
      LemonadeCodeResult.failure(SessionError.InvalidBranch("my branch")),
      service.startSubSession(parent, SubSessionLocation.NewWorktree, branch = "my branch"),
    )
    assertTrue(git.worktreeCalls.isEmpty())
    assertEquals(listOf(parent), service.sessions.value.map { it.id })
  }

  @Test
  fun `a session's worktree comes from git, and removing it waits for the shell in it to end`() = runBlocking<Unit> {
    val main = File(home, "storefront").apply { mkdirs() }
    val folder = File(home, "storefront.worktrees/sub-1").apply { mkdirs() }
    git.linkedWorktrees = mapOf(folder.path to GitWorktree(root = folder.path, branch = "feat/x", mainRoot = main.path, hasChanges = true))
    val inside = service.openTerminal(folder.path).getOrNull()!!
    val outside = service.openTerminal(main.path).getOrNull()!!

    val worktree = LinkedWorktree(path = folder.path, branch = "feat/x", mainCheckout = main.path, hasChanges = true)
    assertEquals(worktree, service.worktreeOf(inside))
    assertEquals(null, service.worktreeOf(outside), "the main checkout isn't a linked worktree")

    val shell = registry.terminal(inside)!!.pid
    service.close(inside)
    assertTrue(service.removeWorktree(worktree, force = true).isSuccess)
    assertEquals(listOf(FakeGitDataSource.RemoveWorktreeCall(main.path, folder.path, force = true)), git.removeWorktreeCalls)
    assertFalse(ProcessHandle.of(shell).map { it.isAlive }.orElse(false), "the shell ended before git ran")
  }

  @Test
  fun `a worktree git won't remove says why`() = runBlocking<Unit> {
    git.removeWorktreeResult = LemonadeCodeResult.failure(GitError.CommandFailed("fatal: '/x' contains modified or untracked files, use --force to delete it"))
    assertEquals(
      LemonadeCodeResult.failure(SessionError.WorktreeNotRemoved("/x", "'/x' contains modified or untracked files, use --force to delete it")),
      service.removeWorktree(LinkedWorktree("/x", "feat/x", "/repo", hasChanges = false), force = false),
    )
  }

  @Test
  fun `archiving ends a session and its sub-sessions but keeps them, and restoring brings them back where they were`() = runBlocking<Unit> {
    git.checkoutResult = LemonadeCodeResult.success(GitCheckout(root = home.path, branch = "main"))
    val first = service.openTerminal().getOrNull()!!
    val middle = service.openTerminal().getOrNull()!!
    val last = service.openTerminal().getOrNull()!!
    val sub = service.startSubSession(middle, SubSessionLocation.SameFolder).getOrNull()!!
    service.rename(middle, "rate limits")
    val terminal = registry.terminal(middle)!!
    terminal.send("echo archived-line\r")
    withTimeout(5_000) {
      while (terminal.screen().lines().none { it.trim() == "archived-line" }) delay(20)
    }

    service.archive(middle)
    assertEquals(listOf(first, last), service.sessions.value.map { it.id })
    val archived = service.archived.value.single()
    assertEquals(middle, archived.id)
    assertEquals(listOf(sub), archived.subSessions.map { it.id })
    assertEquals("rate limits", archived.session.displayName)
    assertEquals("main", archived.session.branch)
    assertEquals(home.path, archived.session.repositoryRoot)
    assertEquals(null, registry.terminal(middle), "its terminal ends")
    assertEquals(null, registry.terminal(sub), "and its sub-session's")
    withTimeout(5_000) {
      while (ProcessHandle.of(terminal.pid).map { it.isAlive }.orElse(false)) delay(20)
    }
    assertTrue(git.removeWorktreeCalls.isEmpty())

    assertEquals(LemonadeCodeResult.success(Unarchived(middle)), service.unarchive(middle))
    assertEquals(listOf(first, middle, sub, last), service.sessions.value.map { it.id })
    assertEquals(middle, service.sessions.value.single { it.id == sub }.parentId)
    assertEquals("rate limits", service.sessions.value.single { it.id == middle }.displayName)
    assertTrue(service.archived.value.isEmpty())
    withTimeout(5_000) {
      while (registry.terminal(middle)!!.screen().lines().none { it.trim() == "archived-line" }) delay(20)
    }
    assertEquals(LemonadeCodeResult.failure(SessionError.UnknownSession), service.unarchive(middle), "it comes back once")
  }

  @Test
  fun `archived sessions are saved, stay archived on the next launch, and resume their agent when restored`() = runBlocking<Unit> {
    assertEquals(0, service.restore())
    val kept = service.openTerminal().getOrNull()!!
    val shelved = service.openTerminal().getOrNull()!!
    val shelvedPid = registry.terminal(shelved)!!.pid
    agent = { pid -> "echo resumed-agent".takeIf { pid == shelvedPid } }
    withTimeout(5_000) {
      while (store.saved?.sessions?.firstOrNull { it.id == shelved.value }?.resume == null) delay(50)
    }
    service.archive(shelved)
    withTimeout(5_000) {
      while (store.saved?.archived.isNullOrEmpty()) delay(50)
    }
    assertEquals(listOf(kept.value), store.saved!!.sessions.map { it.id })

    service.shutdown()
    val saved = store.saved!!.archived.single().sessions.single()
    assertEquals(shelved.value, saved.id)
    assertEquals("echo resumed-agent", saved.resume)

    agent = { null }
    val nextRegistry = SessionRegistry()
    val next = newService(nextRegistry)
    assertEquals(1, next.restore(), "only the open session starts")
    assertEquals(null, nextRegistry.terminal(shelved))
    val archived = next.archived.value.single()
    assertEquals(shelved, archived.id)
    assertEquals("echo", archived.session.agent)

    assertTrue(next.unarchive(shelved).isSuccess)
    assertEquals(listOf(kept, shelved), next.sessions.value.map { it.id })
    withTimeout(10_000) {
      while (nextRegistry.terminal(shelved)!!.screen().lines().none { it.trim() == "resumed-agent" }) delay(50)
    }
  }

  @Test
  fun `an archived session whose folder has gone comes back at home, at the top, without its agent`() = runBlocking<Unit> {
    store.saved = SavedSessions(
      archived = listOf(
        SavedArchive(
          listOf(SavedSession(id = "gone", name = "sub-1", workingDirectory = "/no/such/folder", parentId = "closed", placement = "Worktree", resume = "echo should-not-run")),
        ),
      ),
    )
    assertEquals(0, service.restore())
    val id = dev.lemonadecode.domain.session.SessionId("gone")
    assertEquals(LemonadeCodeResult.success(Unarchived(id, mapOf(id to "/no/such/folder"))), service.unarchive(id))
    val session = service.sessions.value.single()
    assertEquals(home.path, session.workingDirectory)
    assertEquals(null, session.parentId, "its parent closed while it was archived")
    assertEquals("~", session.name)
    delay(1_500)
    assertTrue(registry.terminal(id)!!.screen().lines().none { it.trim() == "should-not-run" })
  }

  @Test
  fun `a sub-session archived on its own comes back under its parent, or at the top while the parent is archived too`() = runBlocking<Unit> {
    val parent = service.openTerminal().getOrNull()!!
    val first = service.startSubSession(parent, SubSessionLocation.SameFolder).getOrNull()!!
    val second = service.startSubSession(parent, SubSessionLocation.SameFolder).getOrNull()!!
    service.archive(first)
    assertTrue(service.unarchive(first).isSuccess)
    assertEquals(listOf(parent, first, second), service.sessions.value.map { it.id })
    assertEquals(parent, service.sessions.value.single { it.id == first }.parentId)

    service.archive(first)
    service.archive(parent)
    assertEquals(listOf(parent, first), service.archived.value.map { it.id }, "the most recently archived first")
    assertEquals(listOf(second), service.archived.value.first().subSessions.map { it.id })
    assertTrue(service.unarchive(first).isSuccess)
    assertEquals(null, service.sessions.value.single().parentId)
    assertTrue(service.unarchive(parent).isSuccess)
    assertEquals(listOf(first, parent, second), service.sessions.value.map { it.id })
  }

  @Test
  fun `closing an archived session lets it go for good, and its worktree is still found until then`() = runBlocking<Unit> {
    val main = File(home, "storefront").apply { mkdirs() }
    val folder = File(home, "storefront.worktrees/sub-1").apply { mkdirs() }
    git.linkedWorktrees = mapOf(folder.path to GitWorktree(root = folder.path, branch = "feat/x", mainRoot = main.path, hasChanges = false))
    val id = service.openTerminal(folder.path).getOrNull()!!
    service.archive(id)
    assertTrue(folder.isDirectory, "archiving never deletes a worktree")
    assertEquals(LinkedWorktree(path = folder.path, branch = "feat/x", mainCheckout = main.path, hasChanges = false), service.worktreeOf(id))

    service.close(id)
    assertTrue(service.archived.value.isEmpty())
    assertEquals(null, service.worktreeOf(id))
    assertTrue(git.removeWorktreeCalls.isEmpty())
  }

  private val top = SessionId("top")
  private val sub = SessionId("sub")

  /** `top`, archived at home with its sub-session `sub` in a folder of its own, as a last launch saved them; returns that folder. */
  private fun archivedFamily(): File {
    val folder = File(home, "sub").apply { mkdirs() }
    store.saved = SavedSessions(
      archived = listOf(
        SavedArchive(
          listOf(
            SavedSession(id = "top", name = "~", workingDirectory = home.path),
            SavedSession(id = "sub", name = "sub-1", workingDirectory = folder.path, parentId = "top", placement = "SameFolder"),
          ),
        ),
      ),
    )
    return folder
  }

  @Test
  fun `a sub-session whose shell can't start stays archived, and says so`() = runBlocking<Unit> {
    val folder = archivedFamily()
    assertEquals(0, service.restore())
    beforeStart = { directory -> check(directory != folder.path) { "no pty" } }
    assertEquals(LemonadeCodeResult.success(Unarchived(top, stillArchived = listOf(sub))), service.unarchive(top))
    assertEquals(listOf(top), service.sessions.value.map { it.id })
    assertEquals(listOf(sub), service.archived.value.map { it.id }, "it isn't lost")

    beforeStart = {}
    assertTrue(service.unarchive(sub).isSuccess)
    assertEquals(listOf(top, sub), service.sessions.value.map { it.id })
    assertEquals(top, service.sessions.value.last().parentId, "back under its parent")
  }

  @Test
  fun `cancelling a restore part way keeps every session not yet back archived`() = runBlocking<Unit> {
    val folder = archivedFamily()
    assertEquals(0, service.restore())
    val held = CompletableDeferred<Unit>()
    beforeCheckout = { directory ->
      if (directory == folder.path) {
        held.complete(Unit)
        awaitCancellation()
      }
    }
    val restoring = launch(Dispatchers.Default) { val _ = service.unarchive(top) }
    withTimeout(5_000) { held.await() }
    restoring.cancelAndJoin()
    assertEquals(listOf(top), service.sessions.value.map { it.id })
    assertEquals(listOf(sub), service.archived.value.map { it.id })
  }

  @Test
  fun `while a restore is part way, what's saved still has every session`() = runBlocking<Unit> {
    val folder = archivedFamily()
    assertEquals(0, service.restore())
    val held = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    beforeCheckout = { directory ->
      if (directory == folder.path) {
        held.complete(Unit)
        release.await()
      }
    }
    val restoring = launch(Dispatchers.Default) { val _ = service.unarchive(top) }
    withTimeout(5_000) { held.await() }
    fun archivedIds() = store.saved!!.archived.map { archive -> archive.sessions.map { it.id } }

    // The save made while the app runs, then the one made on quitting.
    withTimeout(5_000) {
      while (store.saved?.sessions?.map { it.id } != listOf("top")) delay(50)
    }
    assertEquals(listOf(listOf("sub")), archivedIds())
    service.shutdown()
    assertEquals(listOf("top"), store.saved!!.sessions.map { it.id })
    assertEquals(listOf(listOf("sub")), archivedIds())
    release.complete(Unit)
    restoring.join()
  }

  @Test
  fun `a session that can't come back stays where it was among the archived`() = runBlocking<Unit> {
    val first = service.openTerminal().getOrNull()!!
    val second = service.openTerminal().getOrNull()!!
    service.archive(first)
    service.archive(second)
    beforeStart = { error("no pty") }
    assertEquals(LemonadeCodeResult.failure(SessionError.LaunchFailed("no pty")), service.unarchive(first))
    assertEquals(listOf(second, first), service.archived.value.map { it.id })
  }

  @Test
  fun `archiving a session while its sub-session is still starting archives that one with it`() = runBlocking<Unit> {
    val parent = service.openTerminal().getOrNull()!!
    val starting = CountDownLatch(1)
    val release = CountDownLatch(1)
    beforeStart = {
      starting.countDown()
      release.await()
    }
    val started = async(Dispatchers.IO) { service.startSubSession(parent, SubSessionLocation.SameFolder) }
    assertTrue(starting.await(5, TimeUnit.SECONDS))
    service.archive(parent)
    release.countDown()
    val child = started.await().getOrNull()!!
    assertEquals(emptyList(), service.sessions.value.map { it.id }, "no sub-session is left running on its own")
    assertEquals(listOf(child), service.archived.value.single().subSessions.map { it.id })
    assertEquals(null, registry.terminal(child))

    beforeStart = {}
    assertTrue(service.unarchive(parent).isSuccess)
    assertEquals(listOf(parent, child), service.sessions.value.map { it.id })
  }

  @Test
  fun `an archive saved twice, or a session saved both open and archived, comes back once`() = runBlocking<Unit> {
    val open = SavedSession(id = "open", name = "~", workingDirectory = home.path)
    val shelved = SavedSession(id = "shelved", name = "~", workingDirectory = home.path)
    store.saved = SavedSessions(
      sessions = listOf(open),
      archived = listOf(SavedArchive(listOf(shelved)), SavedArchive(listOf(shelved)), SavedArchive(listOf(open))),
    )
    assertEquals(1, service.restore())
    assertEquals(listOf(SessionId("open")), service.sessions.value.map { it.id })
    assertEquals(listOf(SessionId("shelved")), service.archived.value.map { it.id })
  }

  @Test
  fun `suggested worktree branches are playful names under worktree`() {
    assertTrue(Regex("worktree/[a-z]+-[a-z]+").matches(service.suggestWorktreeBranch()))
  }

  private fun TerminalSession.screen(): String {
    textBuffer.lock()
    return try {
      textBuffer.getScreenLines()
    } finally {
      textBuffer.unlock()
    }
  }

  /** An interactive zsh without the user's config: it redraws its prompt on every resize, like a login shell does. */
  private object ZshLauncher : ShellLauncher {
    override val shellName: String = "zsh"

    override fun start(directory: String, restoredOutput: List<String>): TerminalSession = TerminalSession(
      TerminalLaunch(command = listOf("/bin/zsh", "-f", "-i"), workingDirectory = directory, restoredOutput = restoredOutput),
    )
  }

  private object ShLauncher : ShellLauncher {
    override val shellName: String = "sh"

    override fun start(directory: String, restoredOutput: List<String>): TerminalSession = TerminalSession(
      TerminalLaunch(command = listOf("/bin/sh"), workingDirectory = directory, environment = mapOf("PS1" to "$ "), restoredOutput = restoredOutput),
    )
  }

  private class MemorySessionStore : SessionStore {
    @Volatile var saved: SavedSessions? = null

    override suspend fun load(): SavedSessions = saved ?: SavedSessions()

    override suspend fun save(saved: SavedSessions) {
      this.saved = saved
    }
  }
}
