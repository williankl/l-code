package dev.loupe.workspace.impl

import dev.loupe.domain.session.SessionId
import dev.loupe.domain.session.SubSessionLocation
import dev.loupe.platform.localization.LoupeString
import dev.loupe.workspace.uimodel.WorkspaceView

/** How the session list is drawn: one level with elbows, or any depth with lineage rails. */
internal enum class NavigationStyle { Flat, Tree }

/** A range of a file's lines, by position in the file's full list of lines. */
internal data class LineRange(
  val filePath: String,
  val anchorIndex: Int,
  val focusIndex: Int,
) {
  val first: Int get() = minOf(anchorIndex, focusIndex)
  val last: Int get() = maxOf(anchorIndex, focusIndex)
}

internal data class Composer(
  val range: LineRange,
  val draft: String,
)

/** The workspace's own UI state: everything that isn't owned by a service. */
internal data class WorkspaceViewState(
  val selectedId: SessionId? = null,
  val view: WorkspaceView = WorkspaceView.Terminal,
  val navigationStyle: NavigationStyle = NavigationStyle.Tree,
  val drawerOpen: Boolean = false,
  val folded: Set<SessionId> = emptySet(),
  /** Files the user expanded or collapsed by hand; the rest follow the default. */
  val fileExpansion: Map<String, Boolean> = emptyMap(),
  val fullFiles: Set<String> = emptySet(),
  val dragging: LineRange? = null,
  val composer: Composer? = null,
  val previewOpen: Boolean = false,
  val settingsOpen: Boolean = false,
  val subSessionMenu: SubSessionLocation? = null,
  val error: LoupeString? = null,
)
