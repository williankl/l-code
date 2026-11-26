package dev.lemonadecode.sessions.impl

import dev.lemonadecode.domain.session.Session
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SubSessionLocation
import dev.lemonadecode.domain.session.SubSessionPlan

/**
 * Names and places sub-sessions. One in the same folder is `sub-1`, `sub-2`… Worktrees go next to
 * the repository, in a `<repo>.worktrees/` folder, on the branch the user typed, in a folder named
 * after its last part, and the sub-session takes the folder's name: `worktree/generating-bugs` makes
 * `generating-bugs`. With no branch typed, a `sub-1` folder on a branch named after the parent's.
 * A worktree of a sub-session working in another checkout than its own parent goes next to that one.
 */
internal object SubSessionPlanner {
  /** [folderTaken] tells whether something is already at a path, so a new worktree goes elsewhere. */
  fun plan(parent: Session, existing: List<Session>, location: SubSessionLocation, prefix: String, branch: String? = null, folderTaken: (String) -> Boolean = { false }): SubSessionPlan? {
    return when (location) {
      SubSessionLocation.SameFolder -> SubSessionPlan(
        name = nextName(existing, prefix),
        location = location,
        workingDirectory = parent.workingDirectory,
        branch = parent.branch,
        createsBranch = false,
        baseBranch = parent.branch,
      )
      SubSessionLocation.NewWorktree -> {
        val root = parent.repositoryRoot ?: return null
        val grandparent = parent.parentId?.let { id -> existing.firstOrNull { it.id == id } }
        val folder = if (grandparent != null && parent.worksInOtherCheckoutThan(grandparent)) {
          root.substringBeforeLast('/')
        } else {
          "${root.substringBeforeLast('/')}/${root.substringAfterLast('/')}.worktrees"
        }
        val name = if (branch == null) {
          nextName(existing, prefix) { folderTaken("$folder/$it") }
        } else {
          worktreeName(branch, siblings = existing.filter { it.parentId == parent.id }) { folderTaken("$folder/$it") }
        }
        SubSessionPlan(
          name = name,
          location = location,
          workingDirectory = "$folder/$name",
          branch = branch ?: parent.branch?.let { "$it-$name" } ?: "lemonade-code/$name",
          createsBranch = true,
          baseBranch = parent.branch,
        )
      }
    }
  }

  fun nextName(existing: List<Session>, prefix: String, taken: (String) -> Boolean = { false }): String = generateSequence(1) { it + 1 }
    .map { "$prefix-$it" }
    .first { name -> existing.none { it.name == name } && !taken(name) }

  /**
   * The name of a new worktree on [branch], for its folder and its sub-session alike: the branch's
   * last part, which [dev.lemonadecode.domain.session.BranchName] keeps free of `/`, `\`, spaces and a
   * leading dot (not of what only Windows forbids, like `|` or `con`). When a folder by that name is
   * [taken], or a sibling goes by it, `-2`, `-3`… follow, so neither the folder nor the name in the
   * list is ever a copy.
   */
  fun worktreeName(branch: String, siblings: List<Session>, taken: (String) -> Boolean): String {
    val base = branch.substringAfterLast('/')
    // Nothing typed yet, or a name ending in `/`: nothing to keep apart, and git won't take it anyway.
    if (base.isEmpty()) return base
    return generateSequence(1) { it + 1 }
      .map { if (it == 1) base else "$base-$it" }
      .first { name -> siblings.none { it.name == name || it.displayName == name } && !taken(name) }
  }

  /** Inserts [created] after [parentId] and everything already nested under it. */
  fun insertUnder(sessions: List<Session>, parentId: SessionId, created: Session): List<Session> {
    val parentIndex = sessions.indexOfFirst { it.id == parentId }
    if (parentIndex < 0) return sessions + created
    val byId = sessions.associateBy { it.id }
    fun isUnderParent(session: Session): Boolean = generateSequence(session.parentId) { byId[it]?.parentId }.any { it == parentId }
    var index = parentIndex + 1
    while (index < sessions.size && isUnderParent(sessions[index])) index++
    return sessions.toMutableList().apply { add(index, created) }
  }

  /**
   * Puts [session] back among its siblings where it was: right before [before] if that's still one of
   * them, else right after [after] and everything nested under it, else last under its parent, or
   * last of all for a session without one.
   */
  fun insertBack(sessions: List<Session>, session: Session, after: SessionId?, before: SessionId?): List<Session> {
    val siblings = sessions.filter { it.parentId == session.parentId }
    val next = siblings.firstOrNull { it.id == before }
    if (next != null) return sessions.toMutableList().apply { add(sessions.indexOf(next), session) }
    val previous = siblings.firstOrNull { it.id == after }
    if (previous != null) return insertUnder(sessions, previous.id, session)
    val parentId = session.parentId ?: return sessions + session
    return insertUnder(sessions, parentId, session)
  }

  /** [id] and every session nested under it. */
  fun subtree(sessions: List<Session>, id: SessionId): Set<SessionId> {
    val result = mutableSetOf(id)
    var grew = true
    while (grew) {
      grew = false
      sessions.forEach { if (it.parentId in result && result.add(it.id)) grew = true }
    }
    return result
  }
}
