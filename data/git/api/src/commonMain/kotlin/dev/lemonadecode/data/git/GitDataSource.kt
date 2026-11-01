package dev.lemonadecode.data.git

import dev.lemonadecode.platform.core.LemonadeCodeResult

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

/** A file's bytes as read by [GitDataSource.readFileAt] and [GitDataSource.readWorkingFile]. */
public sealed interface GitBlob {
  public data object Missing : GitBlob

  public data class TooLarge(val sizeBytes: Long) : GitBlob

  public class Content(public val bytes: ByteArray) : GitBlob
}

/** Read and write access to git checkouts through the `git` command. */
public interface GitDataSource {
  public suspend fun checkout(directory: String): LemonadeCodeResult<GitCheckout, GitError>

  /** The branch changes are reviewed against: origin's HEAD, else `main`, else `master`. */
  public suspend fun defaultBranch(repositoryRoot: String): LemonadeCodeResult<String, GitError>

  /** The checkout's local branches, in the order git lists them (by name). */
  public suspend fun branches(repositoryRoot: String): LemonadeCodeResult<List<String>, GitError>

  /**
   * Unified diff of the working tree, staged and unstaged, against the merge base of [baseRef]
   * and HEAD. Every line of each changed file is included as context.
   */
  public suspend fun diffAgainst(repositoryRoot: String, baseRef: String): LemonadeCodeResult<String, GitError>

  /** The commit HEAD and [baseRef] last had in common; the diff's "before" side. */
  public suspend fun mergeBase(repositoryRoot: String, baseRef: String): LemonadeCodeResult<String, GitError>

  /**
   * The bytes of [path] as committed in [revision], whatever is on disk now. [GitBlob.Missing] when
   * [revision] has no such file; [GitBlob.TooLarge] when it is bigger than [maxBytes], which is
   * checked before anything is read.
   */
  public suspend fun readFileAt(
    repositoryRoot: String,
    revision: String,
    path: String,
    maxBytes: Long,
  ): LemonadeCodeResult<GitBlob, GitError>

  /** [path] as it is on disk now; [GitBlob.Missing] when there's no such file. */
  public suspend fun readWorkingFile(repositoryRoot: String, path: String, maxBytes: Long): LemonadeCodeResult<GitBlob, GitError>

  /** Paths of untracked files that aren't ignored, relative to the root. */
  public suspend fun untrackedFiles(repositoryRoot: String): LemonadeCodeResult<List<String>, GitError>

  public suspend fun hasUncommittedChanges(repositoryRoot: String): LemonadeCodeResult<Boolean, GitError>

  /** Reads a working-tree file as text; null when it is binary or larger than [maxBytes]. */
  public suspend fun readTextFile(repositoryRoot: String, path: String, maxBytes: Int): LemonadeCodeResult<String?, GitError>

  /** Creates a worktree at [path] on a new [branch] that starts at [startPoint]. */
  public suspend fun addWorktree(
    repositoryRoot: String,
    path: String,
    branch: String,
    startPoint: String,
  ): LemonadeCodeResult<Unit, GitError>
}
