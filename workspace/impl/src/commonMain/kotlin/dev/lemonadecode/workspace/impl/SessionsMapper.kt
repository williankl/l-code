package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.session.ArchivedSession
import dev.lemonadecode.domain.session.Session
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionOrder
import dev.lemonadecode.domain.session.SessionPlacement
import dev.lemonadecode.domain.session.SessionStatus
import dev.lemonadecode.platform.localization.LemonadeCodeString
import dev.lemonadecode.platform.localization.asLemonadeCodeString
import dev.lemonadecode.platform.process.abbreviateHome
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.session_meta
import dev.lemonadecode.workspace.strings.tag_sub_session_same_folder
import dev.lemonadecode.workspace.strings.tag_sub_session_worktree
import dev.lemonadecode.workspace.uimodel.ArchivedSessionRowModel
import dev.lemonadecode.workspace.uimodel.ArchivedSessionsModel
import dev.lemonadecode.workspace.uimodel.HeaderTagIcon
import dev.lemonadecode.workspace.uimodel.HeaderTagModel
import dev.lemonadecode.workspace.uimodel.SessionDragModel
import dev.lemonadecode.workspace.uimodel.SessionDropModel
import dev.lemonadecode.workspace.uimodel.SessionHeaderModel
import dev.lemonadecode.workspace.uimodel.SessionListRowModel
import dev.lemonadecode.workspace.uimodel.SessionMenuModel
import dev.lemonadecode.workspace.uimodel.SessionNavigationModel
import dev.lemonadecode.workspace.uimodel.SessionRenameModel
import dev.lemonadecode.workspace.uimodel.SessionTileModel
import dev.lemonadecode.workspace.uimodel.SessionTreeRowModel
import dev.lemonadecode.workspace.uimodel.TreeConnectorModel
import dev.lemonadecode.workspace.uimodel.TreeFoldModel
import dev.lemonadecode.workspace.uimodel.TreeGuideModel
import dev.lemonadecode.workspace.uimodel.WorktreeBranchModel
import dev.lemonadecode.workspace.uimodel.SessionStatus as StatusModel

/** Session list, tiles and header. */
internal object SessionsMapper {
  fun navigation(
    sessions: List<Session>,
    selected: Session?,
    style: NavigationStyle,
    folded: Set<SessionId>,
    home: String,
    drag: SessionDrag?,
  ): SessionNavigationModel = when (style) {
    NavigationStyle.Flat -> SessionNavigationModel.Flat(
      sessions.map { session ->
        SessionListRowModel(
          id = session.id.value,
          name = session.displayName,
          meta = meta(session, home),
          status = session.status.toModel(),
          selected = session.id == selected?.id,
          isSubSession = session.parentId != null,
        )
      },
    )
    NavigationStyle.Tree -> {
      val rows = treeRows(sessions, selected, folded, home)
      SessionNavigationModel.Tree(rows, drag = drag?.let { drag(sessions, rows, it) })
    }
  }

  /**
   * The session being dragged, and the line where letting go would put it: over the sibling it
   * would go before, or under the last row of the last sibling. None where it already is.
   */
  private fun drag(sessions: List<Session>, rows: List<SessionTreeRowModel>, drag: SessionDrag): SessionDragModel? {
    val id = drag.sessionId
    val siblings = SessionOrder.siblings(sessions, id)
    val from = siblings.indexOfFirst { it.id == id }
    if (from < 0 || rows.none { it.id == id.value }) return null
    val to = drag.index.coerceIn(0, siblings.lastIndex)
    val others = siblings.filterNot { it.id == id }
    val drop = when {
      to == from -> null
      to < others.size -> SessionDropModel(rowId = others[to].id.value, below = false)
      else -> {
        val last = rows.indexOfFirst { it.id == others.last().id.value }
        var end = last
        while (end + 1 < rows.size && rows[end + 1].depth > rows[last].depth) end++
        SessionDropModel(rowId = rows[end].id, below = true)
      }
    }
    return SessionDragModel(sessionId = id.value, drop = drop)
  }

  fun tiles(sessions: List<Session>, selected: Session?): List<SessionTileModel> = sessions.map { session ->
    SessionTileModel(
      id = session.id.value,
      name = session.displayName,
      initials = initials(session.displayName),
      status = session.status.toModel(),
      selected = session.id == selected?.id,
      isSubSession = session.parentId != null,
    )
  }

  fun header(session: Session, parent: Session?, style: NavigationStyle): SessionHeaderModel {
    val shellAndBranch = listOfNotNull(
      HeaderTagModel(session.shell.asLemonadeCodeString()),
      session.branch?.let { HeaderTagModel(it.asLemonadeCodeString()) },
    )
    if (parent == null) return SessionHeaderModel(session.displayName, null, shellAndBranch, canStartSubSession = style == NavigationStyle.Tree)
    val kind = if (session.placement == SessionPlacement.Worktree) {
      HeaderTagModel(Res.string.tag_sub_session_worktree.asLemonadeCodeString(), HeaderTagIcon.SubSession)
    } else {
      HeaderTagModel(Res.string.tag_sub_session_same_folder.asLemonadeCodeString(), HeaderTagIcon.SubSession)
    }
    return when (style) {
      NavigationStyle.Flat -> SessionHeaderModel(session.displayName, parent.displayName, listOf(kind), canStartSubSession = false)
      NavigationStyle.Tree -> {
        val branch = session.branch?.takeIf { session.placement == SessionPlacement.Worktree }?.let { HeaderTagModel(it.asLemonadeCodeString()) }
        SessionHeaderModel(session.displayName, null, listOfNotNull(kind, branch), canStartSubSession = true)
      }
    }
  }

  /**
   * The right-click menu of [session]. Sub-sessions can be started from it where the list nests
   * them. [renaming] shows the name field; [newWorktree], the field for a new worktree's branch.
   * It moves the session up or down among its [siblings], it included.
   */
  fun menu(
    session: Session,
    style: NavigationStyle,
    home: String,
    renaming: Boolean,
    newWorktree: WorktreeBranchModel? = null,
    siblings: List<Session>,
  ): SessionMenuModel {
    val folder = abbreviateHome(session.workingDirectory, home)
    return SessionMenuModel(
      sessionId = session.id.value,
      title = session.displayName,
      location = listOfNotNull(folder, session.branch).joinToString(" · "),
      folderPath = session.workingDirectory,
      canStartSubSession = style == NavigationStyle.Tree,
      branch = session.branch,
      worktreeAvailable = session.repositoryRoot != null,
      rename = if (renaming) SessionRenameModel(name = session.displayName, automaticName = session.name) else null,
      newWorktree = newWorktree,
      canMoveUp = siblings.isNotEmpty() && siblings.first().id != session.id,
      canMoveDown = siblings.isNotEmpty() && siblings.last().id != session.id,
    )
  }

  /** [id] and every session nested under it, at any depth. */
  fun subtree(sessions: List<Session>, id: SessionId): Set<SessionId> {
    val result = mutableSetOf(id)
    var grew = true
    while (grew) {
      grew = false
      sessions.forEach { if (it.parentId in result && result.add(it.id)) grew = true }
    }
    return result
  }

  /**
   * The session to select once [id] and its sub-sessions leave the list: its parent, else the next
   * session at its level, else the one before it, else whichever is left. Null when none is.
   */
  fun neighbour(sessions: List<Session>, id: SessionId): SessionId? {
    val session = sessions.firstOrNull { it.id == id } ?: return null
    val going = subtree(sessions, id)
    val staying = sessions.filterNot { it.id in going }
    session.parentId?.let { parent -> staying.firstOrNull { it.id == parent } }?.let { return it.id }
    val index = sessions.indexOf(session)
    val level = staying.filter { it.parentId == session.parentId }
    return (level.firstOrNull { sessions.indexOf(it) > index } ?: level.lastOrNull { sessions.indexOf(it) < index } ?: staying.firstOrNull())?.id
  }

  /** The Archived section: each archived session as a row, the most recently archived first. Null while there's none. */
  fun archived(archived: List<ArchivedSession>, open: Boolean, home: String): ArchivedSessionsModel? = archived.takeIf { it.isNotEmpty() }?.let { list ->
    ArchivedSessionsModel(
      open = open,
      rows = list.map { entry ->
        ArchivedSessionRowModel(
          id = entry.id.value,
          name = entry.session.displayName,
          meta = meta(entry.session, home),
          subSessionCount = entry.subSessions.size,
        )
      },
    )
  }

  /**
   * The second line of a session row: the agent running in it, else its shell, and the branch it
   * is on (its folder outside a git checkout).
   */
  fun meta(session: Session, home: String): LemonadeCodeString = Res.string.session_meta.asLemonadeCodeString(
    session.agent ?: session.shell,
    session.branch ?: abbreviateHome(session.workingDirectory, home),
  )

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
        name = session.displayName,
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
