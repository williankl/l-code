package dev.lemonadecode.domain.session

/**
 * The order sessions show in: each one followed by everything nested under it, at any depth. A
 * session moves among its siblings only, the sessions under the same parent (or the top-level
 * ones), and its sub-sessions always move with it.
 */
public object SessionOrder {
  /** The sessions under the same parent as [id], it included, in order; empty when it isn't in [sessions]. */
  public fun siblings(sessions: List<Session>, id: SessionId): List<Session> {
    val session = sessions.firstOrNull { it.id == id } ?: return emptyList()
    return sessions.filter { it.parentId == session.parentId }
  }

  /**
   * [sessions] with [id], and every session nested under it, moved to [index] among its siblings,
   * counted as they'll be once it has moved. It keeps its parent. An index past either end goes to
   * that end; the same place, or a session that isn't there, gives back [sessions] itself.
   */
  public fun move(sessions: List<Session>, id: SessionId, index: Int): List<Session> {
    val siblings = siblings(sessions, id)
    val from = siblings.indexOfFirst { it.id == id }
    if (from < 0) return sessions
    val to = index.coerceIn(0, siblings.lastIndex)
    if (to == from) return sessions
    val moving = subtree(sessions, id)
    val rest = sessions.filterNot { it.id in moving }
    val others = siblings.filterNot { it.id == id }
    val at = if (to < others.size) {
      rest.indexOfFirst { it.id == others[to].id }
    } else {
      // After the last sibling and everything nested under it.
      val last = subtree(rest, others.last().id)
      rest.indexOfLast { it.id in last } + 1
    }
    return rest.toMutableList().apply { addAll(at, sessions.filter { it.id in moving }) }
  }

  private fun subtree(sessions: List<Session>, id: SessionId): Set<SessionId> {
    val result = mutableSetOf(id)
    var grew = true
    while (grew) {
      grew = false
      sessions.forEach { if (it.parentId in result && result.add(it.id)) grew = true }
    }
    return result
  }
}
