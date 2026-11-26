package dev.lemonadecode.sessions.impl

import dev.lemonadecode.domain.session.SessionOrigin
import dev.lemonadecode.domain.session.SessionPlacement
import kotlinx.serialization.Serializable

/**
 * The sessions as Lemonade Code left them, to bring back on the next launch, in display order, and
 * the archived ones, the most recently archived first. A file from before archiving has none.
 */
@Serializable
internal data class SavedSessions(
  val sessions: List<SavedSession> = emptyList(),
  val archived: List<SavedArchive> = emptyList(),
)

/**
 * A session archived with its sub-sessions: [sessions] holds it, then the sub-sessions under it in
 * display order. It sat right after the sibling [after] and right before [before], and goes back
 * there if it can.
 */
@Serializable
internal data class SavedArchive(
  val sessions: List<SavedSession> = emptyList(),
  val after: String? = null,
  val before: String? = null,
) {
  /** The archived session's own id. */
  val id: String? get() = sessions.firstOrNull()?.id
}

/**
 * One session to bring back. It starts again in [workingDirectory], where its programs ran: an
 * agent's conversation is kept by that folder, even when the agent said it worked in another.
 * [resume] is the command that picks up the agent that was running in it, where it left off;
 * [output] is what the terminal showed, for sessions without one.
 * Enums are stored as names, so a renamed value falls back instead of failing the whole file.
 */
@Serializable
internal data class SavedSession(
  val id: String,
  val name: String,
  val workingDirectory: String,
  val parentId: String? = null,
  val placement: String? = null,
  val origin: String? = null,
  val reviewCommentCount: Int? = null,
  val resume: String? = null,
  val output: List<String>? = null,
  /** The name the user gave it, if any. */
  val customName: String? = null,
  /** For an archived session, shown while it's archived: its checkout and branch, and the agent that ran in it. */
  val repositoryRoot: String? = null,
  val branch: String? = null,
  val agent: String? = null,
  /** For an archived session whose agent worked in another folder than [workingDirectory], that folder, shown while it's archived. */
  val agentDirectory: String? = null,
) {
  fun placement(): SessionPlacement = SessionPlacement.entries.firstOrNull { it.name == placement } ?: SessionPlacement.Root

  fun origin(): SessionOrigin = when (origin) {
    OriginSplit -> SessionOrigin.Split
    OriginReview -> SessionOrigin.Review(reviewCommentCount ?: 0)
    else -> SessionOrigin.Opened
  }

  companion object {
    private const val OriginOpened = "Opened"
    private const val OriginSplit = "Split"
    private const val OriginReview = "Review"

    fun originName(origin: SessionOrigin): String = when (origin) {
      SessionOrigin.Opened -> OriginOpened
      SessionOrigin.Split -> OriginSplit
      is SessionOrigin.Review -> OriginReview
    }
  }
}
