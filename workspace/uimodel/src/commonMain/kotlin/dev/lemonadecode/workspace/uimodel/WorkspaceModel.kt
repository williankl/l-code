package dev.lemonadecode.workspace.uimodel

import androidx.compose.runtime.Immutable
import dev.lemonadecode.platform.localization.LemonadeCodeString

/**
 * Everything the Lemonade Code workspace draws. Produced by the workspace presenter; the UI renders it
 * and reports intents through [eventSink].
 */
@Immutable
public data class WorkspaceModel(
  val topBar: TopBarModel,
  val sessions: SessionsPanelModel,
  /** Null when no session is open. */
  val header: SessionHeaderModel?,
  val viewSwitcher: ViewSwitcherModel?,
  val content: WorkspaceContentModel,
  val settings: SettingsModel,
  val newSubSession: NewSubSessionModel,
  /** Asks whether to delete the worktrees a closing session leaves unused; null otherwise. */
  val closeSession: CloseSessionDialogModel? = null,
  /** The Devices panel while it's open; null when closed or the Mobile plugin is off. */
  val devices: DevicesPanelModel? = null,
  /** Devices mirrored in windows of their own, in the order they were opened. */
  val deviceWindows: List<DeviceWindowModel> = emptyList(),
  /** Brief problem report, e.g. a worktree that couldn't be created. */
  val error: LemonadeCodeString? = null,
  /** A short confirmation shown at the bottom for a moment, e.g. after copying a path. */
  val toast: LemonadeCodeString? = null,
  val eventSink: (WorkspaceEvent) -> Unit = {},
)

@Immutable
public data class TopBarModel(
  /** Working folder of the selected session, e.g. `~/code/storefront`. */
  val path: String?,
  /** Shown as a tag while keep-awake is holding the machine up; null hides it. */
  val keepAwakeLabel: LemonadeCodeString?,
  /** Drives the info dot on the sessions toggle when the drawer is closed. */
  val anySessionNeedsInput: Boolean,
  /** An update waits: the gear carries a dot. */
  val updateAttention: Boolean = false,
  /** The phone button; null while the Mobile plugin is off. */
  val devices: DevicesButtonModel? = null,
)

public enum class WorkspaceView { Terminal, Diff }

@Immutable
public data class ViewSwitcherModel(
  val selected: WorkspaceView,
  val sessionStatus: SessionStatus,
  /** Totals for the diff tab; null when the session has nothing to diff. */
  val diffStat: DiffStatModel?,
  val unsentCommentCount: Int,
)

@Immutable
public data class DiffStatModel(
  val additions: Int,
  val deletions: Int,
)

@Immutable
public sealed interface WorkspaceContentModel {
  /** The live terminal of [sessionId]; the host decides how it is drawn. */
  @Immutable
  public data class Terminal(val sessionId: String) : WorkspaceContentModel

  /** [diffToolbar] is set in the diff view, so the branch can be changed while there is no diff. */
  @Immutable
  public data class Message(
    val title: LemonadeCodeString,
    val detail: LemonadeCodeString? = null,
    val loading: Boolean = false,
    val diffToolbar: DiffToolbarModel? = null,
  ) : WorkspaceContentModel

  @Immutable
  public data class Diff(val review: DiffReviewModel) : WorkspaceContentModel

  @Immutable
  public data object NoSessions : WorkspaceContentModel
}
