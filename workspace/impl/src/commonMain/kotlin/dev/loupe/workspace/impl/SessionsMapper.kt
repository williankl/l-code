package dev.loupe.workspace.impl

import dev.loupe.domain.session.Session
import dev.loupe.domain.session.SessionId
import dev.loupe.domain.session.SessionOrigin
import dev.loupe.domain.session.SessionPlacement
import dev.loupe.domain.session.SessionStatus
import dev.loupe.platform.localization.LoupeString
import dev.loupe.platform.localization.asLoupeString
import dev.loupe.platform.process.abbreviateHome
import dev.loupe.workspace.strings.Res
import dev.loupe.workspace.strings.meta_review
import dev.loupe.workspace.strings.meta_shell_place
import dev.loupe.workspace.strings.meta_split_same_folder
import dev.loupe.workspace.strings.meta_split_worktree
import dev.loupe.workspace.strings.tag_sub_session_same_folder
import dev.loupe.workspace.strings.tag_sub_session_worktree
import dev.loupe.workspace.uimodel.HeaderTagIcon
import dev.loupe.workspace.uimodel.HeaderTagModel
import dev.loupe.workspace.uimodel.SessionHeaderModel
import dev.loupe.workspace.uimodel.SessionListRowModel
import dev.loupe.workspace.uimodel.SessionNavigationModel
import dev.loupe.workspace.uimodel.SessionTileModel
import dev.loupe.workspace.uimodel.SessionTreeRowModel
import dev.loupe.workspace.uimodel.TreeConnectorModel
import dev.loupe.workspace.uimodel.TreeFoldModel
import dev.loupe.workspace.uimodel.TreeGuideModel
import dev.loupe.workspace.uimodel.SessionStatus as StatusModel

/** Session list, tiles and header. */
internal object SessionsMapper {
  fun navigation(sessions: List<Session>, selected: Session?, style: NavigationStyle, folded: Set<SessionId>, home: String): SessionNavigationModel = when (style) {
    NavigationStyle.Flat -> SessionNavigationModel.Flat(
      sessions.map { session ->
        SessionListRowModel(
          id = session.id.value,
          name = session.name,
          meta = meta(session, home),
          status = session.status.toModel(),
          selected = session.id == selected?.id,
          isSubSession = session.parentId != null,
        )
      },
    )
    NavigationStyle.Tree -> SessionNavigationModel.Tree(treeRows(sessions, selected, folded, home))
  }

  fun tiles(sessions: List<Session>, selected: Session?): List<SessionTileModel> = sessions.map { session ->
    SessionTileModel(
      id = session.id.value,
      name = session.name,
      initials = initials(session.name),
      status = session.status.toModel(),
      selected = session.id == selected?.id,
      isSubSession = session.parentId != null,
    )
  }

  fun header(session: Session, parent: Session?, style: NavigationStyle): SessionHeaderModel {
    val shellAndBranch = listOfNotNull(
      HeaderTagModel(session.shell.asLoupeString()),
      session.branch?.let { HeaderTagModel(it.asLoupeString()) },
    )
    if (parent == null) return SessionHeaderModel(session.name, null, shellAndBranch, canStartSubSession = style == NavigationStyle.Tree)
    val kind = if (session.placement == SessionPlacement.Worktree) {
      HeaderTagModel(Res.string.tag_sub_session_worktree.asLoupeString(), HeaderTagIcon.SubSession)
    } else {
      HeaderTagModel(Res.string.tag_sub_session_same_folder.asLoupeString(), HeaderTagIcon.SubSession)
    }
    return when (style) {
      NavigationStyle.Flat -> SessionHeaderModel(session.name, parent.name, listOf(kind), canStartSubSession = false)
      NavigationStyle.Tree -> {
        val branch = session.branch?.takeIf { session.placement == SessionPlacement.Worktree }?.let { HeaderTagModel(it.asLoupeString()) }
        SessionHeaderModel(session.name, null, listOfNotNull(kind, branch), canStartSubSession = true)
      }
    }
  }

  /** The second line of a session row: what it is for, or where it is. */
  fun meta(session: Session, home: String): LoupeString = when (val origin = session.origin) {
    is SessionOrigin.Review -> Res.plurals.meta_review.asLoupeString(origin.commentCount)
    SessionOrigin.Split -> if (session.placement == SessionPlacement.Worktree) {
      Res.string.meta_split_worktree.asLoupeString(session.shell)
    } else {
      Res.string.meta_split_same_folder.asLoupeString(session.shell)
    }
    SessionOrigin.Opened -> Res.string.meta_shell_place.asLoupeString(
      session.shell,
      session.branch ?: abbreviateHome(session.workingDirectory, home),
    )
  }

  fun SessionStatus.toModel(): StatusModel = when (this) {
    SessionStatus.Shell -> StatusModel.Shell
    SessionStatus.Idle -> StatusModel.Idle
    SessionStatus.Working -> StatusModel.Working
    SessionStatus.NeedsInput -> StatusModel.NeedsInput
    SessionStatus.Exited -> StatusModel.Exited
  }

  private fun initials(name: String): String {
    val parts = name.split('-', '_', ' ', '.').filter { it.isNotEmpty() }
    val letters = when {
      parts.size >= 2 && parts[1].all { it.isDigit() } -> "${parts[0].first()}${parts[1]}"
      parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}"
      else -> name.take(2)
    }
    return letters.take(3).uppercase()
  }

  /**
   * Indented tree with lineage rails. A rail is lit when it is part of the path from a root
   * down to the selected session, so the path can be traced at a glance.
   */
  private fun treeRows(sessions: List<Session>, selected: Session?, folded: Set<SessionId>, home: String): List<SessionTreeRowModel> {
    val byId = sessions.associateBy { it.id }
    val position = sessions.withIndex().associate { it.value.id to it.index }
    val children = sessions.groupBy { it.parentId }
    fun ancestors(session: Session): List<Session> = generateSequence(session.parentId?.let(byId::get)) { it.parentId?.let(byId::get) }.toList().reversed()
    fun descendants(id: SessionId): Int = children[id].orEmpty().sumOf { 1 + descendants(it.id) }

    val selectedPath = selected?.let { ancestors(it) + it }.orEmpty()
    val onPath = selectedPath.map { it.id }.toSet()

    return sessions.mapNotNull { session ->
      val lineage = ancestors(session)
      if (lineage.any { it.id in folded }) return@mapNotNull null
      val depth = lineage.size
      val index = position.getValue(session.id)
      val guides = (1 until depth).map { level ->
        val ancestor = lineage[level]
        val siblings = children[ancestor.parentId].orEmpty()
        val continues = siblings.indexOf(ancestor) < siblings.lastIndex
        val pathNode = selectedPath.getOrNull(level)
        val highlighted = continues &&
          pathNode != null &&
          selectedPath[level - 1].id == lineage[level - 1].id &&
          index < position.getValue(pathNode.id)
        TreeGuideModel(visible = continues, highlighted = highlighted)
      }
      val connector = if (depth == 0) {
        null
      } else {
        val siblings = children[session.parentId].orEmpty()
        val pathNode = selectedPath.getOrNull(depth)
        val samePath = pathNode != null && selectedPath.getOrNull(depth - 1)?.id == session.parentId
        val pathIndex = pathNode?.let { position.getValue(it.id) } ?: -1
        TreeConnectorModel(
          isLastChild = siblings.lastOrNull()?.id == session.id,
          upperHighlighted = samePath && index <= pathIndex,
          lowerHighlighted = samePath && index < pathIndex,
          branchHighlighted = session.id in onPath,
        )
      }
      val hidden = descendants(session.id)
      SessionTreeRowModel(
        id = session.id.value,
        name = session.name,
        meta = meta(session, home),
        status = session.status.toModel(),
        selected = session.id == selected?.id,
        depth = depth,
        guides = guides,
        connector = connector,
        fold = if (hidden > 0) TreeFoldModel(folded = session.id in folded, descendantCount = hidden) else null,
      )
    }
  }
}
