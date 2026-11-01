package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.review.FileContent
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SubSessionLocation
import dev.lemonadecode.platform.localization.LemonadeCodeString
import dev.lemonadecode.workspace.uimodel.PreviewLayout
import dev.lemonadecode.workspace.uimodel.WorkspaceView

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
  /**
   * Preview (true) or text diff (false) picked by hand, by path. The rest show their preview when
   * Lemonade Code can draw them, unless they have comments, which only the text diff shows.
   */
  val previewChoices: Map<String, Boolean> = emptyMap(),
  /** Preview sides picked by hand; the rest follow the default for their kind. */
  val previewLayouts: Map<String, PreviewLayout> = emptyMap(),
  val dragging: LineRange? = null,
  val composer: Composer? = null,
  val previewOpen: Boolean = false,
  val settingsOpen: Boolean = false,
  /** The panel under the Update button is open. */
  val updateOpen: Boolean = false,
  val subSessionMenu: SubSessionLocation? = null,
  /** The list of branches the diff can compare with is open. */
  val basePickerOpen: Boolean = false,
  /** The session whose right-click menu is open. */
  val sessionMenu: SessionId? = null,
  /** The session whose menu shows the name field; the text itself stays in the field until saved. */
  val renaming: SessionId? = null,
  val error: LemonadeCodeString? = null,
  val toast: LemonadeCodeString? = null,
)

/**
 * Both sides of a previewed file as far as they have loaded. A side is null until it has been
 * read; [error] is set when reading failed, and the last good content, if any, stays shown.
 */
internal data class PreviewLoad(
  val before: FileContent? = null,
  val after: FileContent? = null,
  val error: LemonadeCodeString? = null,
)
