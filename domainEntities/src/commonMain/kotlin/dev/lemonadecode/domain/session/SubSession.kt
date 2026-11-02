package dev.lemonadecode.domain.session

public enum class SubSessionLocation { SameFolder, NewWorktree }

/** What a new sub-session would be, shown before anything is created. */
public data class SubSessionPlan(
  val name: String,
  val location: SubSessionLocation,
  val workingDirectory: String,
  val branch: String?,
  /** True when a new branch is created for a worktree. */
  val createsBranch: Boolean,
  /** The branch the new one starts from. */
  val baseBranch: String?,
)

/** Git's rules for naming a branch (`git check-ref-format --branch`), to catch a bad name before git does. */
public object BranchName {
  public fun isValid(name: String): Boolean = name.isNotEmpty() &&
    name != "@" &&
    !name.startsWith('-') &&
    !name.endsWith('.') &&
    ".." !in name &&
    "@{" !in name &&
    name.none { it < ' ' || it == '\u007F' || it in Forbidden } &&
    name.split('/').all { part -> part.isNotEmpty() && !part.startsWith('.') && !part.endsWith(".lock") }

  private const val Forbidden = " ~^:?*[\\"
}
