package dev.lemonadecode.domain.session

/**
 * A session put away with the sub-sessions under it. Its terminal has ended, but its name, folder,
 * worktree and place in the tree are kept, and so is the agent that ran in it, which picks up where
 * it left off when the session comes back. Its status no longer changes.
 */
public data class ArchivedSession(
  /** The session as it was when archived. */
  val session: Session,
  /** The sub-sessions archived with it, at any depth, in display order. */
  val subSessions: List<Session> = emptyList(),
) {
  val id: SessionId get() = session.id

  /** It, then its sub-sessions. */
  val sessions: List<Session> get() = listOf(session) + subSessions
}
