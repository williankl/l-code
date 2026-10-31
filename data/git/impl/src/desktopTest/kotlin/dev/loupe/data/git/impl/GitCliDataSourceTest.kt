package dev.loupe.data.git.impl

import dev.loupe.data.git.GitError
import dev.loupe.platform.architecture.testing.FakeLoupeCoroutineDispatchers
import dev.loupe.platform.core.LoupeResult
import dev.loupe.platform.core.getOrNull
import dev.loupe.platform.process.ProcessOutput
import dev.loupe.platform.process.ProcessRunner
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

class GitCliDataSourceTest {
  private val dataSource = GitCliDataSource(BlockingProcessRunner, FakeLoupeCoroutineDispatchers())

  @Test
  fun `finds the checkout root and current branch`() = runTest {
    val repo = newRepository()
    val checkout = dataSource.checkout(File(repo, "src").apply { mkdirs() }.path).getOrNull()
    assertEquals(repo.canonicalPath, checkout?.root?.let { File(it).canonicalPath })
    assertEquals("feature", checkout?.branch)
  }

  @Test
  fun `reports a folder outside git`() = runTest {
    val folder = Files.createTempDirectory("loupe-plain").toFile()
    assertEquals(LoupeResult.failure(GitError.NotARepository), dataSource.checkout(folder.path))
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
  fun `creates a worktree on a new branch`() = runTest {
    val repo = newRepository()
    val path = File(repo.parentFile, "${repo.name}.worktrees/sub-1").path
    assertTrue(dataSource.addWorktree(repo.path, path, "feature-sub-1", "feature").isSuccess)
    assertEquals("feature-sub-1", dataSource.checkout(path).getOrNull()?.branch)
  }

  private fun newRepository(): File {
    val repo = Files.createTempDirectory("loupe-git").toFile()
    fun run(vararg args: String) {
      val process = ProcessBuilder(*args).directory(repo).redirectErrorStream(true).start()
      check(process.waitFor() == 0) { process.inputStream.bufferedReader().readText() }
    }
    run("git", "init", "-q", "-b", "main")
    run("git", "config", "user.email", "test@loupe.dev")
    run("git", "config", "user.name", "Loupe Test")
    run("git", "config", "commit.gpgsign", "false")
    File(repo, "app.ts").writeText("one\ntwo\nthree\nfour\n")
    run("git", "add", ".")
    run("git", "commit", "-q", "-m", "init")
    run("git", "checkout", "-q", "-b", "feature")
    return repo
  }

  private object BlockingProcessRunner : ProcessRunner {
    override suspend fun run(command: List<String>, workingDirectory: String, timeout: Duration): LoupeResult<ProcessOutput, Throwable> {
      val process = ProcessBuilder(command).directory(File(workingDirectory)).start()
      val out = process.inputStream.bufferedReader().readText()
      val err = process.errorStream.bufferedReader().readText()
      return LoupeResult.success(ProcessOutput(process.waitFor(), out, err))
    }
  }
}
