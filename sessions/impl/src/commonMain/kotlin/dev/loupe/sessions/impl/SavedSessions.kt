package dev.loupe.sessions.impl

import dev.loupe.domain.session.SessionOrigin
import dev.loupe.domain.session.SessionPlacement
import kotlinx.serialization.Serializable

/** The sessions as Loupe left them, to bring back on the next launch, in display order. */
@Serializable
internal data class SavedSessions(val sessions: List<SavedSession> = emptyList())

/**
 * One session to bring back. [resume] is the command that picks up the agent that was running in
 * it, where it left off; [output] is what the terminal showed, for sessions without one.
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
