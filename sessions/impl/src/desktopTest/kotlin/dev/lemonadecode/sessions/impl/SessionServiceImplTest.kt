package dev.lemonadecode.sessions.impl

import dev.lemonadecode.data.git.GitCheckout
import dev.lemonadecode.data.git.GitError
import dev.lemonadecode.data.git.GitWorktree
import dev.lemonadecode.data.git.fake.FakeGitDataSource
import dev.lemonadecode.domain.session.LinkedWorktree
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
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

  /** A service as one launch of Lemonade Code sees it; a second one over the same [store] is the next launch. */
  private fun newService(registry: SessionRegistry) = SessionServiceImpl(
    git = git,
    launcher = object : ShellLauncher {
      override val shellName: String get() = shell.shellName

      override fun start(directory: String, restoredOutput: List<String>): TerminalSession = shell.start(directory, restoredOutput)
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
