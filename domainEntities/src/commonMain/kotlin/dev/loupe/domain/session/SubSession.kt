package dev.loupe.domain.session

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
