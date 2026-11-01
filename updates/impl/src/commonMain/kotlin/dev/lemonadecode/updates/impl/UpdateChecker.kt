package dev.lemonadecode.updates.impl

import dev.lemonadecode.platform.architecture.AppInitializer
import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.updates.api.UpdateService
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Looks for an update shortly after launch, once the sessions are back, then every few hours. */
@ContributesIntoSet(AppScope::class)
@Inject
internal class UpdateChecker(
  private val updates: UpdateService,
) : AppInitializer {
  override suspend fun run() {
    delay(FirstCheckDelay)
    while (true) {
      updates.check()
      delay(CheckInterval)
    }
  }

  private companion object {
    val FirstCheckDelay = 10.seconds
    val CheckInterval = 6.hours
  }
}
