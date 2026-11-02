package dev.lemonadecode.data.git.impl

import dev.lemonadecode.data.git.GitBlob
import dev.lemonadecode.data.git.GitBranchTip
import dev.lemonadecode.data.git.GitCheckout
import dev.lemonadecode.data.git.GitDataSource
import dev.lemonadecode.data.git.GitError
import dev.lemonadecode.platform.architecture.LemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.core.flatMap
import dev.lemonadecode.platform.core.fold
import dev.lemonadecode.platform.core.map
import dev.lemonadecode.platform.core.mapError
import dev.lemonadecode.platform.core.safeSuspendRunCatchingOn
import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.platform.process.ProcessOutput
import dev.lemonadecode.platform.process.ProcessRunner
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.time.Duration.Companion.seconds

@ContributesBinding(AppScope::class)
@Inject
internal class GitCliDataSource(
  private val processRunner: ProcessRunner,
  private val dispatchers: LemonadeCodeCoroutineDispatchers,
  private val files: TextFileReader = TextFileReader(),
) : GitDataSource {
  override suspend fun checkout(directory: String): LemonadeCodeResult<GitCheckout, GitError> = git(directory, "rev-parse", "--show-toplevel").flatMap { top ->
    if (!top.isSuccess) return@flatMap LemonadeCodeResult.failure(GitError.NotARepository)
    val root = top.stdout.trim()
    git(root, "symbolic-ref", "--short", "-q", "HEAD").map { head ->
      GitCheckout(root = root, branch = head.stdout.trim().takeIf { head.isSuccess && it.isNotEmpty() })
    }
  }

  override suspend fun defaultBranch(repositoryRoot: String): LemonadeCodeResult<String, GitError> {
    val originHead = git(repositoryRoot, "symbolic-ref", "--short", "-q", "refs/remotes/origin/HEAD")
      .fold({ if (it.isSuccess) it.stdout.trim() else null }, { null })
    val candidates = listOfNotNull(originHead?.removePrefix("origin/"), originHead, "main", "master")
    for (candidate in candidates.distinct()) {
      val exists = git(repositoryRoot, "rev-parse", "--verify", "--quiet", "$candidate^{commit}")
        .fold({ it.isSuccess }, { false })
      if (exists) return LemonadeCodeResult.success(candidate)
    }
    return LemonadeCodeResult.failure(GitError.RefNotFound(originHead?.removePrefix("origin/") ?: "main"))
  }

  override suspend fun branches(repositoryRoot: String): LemonadeCodeResult<List<String>, GitError> = git(repositoryRoot, "for-each-ref", "--format=%(refname:short)", "refs/heads")
    .flatMap { it.requireSuccess() }
    .map { output -> output.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() } }

  override suspend fun branchTips(repositoryRoot: String): LemonadeCodeResult<Map<String, GitBranchTip>, GitError> = git(repositoryRoot, "for-each-ref", "--format=%(refname:short)%09%(committerdate:unix)%09%(authorname)", "refs/heads")
    .flatMap { it.requireSuccess() }
    .map { output -> parseBranchTips(output.stdout) }

  override suspend fun diffAgainst(repositoryRoot: String, baseRef: String): LemonadeCodeResult<String, GitError> = mergeBase(repositoryRoot, baseRef).flatMap { mergeBase ->
    git(
      repositoryRoot,
      "-c", "core.quotePath=false",
      "diff", "--no-color", "--no-ext-diff", "--find-renames", "--unified=$WholeFile",
      mergeBase, "--",
      timeoutSeconds = 60,
    ).flatMap { it.requireSuccess() }.map { it.stdout }
  }

  override suspend fun mergeBase(repositoryRoot: String, baseRef: String): LemonadeCodeResult<String, GitError> = git(repositoryRoot, "merge-base", baseRef, "HEAD").flatMap { mergeBase ->
    if (mergeBase.isSuccess) LemonadeCodeResult.success(mergeBase.stdout.trim()) else LemonadeCodeResult.failure(GitError.RefNotFound(baseRef))
  }

  override suspend fun readFileAt(
    repositoryRoot: String,
    revision: String,
    path: String,
    maxBytes: Long,
  ): LemonadeCodeResult<GitBlob, GitError> {
    val objectName = "$revision:$path"
    return git(repositoryRoot, "cat-file", "-s", objectName).flatMap { size ->
      val bytes = size.stdout.trim().toLongOrNull()
      when {
        !size.isSuccess || bytes == null -> LemonadeCodeResult.success(GitBlob.Missing)
        bytes > maxBytes -> LemonadeCodeResult.success(GitBlob.TooLarge(bytes))
        else -> processRunner.runForBytes(listOf("git", "cat-file", "blob", objectName), repositoryRoot, 60.seconds)
          .mapError { GitError.Unavailable(it.message ?: "git couldn't be started") }
          .flatMap { blob ->
            if (blob.isSuccess) LemonadeCodeResult.success(GitBlob.Content(blob.stdout)) else LemonadeCodeResult.failure(GitError.CommandFailed(blob.stderr.trim()))
          }
      }
    }
  }

  override suspend fun readWorkingFile(repositoryRoot: String, path: String, maxBytes: Long): LemonadeCodeResult<GitBlob, GitError> = safeSuspendRunCatchingOn(dispatchers.io) { files.readBytes(repositoryRoot, path, maxBytes) }
    .mapError { GitError.Unavailable(it.message ?: "Couldn't read $path") }

  override suspend fun untrackedFiles(repositoryRoot: String): LemonadeCodeResult<List<String>, GitError> = git(repositoryRoot, "ls-files", "--others", "--exclude-standard", "-z")
    .flatMap { it.requireSuccess() }
    .map { output -> output.stdout.split('\u0000').filter { it.isNotEmpty() } }

  override suspend fun hasUncommittedChanges(repositoryRoot: String): LemonadeCodeResult<Boolean, GitError> = git(repositoryRoot, "status", "--porcelain")
    .flatMap { it.requireSuccess() }
    .map { it.stdout.isNotBlank() }

  override suspend fun readTextFile(repositoryRoot: String, path: String, maxBytes: Int): LemonadeCodeResult<String?, GitError> = safeSuspendRunCatchingOn(dispatchers.io) { files.read(repositoryRoot, path, maxBytes) }
    .mapError { GitError.Unavailable(it.message ?: "Couldn't read $path") }

  override suspend fun addWorktree(
    repositoryRoot: String,
    path: String,
    branch: String,
    startPoint: String,
  ): LemonadeCodeResult<Unit, GitError> = git(repositoryRoot, "worktree", "add", "-b", branch, path, startPoint)
    .flatMap { it.requireSuccess() }
    .map { }

  private suspend fun git(
    directory: String,
    vararg arguments: String,
    timeoutSeconds: Int = 15,
  ): LemonadeCodeResult<ProcessOutput, GitError> = processRunner.run(listOf("git", *arguments), directory, timeoutSeconds.seconds)
    .mapError { GitError.Unavailable(it.message ?: "git couldn't be started") }

  private fun ProcessOutput.requireSuccess(): LemonadeCodeResult<ProcessOutput, GitError> = if (isSuccess) LemonadeCodeResult.success(this) else LemonadeCodeResult.failure(GitError.CommandFailed(stderr.trim()))

  private companion object {
    const val WholeFile = 1_000_000
  }
}

/** `for-each-ref` lines of name, commit time in seconds and author, split by tabs. */
internal fun parseBranchTips(output: String): Map<String, GitBranchTip> = output.lineSequence()
  .mapNotNull { line ->
    val parts = line.trim().split('\t')
    val seconds = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
    parts[0] to GitBranchTip(committedAtMillis = seconds * 1000, author = parts.getOrNull(2).orEmpty())
  }
  .toMap()
