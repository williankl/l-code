package dev.lemonadecode.settings.api

import dev.lemonadecode.domain.settings.AppSettings
import kotlinx.coroutines.flow.StateFlow

/** The user's preferences, kept across launches. */
public interface SettingsService {
  public val settings: StateFlow<AppSettings>

  public suspend fun update(transform: (AppSettings) -> AppSettings)
}
