package dev.loupe.data.git.fake

import dev.loupe.data.git.GitCheckout
import dev.loupe.data.git.GitDataSource
import dev.loupe.data.git.GitError
import dev.loupe.platform.core.LoupeResult

/** Configurable [GitDataSource]: set the results, read the calls. */
public class FakeGitDataSource : GitDataSource {
  public var checkoutResult: LoupeResult<GitCheckout, GitError> = LoupeResult.failure(GitError.NotARepository)
  public var defaultBranchResult: LoupeResult<String, GitError> = LoupeResult.success("main")
  public var diffResult: LoupeResult<String, GitError> = LoupeResult.success("")
  public var untrackedResult: LoupeResult<List<String>, GitError> = LoupeResult.success(emptyList())
  public var uncommittedResult: LoupeResult<Boolean, GitError> = LoupeResult.success(false)
  public var fileContents: Map<String, String> = emptyMap()
  public var addWorktreeResult: LoupeResult<Unit, GitError> = LoupeResult.success(Unit)

  public val diffCalls: MutableList<Pair<String, String>> = mutableListOf()
  public val worktreeCalls: MutableList<WorktreeCall> = mutableListOf()

  public data class WorktreeCall(val root: String, val path: String, val branch: String, val startPoint: String)

  override suspend fun checkout(directory: String): LoupeResult<GitCheckout, GitError> = checkoutResult

  override suspend fun defaultBranch(repositoryRoot: String): LoupeResult<String, GitError> = defaultBranchResult

  override suspend fun diffAgainst(repositoryRoot: String, baseRef: String): LoupeResult<String, GitError> {
    diffCalls += repositoryRoot to baseRef
    return diffResult
  }

  override suspend fun untrackedFiles(repositoryRoot: String): LoupeResult<List<String>, GitError> = untrackedResult

  override suspend fun hasUncommittedChanges(repositoryRoot: String): LoupeResult<Boolean, GitError> = uncommittedResult

  override suspend fun readTextFile(repositoryRoot: String, path: String, maxBytes: Int): LoupeResult<String?, GitError> = LoupeResult.success(fileContents[path])

  override suspend fun addWorktree(
    repositoryRoot: String,
    path: String,
    branch: String,
    startPoint: String,
  ): LoupeResult<Unit, GitError> {
    worktreeCalls += WorktreeCall(repositoryRoot, path, branch, startPoint)
    return addWorktreeResult
  }
}
