package dev.loupe.data.git.impl

import dev.loupe.data.git.GitCheckout
import dev.loupe.data.git.GitDataSource
import dev.loupe.data.git.GitError
import dev.loupe.platform.architecture.LoupeCoroutineDispatchers
import dev.loupe.platform.core.LoupeResult
import dev.loupe.platform.core.flatMap
import dev.loupe.platform.core.fold
import dev.loupe.platform.core.map
import dev.loupe.platform.core.mapError
import dev.loupe.platform.core.safeSuspendRunCatchingOn
import dev.loupe.platform.di.AppScope
import dev.loupe.platform.process.ProcessOutput
import dev.loupe.platform.process.ProcessRunner
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.time.Duration.Companion.seconds

@ContributesBinding(AppScope::class)
@Inject
internal class GitCliDataSource(
  private val processRunner: ProcessRunner,
  private val dispatchers: LoupeCoroutineDispatchers,
  private val files: TextFileReader = TextFileReader(),
) : GitDataSource {
  override suspend fun checkout(directory: String): LoupeResult<GitCheckout, GitError> = git(directory, "rev-parse", "--show-toplevel").flatMap { top ->
    if (!top.isSuccess) return@flatMap LoupeResult.failure(GitError.NotARepository)
    val root = top.stdout.trim()
    git(root, "symbolic-ref", "--short", "-q", "HEAD").map { head ->
      GitCheckout(root = root, branch = head.stdout.trim().takeIf { head.isSuccess && it.isNotEmpty() })
    }
  }

  override suspend fun defaultBranch(repositoryRoot: String): LoupeResult<String, GitError> {
    val originHead = git(repositoryRoot, "symbolic-ref", "--short", "-q", "refs/remotes/origin/HEAD")
      .fold({ if (it.isSuccess) it.stdout.trim() else null }, { null })
    val candidates = listOfNotNull(originHead?.removePrefix("origin/"), originHead, "main", "master")
    for (candidate in candidates.distinct()) {
      val exists = git(repositoryRoot, "rev-parse", "--verify", "--quiet", "$candidate^{commit}")
        .fold({ it.isSuccess }, { false })
      if (exists) return LoupeResult.success(candidate)
    }
    return LoupeResult.failure(GitError.RefNotFound(originHead?.removePrefix("origin/") ?: "main"))
  }

  override suspend fun diffAgainst(repositoryRoot: String, baseRef: String): LoupeResult<String, GitError> = git(repositoryRoot, "merge-base", baseRef, "HEAD").flatMap { mergeBase ->
    if (!mergeBase.isSuccess) return@flatMap LoupeResult.failure(GitError.RefNotFound(baseRef))
    git(
      repositoryRoot,
      "-c", "core.quotePath=false",
      "diff", "--no-color", "--no-ext-diff", "--find-renames", "--unified=$WholeFile",
      mergeBase.stdout.trim(), "--",
      timeoutSeconds = 60,
    ).flatMap { it.requireSuccess() }.map { it.stdout }
  }

  override suspend fun untrackedFiles(repositoryRoot: String): LoupeResult<List<String>, GitError> = git(repositoryRoot, "ls-files", "--others", "--exclude-standard", "-z")
    .flatMap { it.requireSuccess() }
    .map { output -> output.stdout.split('\u0000').filter { it.isNotEmpty() } }

  override suspend fun hasUncommittedChanges(repositoryRoot: String): LoupeResult<Boolean, GitError> = git(repositoryRoot, "status", "--porcelain")
    .flatMap { it.requireSuccess() }
    .map { it.stdout.isNotBlank() }

  override suspend fun readTextFile(repositoryRoot: String, path: String, maxBytes: Int): LoupeResult<String?, GitError> = safeSuspendRunCatchingOn(dispatchers.io) { files.read(repositoryRoot, path, maxBytes) }
    .mapError { GitError.Unavailable(it.message ?: "Couldn't read $path") }

  override suspend fun addWorktree(
    repositoryRoot: String,
    path: String,
    branch: String,
    startPoint: String,
  ): LoupeResult<Unit, GitError> = git(repositoryRoot, "worktree", "add", "-b", branch, path, startPoint)
    .flatMap { it.requireSuccess() }
    .map { }

  private suspend fun git(
    directory: String,
    vararg arguments: String,
    timeoutSeconds: Int = 15,
  ): LoupeResult<ProcessOutput, GitError> = processRunner.run(listOf("git", *arguments), directory, timeoutSeconds.seconds)
    .mapError { GitError.Unavailable(it.message ?: "git couldn't be started") }

  private fun ProcessOutput.requireSuccess(): LoupeResult<ProcessOutput, GitError> = if (isSuccess) LoupeResult.success(this) else LoupeResult.failure(GitError.CommandFailed(stderr.trim()))

  private companion object {
    const val WholeFile = 1_000_000
  }
}
