package dev.loupe.workspace.uimodel

/** Every intent the workspace UI can raise. */
public sealed interface WorkspaceEvent {
  /** The sidebar on wide windows, the drawer below the narrow-layout breakpoint. */
  public data class ToggleSessionsPanel(val narrowLayout: Boolean) : WorkspaceEvent
  public data object CloseDrawer : WorkspaceEvent
  public data class SelectSession(val sessionId: String) : WorkspaceEvent
  public data class ToggleSessionFold(val sessionId: String) : WorkspaceEvent
  public data object CloseSelectedSession : WorkspaceEvent

  /** Opens a new terminal at home. */
  public data object NewSession : WorkspaceEvent

  public data object ToggleSettings : WorkspaceEvent
  public data object DismissSettings : WorkspaceEvent
  public data class SetTheme(val theme: ThemeOption) : WorkspaceEvent
  public data class SetKeepAwake(val enabled: Boolean) : WorkspaceEvent

  public data object OpenParentSession : WorkspaceEvent
  public data object ToggleNewSubSessionMenu : WorkspaceEvent
  public data object DismissNewSubSessionMenu : WorkspaceEvent
  public data class SetSubSessionLocation(val location: SubSessionLocation) : WorkspaceEvent
  public data object CreateSubSession : WorkspaceEvent
  public data class SelectView(val view: WorkspaceView) : WorkspaceEvent

  public data object RefreshDiff : WorkspaceEvent
  public data class ToggleFileExpanded(val fileId: String) : WorkspaceEvent
  public data class ToggleFullFile(val fileId: String) : WorkspaceEvent
  public data class ShowFullFile(val fileId: String) : WorkspaceEvent

  /** Pressed + on a line: starts a range there. */
  public data class StartCommentRange(val fileId: String, val lineIndex: Int) : WorkspaceEvent

  /** Dragged from + onto another line while still pressed. */
  public data class DragCommentRange(val fileId: String, val lineIndex: Int) : WorkspaceEvent

  /** Released after pressing or dragging; the composer opens under the last line. */
  public data object EndCommentRange : WorkspaceEvent

  /** Shift-clicked + on another line to grow the open range. */
  public data class ExtendCommentRange(val fileId: String, val lineIndex: Int) : WorkspaceEvent

  public data class UpdateCommentDraft(val text: String) : WorkspaceEvent
  public data object CancelComment : WorkspaceEvent
  public data object AddComment : WorkspaceEvent
  public data class DeleteComment(val commentId: String) : WorkspaceEvent

  public data object TogglePreview : WorkspaceEvent
  public data object SendToNewSubSession : WorkspaceEvent
  public data object SendToSession : WorkspaceEvent

  public data object DismissError : WorkspaceEvent
}
