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
    git(repo, "branch", "-q", "alpha")
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
  fun `leaves out what others merged when the local main is behind origin's`() = runTest {
    val (remote, clone) = newClone()
    commitFile(remote, "teammate.txt", "a teammate's change\n")
    git(clone, "fetch", "-q")
    git(clone, "switch", "-q", "-c", "my-feature", "origin/main")
    commitFile(clone, "mine.txt", "my change\n")

    assertEquals(listOf("mine.txt"), changedFiles(clone, "main"))
    assertEquals(git(clone, "rev-parse", "origin/main"), dataSource.mergeBase(clone.path, "main").getOrNull())
  }

  @Test
  fun `falls back to origin's branch of the same name when main tracks nothing`() = runTest {
    val (remote, clone) = newClone()
    git(clone, "branch", "-q", "--unset-upstream", "main")
    commitFile(remote, "teammate.txt", "a teammate's change\n")
    git(clone, "fetch", "-q")
    git(clone, "switch", "-q", "-c", "my-feature", "origin/main")
    commitFile(clone, "mine.txt", "my change\n")

    assertEquals(listOf("mine.txt"), changedFiles(clone, "main"))
  }

  @Test
  fun `counts the local main's unpushed commits as part of the base`() = runTest {
    val (_, clone) = newClone()
    commitFile(clone, "unpushed.txt", "on main, not pushed\n")
    git(clone, "switch", "-q", "-c", "my-feature")
    commitFile(clone, "mine.txt", "my change\n")

    assertEquals(listOf("mine.txt"), changedFiles(clone, "main"))
    assertEquals(git(clone, "rev-parse", "main"), dataSource.mergeBase(clone.path, "main").getOrNull())
  }

  @Test
  fun `diffs against where the branch split off from main when there's no remote`() = runTest {
    val repo = newRepository()
    val splitPoint = git(repo, "rev-parse", "main")
    commitFile(repo, "mine.txt", "my change\n")
    git(repo, "switch", "-q", "main")
    commitFile(repo, "later.txt", "on main after the split\n")
    git(repo, "switch", "-q", "feature")

    assertEquals(listOf("mine.txt"), changedFiles(repo, "main"))
    assertEquals(splitPoint, dataSource.mergeBase(repo.path, "main").getOrNull())
  }

  @Test
  fun `diffs only what isn't committed against HEAD`() = runTest {
    val (_, clone) = newClone()
    git(clone, "switch", "-q", "-c", "my-feature", "--track", "origin/main")
    commitFile(clone, "mine.txt", "my change\n")
    File(clone, "app.ts").writeText("one\ntwo-changed\nthree\nfour\n")

    assertEquals(listOf("app.ts"), changedFiles(clone, "HEAD"))
    assertEquals(git(clone, "rev-parse", "HEAD"), dataSource.mergeBase(clone.path, "HEAD").getOrNull())
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

  /** The paths [diffAgainst][GitCliDataSource.diffAgainst] shows for [baseRef], by name. */
  private suspend fun changedFiles(repo: File, baseRef: String): List<String> = dataSource.diffAgainst(repo.path, baseRef).getOrNull().orEmpty()
    .lineSequence()
    .filter { it.startsWith("diff --git ") }
    .map { it.substringAfterLast(" b/") }
    .sorted()
    .toList()

  private fun commitAll(repo: File, message: String) {
    git(repo, "add", ".")
    git(repo, "commit", "-q", "-m", message)
  }

  private fun commitFile(repo: File, path: String, text: String) {
    File(repo, path).writeText(text)
    commitAll(repo, path)
  }

  private fun newRepository(): File {
    val repo = Files.createTempDirectory("lemonade-code-git").toFile()
    git(repo, "init", "-q", "-b", "main")
    configure(repo)
    File(repo, "app.ts").writeText("one\ntwo\nthree\nfour\n")
    commitAll(repo, "init")
    git(repo, "checkout", "-q", "-b", "feature")
    return repo
  }

  /** A repository on `main` and a clone of it, whose `main` tracks `origin/main`. */
  private fun newClone(): Pair<File, File> {
    val remote = newRepository()
    git(remote, "switch", "-q", "main")
    val clone = Files.createTempDirectory("lemonade-code-clone").toFile()
    git(clone, "clone", "-q", remote.path, ".")
    configure(clone)
    return remote to clone
  }

  private fun configure(repo: File) {
    git(repo, "config", "user.email", "test@lemonade-code.dev")
    git(repo, "config", "user.name", "Lemonade Code Test")
    git(repo, "config", "commit.gpgsign", "false")
  }

  /** Runs git in [directory] and returns what it printed, trimmed. */
  @IgnorableReturnValue
  private fun git(directory: File, vararg args: String): String {
    val process = ProcessBuilder("git", *args).directory(directory).redirectErrorStream(true).isolated().start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { output }
    return output.trim()
  }

  private object BlockingProcessRunner : ProcessRunner {
    override suspend fun run(command: List<String>, workingDirectory: String, timeout: Duration): LemonadeCodeResult<ProcessOutput, Throwable> {
      val process = ProcessBuilder(command).directory(File(workingDirectory)).isolated().start()
      val out = process.inputStream.bufferedReader().readText()
      val err = process.errorStream.bufferedReader().readText()
      return LemonadeCodeResult.success(ProcessOutput(process.waitFor(), out, err))
    }

    override suspend fun runForBytes(command: List<String>, workingDirectory: String, timeout: Duration): LemonadeCodeResult<BinaryProcessOutput, Throwable> {
      val process = ProcessBuilder(command).directory(File(workingDirectory)).isolated().start()
      val out = process.inputStream.readBytes()
      val err = process.errorStream.bufferedReader().readText()
      return LemonadeCodeResult.success(BinaryProcessOutput(process.waitFor(), out, err))
    }
  }
}

private val emptyGitConfig: File by lazy { File.createTempFile("lemonade-code-gitconfig", "").apply { deleteOnExit() } }

/** git without the user's or the system's settings, so neither can change what the tests see. */
private fun ProcessBuilder.isolated(): ProcessBuilder = apply {
  environment()["GIT_CONFIG_NOSYSTEM"] = "1"
  environment()["GIT_CONFIG_GLOBAL"] = emptyGitConfig.path
}
