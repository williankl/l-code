package dev.loupe.sessions.impl

import dev.loupe.domain.session.Session
import dev.loupe.domain.session.SessionId
import dev.loupe.domain.session.SessionPlacement
import dev.loupe.domain.session.SubSessionLocation
import dev.loupe.domain.session.SubSessionPlan

/**
 * Names and places sub-sessions. Worktrees go next to the repository, in a
 * `<repo>.worktrees/` folder, on a branch named after the parent's branch and the sub-session.
 */
internal object SubSessionPlanner {
  fun plan(parent: Session, existing: List<Session>, location: SubSessionLocation, prefix: String): SubSessionPlan? {
    val name = nextName(existing, prefix)
    return when (location) {
      SubSessionLocation.SameFolder -> SubSessionPlan(
        name = name,
        location = location,
        workingDirectory = parent.workingDirectory,
        branch = parent.branch,
        createsBranch = false,
        baseBranch = parent.branch,
      )
      SubSessionLocation.NewWorktree -> {
        val root = parent.repositoryRoot ?: return null
        val folder = if (parent.placement == SessionPlacement.Worktree) {
          root.substringBeforeLast('/')
        } else {
          "${root.substringBeforeLast('/')}/${root.substringAfterLast('/')}.worktrees"
        }
        SubSessionPlan(
          name = name,
          location = location,
          workingDirectory = "$folder/$name",
          branch = parent.branch?.let { "$it-$name" } ?: "loupe/$name",
          createsBranch = true,
          baseBranch = parent.branch,
        )
      }
    }
  }

  fun nextName(existing: List<Session>, prefix: String): String = generateSequence(1) { it + 1 }.map { "$prefix-$it" }.first { name -> existing.none { it.name == name } }

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
