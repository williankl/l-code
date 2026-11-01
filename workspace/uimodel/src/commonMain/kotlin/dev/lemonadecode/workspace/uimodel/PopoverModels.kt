package dev.lemonadecode.workspace.uimodel

import androidx.compose.runtime.Immutable
import dev.lemonadecode.platform.localization.LemonadeCodeString

public enum class ThemeOption { Dark, Light, System }

@Immutable
public data class SettingsModel(
  val open: Boolean,
  val theme: ThemeOption,
  val keepAwake: Boolean,
  val keepAwakeStatus: KeepAwakeStatusModel,
)

/** A newer Lemonade Code to install, and how installing it is going. */
@Immutable
public data class UpdateModel(
  /** e.g. `1.3.0`. */
  val version: String,
  val currentVersion: String,
  /** The release's page, with its notes. */
  val releaseUrl: String,
  /** The panel under the button is open. */
  val open: Boolean,
  val status: UpdateStatusModel,
)

@Immutable
public sealed interface UpdateStatusModel {
  @Immutable
  public data object Ready : UpdateStatusModel

  /** [percent] is null until the download's size is known. */
  @Immutable
  public data class Downloading(val percent: Int?) : UpdateStatusModel

  @Immutable
  public data object Restarting : UpdateStatusModel

  @Immutable
  public data class Failed(val message: LemonadeCodeString) : UpdateStatusModel
}

@Immutable
public data class KeepAwakeStatusModel(
  /** e.g. "Keeping awake now · 2 sessions working". */
  val text: LemonadeCodeString,
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
  val branchNote: LemonadeCodeString,
  val help: LemonadeCodeString,
  /** False outside a git checkout, where only the same folder is possible. */
  val worktreeAvailable: Boolean = true,
)

public enum class SubSessionLocation { SameFolder, NewWorktree }
