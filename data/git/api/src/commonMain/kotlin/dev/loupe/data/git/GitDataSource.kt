package dev.loupe.data.git

import dev.loupe.platform.core.LoupeResult

public data class GitCheckout(
  /** Absolute path of the checkout's top-level folder. */
  val root: String,
  /** Current branch, or null on a detached HEAD. */
  val branch: String?,
)

public sealed interface GitError {
  public data object NotARepository : GitError

  public data class RefNotFound(val ref: String) : GitError

  public data class CommandFailed(val message: String) : GitError

  /** git couldn't be run at all. */
  public data class Unavailable(val message: String) : GitError
}

/** Read and write access to git checkouts through the `git` command. */
public interface GitDataSource {
  public suspend fun checkout(directory: String): LoupeResult<GitCheckout, GitError>

  /** The branch changes are reviewed against: origin's HEAD, else `main`, else `master`. */
  public suspend fun defaultBranch(repositoryRoot: String): LoupeResult<String, GitError>

  /**
   * Unified diff of the working tree, staged and unstaged, against the merge base of [baseRef]
   * and HEAD. Every line of each changed file is included as context.
   */
  public suspend fun diffAgainst(repositoryRoot: String, baseRef: String): LoupeResult<String, GitError>

  /** Paths of untracked files that aren't ignored, relative to the root. */
  public suspend fun untrackedFiles(repositoryRoot: String): LoupeResult<List<String>, GitError>

  public suspend fun hasUncommittedChanges(repositoryRoot: String): LoupeResult<Boolean, GitError>

  /** Reads a working-tree file as text; null when it is binary or larger than [maxBytes]. */
  public suspend fun readTextFile(repositoryRoot: String, path: String, maxBytes: Int): LoupeResult<String?, GitError>

  /** Creates a worktree at [path] on a new [branch] that starts at [startPoint]. */
  public suspend fun addWorktree(
    repositoryRoot: String,
    path: String,
    branch: String,
    startPoint: String,
  ): LoupeResult<Unit, GitError>
}
