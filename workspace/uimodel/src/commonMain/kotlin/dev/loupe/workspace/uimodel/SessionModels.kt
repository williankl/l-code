package dev.loupe.workspace.uimodel

import androidx.compose.runtime.Immutable
import dev.loupe.platform.localization.LoupeString

/** A session's status as shown; [Shell] (a plain terminal, no agent reporting) shows nothing. */
public enum class SessionStatus { Shell, Idle, Working, NeedsInput, Exited }

/**
 * The sessions column. On wide windows it is a sidebar ([expanded]) or a rail of tiles; on narrow
 * windows it becomes a drawer ([drawerOpen]). Which one shows is decided by the UI from the width.
 */
@Immutable
public data class SessionsPanelModel(
  val expanded: Boolean,
  val drawerOpen: Boolean,
  val navigation: SessionNavigationModel,
  val tiles: List<SessionTileModel>,
)

@Immutable
public sealed interface SessionNavigationModel {
  /** One level of nesting, sub-sessions hang off their parent with an elbow. */
  @Immutable
  public data class Flat(val rows: List<SessionListRowModel>) : SessionNavigationModel

  /** Any depth, drawn as an indented tree with lineage rails. */
  @Immutable
  public data class Tree(val rows: List<SessionTreeRowModel>) : SessionNavigationModel
}

@Immutable
public data class SessionListRowModel(
  val id: String,
  val name: String,
  val meta: LoupeString,
  val status: SessionStatus,
  val selected: Boolean,
  val isSubSession: Boolean,
)

@Immutable
public data class SessionTreeRowModel(
  val id: String,
  val name: String,
  val meta: LoupeString,
  val status: SessionStatus,
  val selected: Boolean,
  /** 0 for a root session. Roots show the computer icon, nested rows don't. */
  val depth: Int,
  /** One entry per ancestor level between the root and this row's parent. */
  val guides: List<TreeGuideModel>,
  /** The elbow or tee joining this row to its parent; null for roots. */
  val connector: TreeConnectorModel?,
  /** Present when the row has descendants that can be folded away. */
  val fold: TreeFoldModel?,
)

/** A vertical rail passing this row on its way to a later sibling of an ancestor. */
@Immutable
public data class TreeGuideModel(
  val visible: Boolean,
  /** Lit in brand lime when it is part of the path to the selected session. */
  val highlighted: Boolean,
)

@Immutable
public data class TreeConnectorModel(
  val isLastChild: Boolean,
  val upperHighlighted: Boolean,
  val lowerHighlighted: Boolean,
  val branchHighlighted: Boolean,
)

@Immutable
public data class TreeFoldModel(
  val folded: Boolean,
  val descendantCount: Int,
)

@Immutable
public data class SessionTileModel(
  val id: String,
  val name: String,
  val initials: String,
  val status: SessionStatus,
  val selected: Boolean,
  val isSubSession: Boolean,
)

/**
 * Title row of the main panel. [parentName] adds the "← parent /" back link used when
 * sub-sessions are one level deep.
 */
@Immutable
public data class SessionHeaderModel(
  val name: String,
  val parentName: String?,
  val tags: List<HeaderTagModel>,
  val canStartSubSession: Boolean,
)

@Immutable
public data class HeaderTagModel(
  val label: LoupeString,
  val icon: HeaderTagIcon = HeaderTagIcon.None,
)

public enum class HeaderTagIcon { None, SubSession, Terminal }
