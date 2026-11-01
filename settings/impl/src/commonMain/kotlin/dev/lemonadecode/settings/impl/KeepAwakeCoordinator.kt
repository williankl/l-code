package dev.lemonadecode.settings.impl

import dev.lemonadecode.domain.session.SessionStatus
import dev.lemonadecode.platform.architecture.AppInitializer
import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.platform.power.KeepAwake
import dev.lemonadecode.sessions.api.SessionService
import dev.lemonadecode.settings.api.SettingsService
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Holds the machine awake while keep-awake is on and at least one session is working. */
@ContributesIntoSet(AppScope::class)
@Inject
internal class KeepAwakeCoordinator(
  private val settings: SettingsService,
  private val sessions: SessionService,
  private val keepAwake: KeepAwake,
) : AppInitializer {
  override suspend fun run() {
    try {
      combine(settings.settings, sessions.sessions) { prefs, list ->
        prefs.keepAwake && list.any { it.status == SessionStatus.Working }
      }
        .distinctUntilChanged()
        .collect(keepAwake::setHeld)
    } finally {
      keepAwake.setHeld(false)
    }
  }
}
