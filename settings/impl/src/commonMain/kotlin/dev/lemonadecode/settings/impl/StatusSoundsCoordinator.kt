package dev.lemonadecode.settings.impl

import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionStatus
import dev.lemonadecode.platform.architecture.AppInitializer
import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.platform.sound.Chime
import dev.lemonadecode.platform.sound.SoundPlayer
import dev.lemonadecode.sessions.api.SessionService
import dev.lemonadecode.settings.api.SettingsService
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * While status sounds are on, dings when any session's agent finishes working, and double-dings
 * when one starts waiting on you, whichever session is selected. A session seen for the first time,
 * just opened or brought back at launch, plays nothing until its status changes.
 */
@ContributesIntoSet(AppScope::class)
@Inject
internal class StatusSoundsCoordinator(
  private val settings: SettingsService,
  private val sessions: SessionService,
  private val player: SoundPlayer,
) : AppInitializer {
  override suspend fun run() {
    sessions.sessions
      .map { list -> list.associate { it.id to it.status } }
      .distinctUntilChanged()
      .chimes()
      .coalesced(CoalesceWindow)
      .collect { chime -> if (settings.settings.value.statusSounds) player.play(chime) }
  }

  internal companion object {
    /** Changes this close together play one sound. */
    val CoalesceWindow: Duration = 300.milliseconds
  }
}

/** The chime for each change in the sessions' statuses that calls for one. */
private fun Flow<Map<SessionId, SessionStatus>>.chimes(): Flow<Chime> = flow {
  var before = emptyMap<SessionId, SessionStatus>()
  collect { after ->
    chimeFor(before, after)?.let { emit(it) }
    before = after
  }
}

/**
 * A double ding if a session now needs input, else a ding if one went from working to idle, else
 * nothing. Sessions not in [before] are new: they set where they start from.
 */
private fun chimeFor(before: Map<SessionId, SessionStatus>, after: Map<SessionId, SessionStatus>): Chime? {
  var chime: Chime? = null
  for ((id, status) in after) {
    val was = before[id] ?: continue
    when {
      was == status -> Unit
      status == SessionStatus.NeedsInput -> return Chime.DoubleDing
      status == SessionStatus.Idle && was == SessionStatus.Working -> chime = Chime.Ding
    }
  }
  return chime
}

/**
 * Waits [window] after a chime for any more, then plays one for them all, a double ding if any was
 * one. So a burst plays once, and no two sounds come closer than [window].
 */
private fun Flow<Chime>.coalesced(window: Duration): Flow<Chime> = channelFlow {
  val pending = Channel<Chime>(Channel.UNLIMITED)
  launch {
    collect { pending.send(it) }
    pending.close()
  }
  for (first in pending) {
    delay(window)
    var chime = first
    while (true) {
      val next = pending.tryReceive().getOrNull() ?: break
      if (next == Chime.DoubleDing) chime = next
    }
    send(chime)
  }
}
