package dev.lemonadecode.data.git.fake

import dev.lemonadecode.data.git.GitBlob
import dev.lemonadecode.data.git.GitBranchTip
import dev.lemonadecode.data.git.GitCheckout
import dev.lemonadecode.data.git.GitDataSource
import dev.lemonadecode.data.git.GitError
import dev.lemonadecode.platform.core.LemonadeCodeResult

/** Configurable [GitDataSource]: set the results, read the calls. */
public class FakeGitDataSource : GitDataSource {
  public var checkoutResult: LemonadeCodeResult<GitCheckout, GitError> = LemonadeCodeResult.failure(GitError.NotARepository)
  public var defaultBranchResult: LemonadeCodeResult<String, GitError> = LemonadeCodeResult.success("main")
  public var branchesResult: LemonadeCodeResult<List<String>, GitError> = LemonadeCodeResult.success(listOf("main"))
  public var branchTipsResult: LemonadeCodeResult<Map<String, GitBranchTip>, GitError> = LemonadeCodeResult.success(emptyMap())
  public var diffResult: LemonadeCodeResult<String, GitError> = LemonadeCodeResult.success("")
  public var untrackedResult: LemonadeCodeResult<List<String>, GitError> = LemonadeCodeResult.success(emptyList())
  public var uncommittedResult: LemonadeCodeResult<Boolean, GitError> = LemonadeCodeResult.success(false)
  public var fileContents: Map<String, String> = emptyMap()
  public var mergeBaseResult: LemonadeCodeResult<String, GitError> = LemonadeCodeResult.success("base0000")

  /** Committed files by `revision:path`. */
  public var committedFiles: Map<String, ByteArray> = emptyMap()

  /** Files on disk by path. */
  public var workingFiles: Map<String, ByteArray> = emptyMap()
  public var addWorktreeResult: LemonadeCodeResult<Unit, GitError> = LemonadeCodeResult.success(Unit)

  public val diffCalls: MutableList<Pair<String, String>> = mutableListOf()
  public val worktreeCalls: MutableList<WorktreeCall> = mutableListOf()

  public data class WorktreeCall(val root: String, val path: String, val branch: String, val startPoint: String)

  override suspend fun checkout(directory: String): LemonadeCodeResult<GitCheckout, GitError> = checkoutResult

  override suspend fun defaultBranch(repositoryRoot: String): LemonadeCodeResult<String, GitError> = defaultBranchResult

  override suspend fun branches(repositoryRoot: String): LemonadeCodeResult<List<String>, GitError> = branchesResult

  override suspend fun branchTips(repositoryRoot: String): LemonadeCodeResult<Map<String, GitBranchTip>, GitError> = branchTipsResult

  override suspend fun diffAgainst(repositoryRoot: String, baseRef: String): LemonadeCodeResult<String, GitError> {
    diffCalls += repositoryRoot to baseRef
    return diffResult
  }

  override suspend fun mergeBase(repositoryRoot: String, baseRef: String): LemonadeCodeResult<String, GitError> = mergeBaseResult

  override suspend fun readFileAt(
    repositoryRoot: String,
    revision: String,
    path: String,
    maxBytes: Long,
  ): LemonadeCodeResult<GitBlob, GitError> = LemonadeCodeResult.success(committedFiles["$revision:$path"].toBlob(maxBytes))

  override suspend fun readWorkingFile(repositoryRoot: String, path: String, maxBytes: Long): LemonadeCodeResult<GitBlob, GitError> = LemonadeCodeResult.success(workingFiles[path].toBlob(maxBytes))

  private fun ByteArray?.toBlob(maxBytes: Long): GitBlob = when {
    this == null -> GitBlob.Missing
    size > maxBytes -> GitBlob.TooLarge(size.toLong())
    else -> GitBlob.Content(this)
  }

  override suspend fun untrackedFiles(repositoryRoot: String): LemonadeCodeResult<List<String>, GitError> = untrackedResult

  override suspend fun hasUncommittedChanges(repositoryRoot: String): LemonadeCodeResult<Boolean, GitError> = uncommittedResult

  override suspend fun readTextFile(repositoryRoot: String, path: String, maxBytes: Int): LemonadeCodeResult<String?, GitError> = LemonadeCodeResult.success(fileContents[path])

  override suspend fun addWorktree(
    repositoryRoot: String,
    path: String,
    branch: String,
    startPoint: String,
  ): LemonadeCodeResult<Unit, GitError> {
    worktreeCalls += WorktreeCall(repositoryRoot, path, branch, startPoint)
    return addWorktreeResult
  }
}
