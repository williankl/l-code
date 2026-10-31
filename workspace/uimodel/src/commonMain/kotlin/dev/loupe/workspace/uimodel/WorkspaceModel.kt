package dev.loupe.workspace.uimodel

import androidx.compose.runtime.Immutable
import dev.loupe.platform.localization.LoupeString

/**
 * Everything the Loupe workspace draws. Produced by the workspace presenter; the UI renders it
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
  /** Brief problem report, e.g. a worktree that couldn't be created. */
  val error: LoupeString? = null,
  val eventSink: (WorkspaceEvent) -> Unit = {},
)

@Immutable
public data class TopBarModel(
  /** Working folder of the selected session, e.g. `~/code/storefront`. */
  val path: String?,
  /** Shown as a tag while keep-awake is holding the machine up; null hides it. */
  val keepAwakeLabel: LoupeString?,
  /** Drives the info dot on the sessions toggle when the drawer is closed. */
  val anySessionNeedsInput: Boolean,
)

public enum class WorkspaceView { Terminal, Diff }

@Immutable
public data class ViewSwitcherModel(
  val selected: WorkspaceView,
  val sessionStatus: SessionStatus,
  /** Branch the diff is taken against, shown as "Diff vs main". */
  val diffTarget: String,
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

  @Immutable
  public data class Message(
    val title: LoupeString,
    val detail: LoupeString? = null,
    val loading: Boolean = false,
  ) : WorkspaceContentModel

  @Immutable
  public data class Diff(val review: DiffReviewModel) : WorkspaceContentModel

  @Immutable
  public data object NoSessions : WorkspaceContentModel
}
