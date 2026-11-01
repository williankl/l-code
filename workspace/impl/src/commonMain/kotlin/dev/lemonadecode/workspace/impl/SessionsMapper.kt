package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.session.Session
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionPlacement
import dev.lemonadecode.domain.session.SessionStatus
import dev.lemonadecode.domain.session.SubSessionPlan
import dev.lemonadecode.platform.localization.LemonadeCodeString
import dev.lemonadecode.platform.localization.asLemonadeCodeString
import dev.lemonadecode.platform.process.abbreviateHome
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.session_meta
import dev.lemonadecode.workspace.strings.tag_sub_session_same_folder
import dev.lemonadecode.workspace.strings.tag_sub_session_worktree
import dev.lemonadecode.workspace.uimodel.HeaderTagIcon
import dev.lemonadecode.workspace.uimodel.HeaderTagModel
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
import dev.lemonadecode.workspace.uimodel.SessionStatus as StatusModel

/** Session list, tiles and header. */
internal object SessionsMapper {
  fun navigation(sessions: List<Session>, selected: Session?, style: NavigationStyle, folded: Set<SessionId>, home: String): SessionNavigationModel = when (style) {
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
    NavigationStyle.Tree -> SessionNavigationModel.Tree(treeRows(sessions, selected, folded, home))
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
   * them; [worktreePlan] names the branch a new worktree would get. [renaming] shows the name field.
   */
  fun menu(
    session: Session,
    style: NavigationStyle,
    home: String,
    worktreePlan: SubSessionPlan?,
    renaming: Boolean,
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
      worktreeBranch = worktreePlan?.takeIf { it.createsBranch }?.branch,
      rename = if (renaming) SessionRenameModel(name = session.displayName, automaticName = session.name) else null,
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
