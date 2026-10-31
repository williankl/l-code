package dev.loupe.settings.api

import dev.loupe.domain.settings.AppSettings
import kotlinx.coroutines.flow.StateFlow

/** The user's preferences, kept across launches. */
public interface SettingsService {
  public val settings: StateFlow<AppSettings>

  public suspend fun update(transform: (AppSettings) -> AppSettings)
}
