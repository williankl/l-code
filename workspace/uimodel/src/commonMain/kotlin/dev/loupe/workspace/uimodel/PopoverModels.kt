package dev.loupe.workspace.uimodel

import androidx.compose.runtime.Immutable
import dev.loupe.platform.localization.LoupeString

public enum class ThemeOption { Dark, Light, System }

@Immutable
public data class SettingsModel(
  val open: Boolean,
  val theme: ThemeOption,
  val keepAwake: Boolean,
  val keepAwakeStatus: KeepAwakeStatusModel,
)

@Immutable
public data class KeepAwakeStatusModel(
  /** e.g. "Keeping awake now · 2 sessions working". */
  val text: LoupeString,
  /** True while keep-awake is actually holding the machine up. */
  val active: Boolean,
)

@Immutable
public data class NewSubSessionModel(
  val open: Boolean,
  val fromSessionName: String,
  val location: SubSessionLocation,
  /** Preview of where the sub-session will run, before anything is created. */
  val path: String,
  val branch: String,
  val branchNote: LoupeString,
  val help: LoupeString,
  /** False outside a git checkout, where only the same folder is possible. */
  val worktreeAvailable: Boolean = true,
)

public enum class SubSessionLocation { SameFolder, NewWorktree }
