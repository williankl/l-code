package dev.lemonadecode.data.git.impl

import dev.lemonadecode.data.git.GitBlob
import dev.lemonadecode.data.git.GitError
import dev.lemonadecode.platform.architecture.testing.FakeLemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.core.getOrNull
import dev.lemonadecode.platform.process.BinaryProcessOutput
import dev.lemonadecode.platform.process.ProcessOutput
import dev.lemonadecode.platform.process.ProcessRunner
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

class GitCliDataSourceTest {
  private val dataSource = GitCliDataSource(BlockingProcessRunner, FakeLemonadeCodeCoroutineDispatchers())

  @Test
  fun `finds the checkout root and current branch`() = runTest {
    val repo = newRepository()
    val checkout = dataSource.checkout(File(repo, "src").apply { mkdirs() }.path).getOrNull()
    assertEquals(repo.canonicalPath, checkout?.root?.let { File(it).canonicalPath })
    assertEquals("feature", checkout?.branch)
  }

  @Test
  fun `lists the local branches by name`() = runTest {
    val repo = newRepository()
    val process = ProcessBuilder("git", "branch", "-q", "alpha").directory(repo).start()
    check(process.waitFor() == 0)
    assertEquals(listOf("alpha", "feature", "main"), dataSource.branches(repo.path).getOrNull())
  }

  @Test
  fun `tells when each branch last got a commit and who made it`() = runTest {
    val repo = newRepository()
    val tips = dataSource.branchTips(repo.path).getOrNull().orEmpty()
    assertEquals(setOf("feature", "main"), tips.keys)
    val main = tips.getValue("main")
    assertEquals("Lemonade Code Test", main.author)
    assertTrue(main.committedAtMillis > 0 && main.committedAtMillis % 1000 == 0L)
  }

  @Test
  fun `reports a folder outside git`() = runTest {
    val folder = Files.createTempDirectory("lemonade-code-plain").toFile()
    assertEquals(LemonadeCodeResult.failure(GitError.NotARepository), dataSource.checkout(folder.path))
  }

  @Test
  fun `diffs the working tree against the merge base with every line as context`() = runTest {
    val repo = newRepository()
    File(repo, "app.ts").writeText("one\ntwo-changed\nthree\nfour\n")
    val diff = dataSource.diffAgainst(repo.path, "main").getOrNull().orEmpty()
    assertTrue("-two" in diff && "+two-changed" in diff, diff)
    assertTrue(" four" in diff, "whole file is included as context")
    assertEquals("main", dataSource.defaultBranch(repo.path).getOrNull())
  }

  @Test
  fun `lists untracked files and uncommitted changes`() = runTest {
    val repo = newRepository()
    File(repo, "new.md").writeText("# New\n")
    assertEquals(listOf("new.md"), dataSource.untrackedFiles(repo.path).getOrNull())
    assertEquals(true, dataSource.hasUncommittedChanges(repo.path).getOrNull())
    assertEquals("# New\n", dataSource.readTextFile(repo.path, "new.md", 1024).getOrNull())
  }

  @Test
  fun `reads binary files at the merge base and on disk byte for byte`() = runTest {
    val repo = newRepository()
    val committed = ByteArray(256) { it.toByte() }
    File(repo, "logo.png").writeBytes(committed)
    commitAll(repo, "logo")
    val changed = committed.reversedArray()
    File(repo, "logo.png").writeBytes(changed)
    val head = dataSource.mergeBase(repo.path, "feature").getOrNull().orEmpty()

    val before = assertIs<GitBlob.Content>(dataSource.readFileAt(repo.path, head, "logo.png", 1024).getOrNull())
    assertContentEquals(committed, before.bytes)
    val after = assertIs<GitBlob.Content>(dataSource.readWorkingFile(repo.path, "logo.png", 1024).getOrNull())
    assertContentEquals(changed, after.bytes)
  }

  @Test
  fun `reports files that are missing or too large to read`() = runTest {
    val repo = newRepository()
    val base = dataSource.mergeBase(repo.path, "main").getOrNull().orEmpty()
    assertEquals(GitBlob.Missing, dataSource.readFileAt(repo.path, base, "nope.png", 1024).getOrNull())
    assertEquals(GitBlob.Missing, dataSource.readWorkingFile(repo.path, "nope.png", 1024).getOrNull())
    assertEquals(GitBlob.TooLarge(19), dataSource.readFileAt(repo.path, base, "app.ts", 4).getOrNull())
    assertEquals(GitBlob.TooLarge(19), dataSource.readWorkingFile(repo.path, "app.ts", 4).getOrNull())
  }

  @Test
  fun `creates a worktree on a new branch`() = runTest {
    val repo = newRepository()
    val path = File(repo.parentFile, "${repo.name}.worktrees/sub-1").path
    assertTrue(dataSource.addWorktree(repo.path, path, "feature-sub-1", "feature").isSuccess)
    assertEquals("feature-sub-1", dataSource.checkout(path).getOrNull()?.branch)
  }

  private fun commitAll(repo: File, message: String) {
    listOf(listOf("git", "add", "."), listOf("git", "commit", "-q", "-m", message)).forEach { args ->
      val process = ProcessBuilder(args).directory(repo).redirectErrorStream(true).start()
      check(process.waitFor() == 0) { process.inputStream.bufferedReader().readText() }
    }
  }

  private fun newRepository(): File {
    val repo = Files.createTempDirectory("lemonade-code-git").toFile()
    fun run(vararg args: String) {
      val process = ProcessBuilder(*args).directory(repo).redirectErrorStream(true).start()
      check(process.waitFor() == 0) { process.inputStream.bufferedReader().readText() }
    }
    run("git", "init", "-q", "-b", "main")
    run("git", "config", "user.email", "test@lemonade-code.dev")
    run("git", "config", "user.name", "Lemonade Code Test")
    run("git", "config", "commit.gpgsign", "false")
    File(repo, "app.ts").writeText("one\ntwo\nthree\nfour\n")
    run("git", "add", ".")
    run("git", "commit", "-q", "-m", "init")
    run("git", "checkout", "-q", "-b", "feature")
    return repo
  }

  private object BlockingProcessRunner : ProcessRunner {
    override suspend fun run(command: List<String>, workingDirectory: String, timeout: Duration): LemonadeCodeResult<ProcessOutput, Throwable> {
      val process = ProcessBuilder(command).directory(File(workingDirectory)).start()
      val out = process.inputStream.bufferedReader().readText()
      val err = process.errorStream.bufferedReader().readText()
      return LemonadeCodeResult.success(ProcessOutput(process.waitFor(), out, err))
    }

    override suspend fun runForBytes(command: List<String>, workingDirectory: String, timeout: Duration): LemonadeCodeResult<BinaryProcessOutput, Throwable> {
      val process = ProcessBuilder(command).directory(File(workingDirectory)).start()
      val out = process.inputStream.readBytes()
      val err = process.errorStream.bufferedReader().readText()
      return LemonadeCodeResult.success(BinaryProcessOutput(process.waitFor(), out, err))
    }
  }
}
