package dev.lemonadecode.settings.fake

import dev.lemonadecode.domain.settings.AppSettings
import dev.lemonadecode.settings.api.SettingsService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

public class FakeSettingsService(initial: AppSettings = AppSettings()) : SettingsService {
  private val state = MutableStateFlow(initial)
  override val settings: StateFlow<AppSettings> = state

  public var updateCallCount: Int = 0
    private set

  override suspend fun update(transform: (AppSettings) -> AppSettings) {
    updateCallCount++
    state.value = transform(state.value)
  }
}
