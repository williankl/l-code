package dev.lemonadecode.workspace.uimodel

import androidx.compose.runtime.Immutable
import dev.lemonadecode.platform.localization.LemonadeCodeString

public enum class ThemeOption { Dark, Light, System }

/** The settings popover shows its list, or one item's own page. */
public enum class SettingsPage { List, Appearance, Language, KeepAwake, Sounds, Plugins, Updates }

/** The sounds Settings › Sounds lets you hear: one ding when an agent finishes, two when it needs input. */
public enum class StatusSound { Finished, NeedsInput }

/** The interface language; [System] follows the computer's. [tag] is the language's BCP 47 tag. */
public enum class LanguageOption(public val tag: String?) {
  System(null),
  English("en"),
  German("de"),
  Spanish("es"),
  French("fr"),
  Portuguese("pt"),
  Slovak("sk"),
}

@Immutable
public data class SettingsModel(
  val open: Boolean,
  val theme: ThemeOption,
  val keepAwake: Boolean,
  val keepAwakeStatus: KeepAwakeStatusModel,
  /** A ding when an agent finishes, two when it needs input. */
  val statusSounds: Boolean,
  val page: SettingsPage = SettingsPage.List,
  val language: LanguageOption = LanguageOption.System,
  /** The language Match system stands for on this computer. */
  val systemLanguage: LanguageOption = LanguageOption.English,
  val mobile: MobilePluginModel = MobilePluginModel(enabled = false),
  val updates: UpdatesModel = UpdatesModel.Unknown,
  /** Panels, menus and lists open and move with motion. */
  val animations: Boolean = true,
)

/** Plugins › Mobile. */
@Immutable
public data class MobilePluginModel(
  val enabled: Boolean,
  /** e.g. "1 Android · 1 iOS connected"; null while it's off. */
  val status: LemonadeCodeString? = null,
)

/** Where updating stands, as the Updates page shows it. */
public enum class UpdatePhase {
  /** Nothing newer: up to date, or not checked yet. */
  UpToDate,
  Checking,
  Available,
  Downloading,

  /** Downloaded: a restart finishes it. */
  Downloaded,
  Restarting,
  Failed,

  /** Homebrew, the Snap Store or nothing at all updates this installation; there's nothing to press. */
  Elsewhere,
}

@Immutable
public data class UpdatesModel(
  /** e.g. "Lemonade Code 1.8.2". */
  val title: LemonadeCodeString,
  val phase: UpdatePhase,
  /** The line under the title, e.g. "Up to date · checked 2 h ago". */
  val status: LemonadeCodeString,
  /** The value beside Updates in the list, e.g. "1.9.0 available". */
  val summary: LemonadeCodeString,
  /** What the newer version brings; null while there's none. */
  val release: ReleaseNotesModel? = null,
  /** 0 to 1 while downloading, once the size is known. */
  val progress: Float? = null,
) {
  /** The gear and the Updates row carry a dot. */
  val needsAttention: Boolean get() = phase == UpdatePhase.Available || phase == UpdatePhase.Downloaded

  public companion object {
    public val Unknown: UpdatesModel = UpdatesModel(
      title = LemonadeCodeString.Literal("Lemonade Code"),
      phase = UpdatePhase.Elsewhere,
      status = LemonadeCodeString.Literal(""),
      summary = LemonadeCodeString.Literal(""),
    )
  }
}

@Immutable
public data class ReleaseNotesModel(
  /** e.g. `1.9.0`. */
  val version: String,
  /** A few lines of what's new; null when the release says nothing. */
  val notes: String?,
  /** The release's page, with all its notes. */
  val url: String,
)

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
  /** The branch field, while a new worktree is picked. */
  val worktreeBranch: WorktreeBranchModel? = null,
)

/** The field naming a new worktree's branch, in the sub-session panel or in a session's menu. */
@Immutable
public data class WorktreeBranchModel(
  /** The branch typed so far. The field starts with a playful one, like `worktree/generating-bugs`. */
  val name: String,
  /** Where the worktree will be, with `~` for home: a folder named after the branch's last part. */
  val path: String,
  /** Why the worktree can't be created on this branch; null when it can, or while nothing is typed. */
  val problem: LemonadeCodeString?,
  /** False while the name is blank, or one git won't take or already has. */
  val canCreate: Boolean,
)

public enum class SubSessionLocation { SameFolder, NewWorktree }
