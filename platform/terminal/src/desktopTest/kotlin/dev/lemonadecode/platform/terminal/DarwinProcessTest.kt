package dev.lemonadecode.platform.terminal

import dev.lemonadecode.platform.terminal.DarwinProcess.DirectoryRead
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The kernel read the way `ps` and `lsof` read it, against `ps` and `lsof` themselves
 * ([ProcessReaderOracles]), on live processes. macOS only: elsewhere [DarwinProcess] isn't used.
 */
class DarwinProcessTest {
  private val mac = System.getProperty("os.name").orEmpty().startsWith("Mac")
  private val root = Files.createTempDirectory("lemonade-code-darwin").toFile().canonicalFile
  private val started = ConcurrentLinkedQueue<Process>()

  @AfterTest
  fun tearDown() {
    started.forEach { process ->
      process.descendants().forEach { it.destroyForcibly() }
      process.destroyForcibly()
    }
    root.deleteRecursively()
  }

  /** `sleep` running in [folder], started through `sh` with [prefix] first, as its own process. */
  private fun sleeper(folder: File, prefix: String = ""): Long {
    val process = ProcessBuilder("/bin/sh", "-c", "${prefix}exec sleep 60").directory(folder).start().also(started::add)
    // Once `sh` has become `sleep`, the folder is the one it will keep.
    val until = System.nanoTime() + 10_000_000_000
    while (!process.toHandle().info().command().orElse("").endsWith("/sleep")) {
      check(System.nanoTime() < until) { "sh never became sleep" }
      Thread.sleep(10)
    }
    return process.pid()
  }

  /** The folder read three ways: the reader the app uses now, the kernel alone, and `lsof`, as before. */
  private data class FolderReads(val reader: String?, val kernel: DirectoryRead, val lsof: String?) {
    /** The folder the kernel named, when it named one. */
    val kernelFolder: String? get() = (kernel as? DirectoryRead.Folder)?.path
  }

  private fun folderReads(pid: Long) = FolderReads(ProcessDirectory.of(pid), DarwinProcess.currentDirectory(pid), ProcessReaderOracles.directoryOf(pid))

  private fun assertSameGroups(pid: Long, what: String) {
    assertEquals(ProcessReaderOracles.terminalGroupOf(pid), ForegroundProcess.groupOf(pid), "terminal group of $what")
    assertEquals(ProcessReaderOracles.processGroupOf(pid), ForegroundProcess.processGroupOf(pid), "process group of $what")
    // Where the kernel answers, it answers what `ps` prints; where it doesn't, `ps` is asked.
    DarwinProcess.groups(pid)?.let { groups ->
      assertEquals(ProcessReaderOracles.terminalGroupOf(pid), groups.terminalGroup, "kernel's terminal group of $what")
      assertEquals(ProcessReaderOracles.processGroupOf(pid), groups.group, "kernel's process group of $what")
    }
  }

  @Test
  fun `a folder named in printable ASCII is read from the kernel, as lsof names it`() {
    if (!mac) return
    val names = listOf("plain", "with spaces", "quotes \"double\" and 'single'", "punctuation !#\$%&()*+,-.;<=>?@[]^_`{|}~", " leading and trailing ", "-dash")
    for (name in names) {
      val folder = File(root, name).apply { mkdirs() }
      val reads = folderReads(sleeper(folder))
      assertEquals(folder.path, reads.lsof, "lsof in [$name]")
      assertEquals(DirectoryRead.Folder(folder.path), reads.kernel, "the kernel in [$name]")
      assertEquals(reads.lsof, reads.reader, "the reader in [$name]")
    }
  }

  @Test
  fun `a folder lsof would print escaped is left to lsof`() {
    if (!mac) return
    val names = listOf("café ünï", "日本語", "lemon 🍋", "new\nline", "tab\tstop", "back\\slash", "bell\u0007", "del\u007f")
    for (name in names) {
      val folder = File(root, name).apply { mkdirs() }
      val reads = folderReads(sleeper(folder))
      assertEquals(DirectoryRead.AskLsof, reads.kernel, "the kernel leaves [$name] to lsof")
      assertEquals(reads.lsof, reads.reader, "the reader in [$name]")
      assertNotNull(reads.lsof, "lsof names [$name]")
    }
  }

  @Test
  fun `a folder reached through a symbolic link, or tmp, reads as its real path`() {
    if (!mac) return
    val real = File(root, "real").apply { mkdirs() }
    val link = File(root, "link")
    Files.createSymbolicLink(link.toPath(), real.toPath())
    for (folder in listOf(link, File("/tmp"), File("/var/tmp"))) {
      val reads = folderReads(sleeper(folder))
      assertEquals<DirectoryRead>(DirectoryRead.Folder(checkNotNull(reads.lsof)), reads.kernel, "the kernel in $folder")
      assertEquals(reads.lsof, reads.reader, "the reader in $folder")
    }
    assertEquals("/private/tmp", ProcessDirectory.of(sleeper(File("/tmp"))))
  }

  @Test
  fun `a folder deleted under the process reads as lsof reads it`() {
    if (!mac) return
    val folder = File(root, "doomed").apply { mkdirs() }
    val pid = sleeper(folder)
    assertTrue(folder.delete())
    val reads = folderReads(pid)
    assertEquals(reads.lsof, reads.reader)
    assertTrue(reads.kernel != DirectoryRead.NoProcess)
    reads.kernelFolder?.let { assertEquals(reads.lsof, it) }
  }

  @Test
  fun `a folder whose path is longer than the kernel names reads as lsof reads it`() {
    if (!mac) return
    // Made and entered a part at a time: no single path given to the system is too long.
    val part = "p".repeat(200)
    val parts = 6
    check(ProcessBuilder("/bin/sh", "-c", (1..parts).joinToString(" && ") { "mkdir $part && cd $part" }).directory(root).start().waitFor() == 0)
    val pid = sleeper(root, prefix = (1..parts).joinToString("") { "cd $part && " })
    val reads = folderReads(pid)
    assertEquals(reads.lsof, reads.reader)
    assertTrue(reads.kernel != DirectoryRead.NoProcess)
    reads.kernelFolder?.let { assertEquals(reads.lsof, it) }
  }

  @Test
  fun `a process's groups are read from the kernel, as ps prints them`() {
    if (!mac) return
    val pid = sleeper(root)
    assertNotNull(DarwinProcess.groups(pid), "the kernel answers for a process of ours")
    assertSameGroups(pid, "a process of ours")
    assertSameGroups(ProcessHandle.current().pid(), "this process")
  }

  @Test
  fun `a process's parent is read from the kernel, as ProcessHandle tells it`() {
    if (!mac) return
    val self = ProcessHandle.current()
    assertEquals(ProcessParent.Parent(self.pid()), processParent(sleeper(root)))
    assertEquals(ProcessParent.Parent(self.parent().get().pid()), processParent(self.pid()))
    assertEquals(ProcessParent.Parent(0L), processParent(1L), "launchd's parent is the kernel")
    val ended = ProcessBuilder("true").start().also { it.waitFor() }.pid()
    assertEquals(ProcessParent.NoProcess, processParent(ended))
    assertEquals(ProcessParent.Unknown, processParent(0L))
    assertEquals(ProcessParent.Unknown, processParent(Int.MAX_VALUE + 1L))
  }

  @Test
  fun `a shell's foreground group reads as ps prints it, at its prompt and while a program runs`() = runBlocking<Unit> {
    if (!mac) return@runBlocking
    TerminalSession(TerminalLaunch(command = listOf("/bin/sh"), workingDirectory = root.path, environment = mapOf("PS1" to "$ "))).use { session ->
      withTimeout(5_000) {
        while (ForegroundProcess.groupOf(session.pid) != session.pid) delay(20)
      }
      assertSameGroups(session.pid, "the shell at its prompt")
      session.send("sleep 30\r")
      val program = withTimeout(5_000) {
        var found: Long? = null
        while (found == null) {
          delay(20)
          found = ProcessHandle.of(session.pid).get().children().findFirst().map { it.pid() }.orElse(null)
        }
        found
      }
      withTimeout(5_000) {
        while (ForegroundProcess.groupOf(session.pid) == session.pid) delay(20)
      }
      assertSameGroups(session.pid, "the shell running a program")
      assertSameGroups(program, "the program")
      assertEquals(TerminalForeground(group = program, directory = root.path), session.foreground())
      session.send("\u0003")
    }
  }

  @Test
  fun `a process that has ended, a zombie, one of another user's and a pid no one has read as before`() {
    if (!mac) return
    val ended = ProcessBuilder("true").start().also { it.waitFor() }.pid()
    // `sleep 0.1` ends while its parent, become `sleep 60`, never waits for it.
    val parent = ProcessBuilder("/bin/sh", "-c", "sleep 0.1 & echo \$!; exec sleep 60").start().also(started::add)
    val zombie = parent.inputStream.bufferedReader().readLine().trim().toLong()
    Thread.sleep(600)
    val unused = (99_000L downTo 2L).first { !ProcessHandle.of(it).isPresent }
    // What the kernel says: nothing of the gone, the zombie and the unused, as `lsof` passes over
    // them; another user's is `lsof`'s to answer, as before.
    val cases = mapOf(
      "an ended process" to (ended to DirectoryRead.NoProcess),
      "a zombie" to (zombie to DirectoryRead.NoProcess),
      "launchd, root's" to (1L to DirectoryRead.AskLsof),
      "a pid no one has" to (unused to DirectoryRead.NoProcess),
    )
    for ((what, case) in cases) {
      val (pid, kernel) = case
      assertSameGroups(pid, what)
      val reads = folderReads(pid)
      assertEquals(kernel, reads.kernel, "the kernel on $what")
      assertNull(reads.lsof, "lsof on $what")
      assertEquals(reads.lsof, reads.reader, "the folder of $what")
    }
    assertNull(DarwinProcess.groups(unused))
    assertNull(DarwinProcess.groups(0L))
    assertNull(DarwinProcess.groups(-1L))
    assertNull(DarwinProcess.groups(Int.MAX_VALUE + 1L))
    assertEquals(DirectoryRead.AskLsof, DarwinProcess.currentDirectory(0L))
    assertEquals(DirectoryRead.AskLsof, DarwinProcess.currentDirectory(Long.MAX_VALUE))
  }

  @Test
  fun `a pipeline in front whose first process has ended reads as before, in zsh and in bash`() = runBlocking<Unit> {
    if (!mac) return@runBlocking
    val shells = listOf(listOf("/bin/zsh", "-f", "-i"), listOf("/bin/bash", "--noprofile", "--norc", "-i")).filter { File(it.first()).exists() }
    check(shells.isNotEmpty())
    for (command in shells) {
      TerminalSession(TerminalLaunch(command = command, workingDirectory = root.path, environment = mapOf("PS1" to "$ "))).use { session ->
        withTimeout(10_000) {
          while (ForegroundProcess.groupOf(session.pid) != session.pid) delay(20)
        }
        // `true` leads the pipeline's group and ends at once; `sleep` keeps the group, and the terminal, held.
        session.send("/usr/bin/true | sleep 30\r")
        val leader = withTimeout(10_000) {
          var group: Long? = null
          while (group == null) {
            delay(20)
            group = ForegroundProcess.groupOf(session.pid)?.takeIf { it != session.pid && !ProcessHandle.of(it).isPresent }
          }
          group
        }
        val member = ProcessHandle.of(session.pid).get().children().findFirst().get().pid()
        assertEquals(leader, ProcessReaderOracles.processGroupOf(member), "${command.first()}: sleep is in the ended leader's group")
        assertSameGroups(session.pid, "${command.first()} running the pipeline")
        assertEquals(DirectoryRead.NoProcess, DarwinProcess.currentDirectory(leader), command.first())
        assertNull(ProcessReaderOracles.directoryOf(leader), command.first())
        assertNull(ProcessDirectory.of(leader), command.first())
        // What the session sees, against the same read through `ps` and `lsof`: the shell's own folder.
        val before = TerminalForeground(
          group = ProcessReaderOracles.terminalGroupOf(session.pid),
          directory = ProcessReaderOracles.directoryOf(leader) ?: ProcessReaderOracles.directoryOf(session.pid),
        )
        assertEquals(TerminalForeground(group = leader, directory = root.path), before, command.first())
        assertEquals(before, session.foreground(), command.first())
        session.send("\u0003")
      }
    }
  }

  @Test
  fun `many reads while processes start and end answer for the process asked about, or not at all`() {
    if (!mac) return
    val steady = File(root, "steady").apply { mkdirs() }
    val steadyPid = sleeper(steady)
    val steadyGroups = DarwinProcess.groups(steadyPid)
    val running = AtomicBoolean(true)
    val reads = AtomicLong()
    val answered = AtomicLong()
    val failures = ConcurrentLinkedQueue<String>()
    val recent = ConcurrentLinkedQueue<Long>()
    val churn = (1..3).map {
      thread(name = "churn-$it") {
        while (running.get()) {
          val process = ProcessBuilder("/bin/sh", "-c", "sleep 0.0\$((RANDOM % 5))").directory(steady).start()
          recent.add(process.pid())
          while (recent.size > 32) recent.poll()
          if (Random.nextBoolean()) process.waitFor() else process.destroyForcibly()
        }
      }
    }
    val readers = (1..4).map {
      thread(name = "reader-$it") {
        while (running.get()) {
          val pid = when (Random.nextInt(4)) {
            0 -> recent.randomOrNull() ?: steadyPid
            1 -> Random.nextLong(1, 100_000)
            2 -> listOf(0L, -1L, Int.MAX_VALUE.toLong(), Int.MAX_VALUE + 1L, Long.MAX_VALUE).random()
            else -> steadyPid
          }
          val folder = DarwinProcess.currentDirectory(pid)
          val groups = DarwinProcess.groups(pid)
          if (folder is DirectoryRead.Folder || groups != null) answered.incrementAndGet()
          if (pid == steadyPid && (folder != DirectoryRead.Folder(steady.path) || groups != steadyGroups)) failures += "steady: $folder $groups"
          if (folder is DirectoryRead.Folder && !folder.path.startsWith("/")) failures += "folder of $pid: $folder"
          reads.incrementAndGet()
        }
      }
    }
    Thread.sleep(8_000)
    running.set(false)
    (churn + readers).forEach { it.join() }
    assertTrue(failures.isEmpty(), failures.take(5).joinToString())
    assertTrue(reads.get() > 10_000, "reads: ${reads.get()}")
    assertTrue(answered.get() > 0)
  }

  private fun <T> ConcurrentLinkedQueue<T>.randomOrNull(): T? = toList().randomOrNull()
}
