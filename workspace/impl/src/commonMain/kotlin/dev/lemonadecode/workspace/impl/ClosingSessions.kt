package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.session.LinkedWorktree
import dev.lemonadecode.domain.session.Session
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.platform.process.abbreviateHome
import dev.lemonadecode.workspace.uimodel.CloseSessionDialogModel
import dev.lemonadecode.workspace.uimodel.ClosingWorktreeModel

/** A session about to close, and the worktrees that leaves unused, while the dialog asks about them. */
internal data class ClosingSession(
  val sessionId: SessionId,
  val name: String,
  val subSessionCount: Int,
  val worktrees: List<LinkedWorktree>,
)

/** Which worktrees closing sessions leaves with no session in them, and the dialog asking about them. */
internal object ClosingSessions {
  /**
   * The worktrees the closing sessions' programs run in ([found], one per session, null for none),
   * each once, less those a session in [staying] still uses.
   */
  fun leftUnused(found: List<LinkedWorktree?>, staying: List<Session>): List<LinkedWorktree> = found
    .filterNotNull()
    .distinctBy { it.path }
    .filter { worktree -> staying.none { it.uses(worktree) } }

  /**
   * It is in [worktree]: by where it works, its agent's folder included, by the checkout git found
   * that in, by where its programs run, where an agent working elsewhere keeps its conversation, or
   * by where any agent running in it works, a suspended one too, which carries on there after `fg`.
   */
  private fun Session.uses(worktree: LinkedWorktree): Boolean = (listOfNotNull(workingDirectory, repositoryRoot, processDirectory) + agentDirectories).any(worktree::contains)

  fun dialog(closing: ClosingSession, home: String): CloseSessionDialogModel = CloseSessionDialogModel(
    sessionName = closing.name,
    subSessionCount = closing.subSessionCount,
    worktrees = closing.worktrees.map { ClosingWorktreeModel(path = abbreviateHome(it.path, home), branch = it.branch, hasChanges = it.hasChanges) },
  )
}
