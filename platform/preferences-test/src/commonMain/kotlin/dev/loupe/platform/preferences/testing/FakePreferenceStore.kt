package dev.loupe.platform.preferences.testing

import dev.loupe.platform.preferences.PreferenceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonElement

public class FakePreferenceStore(initial: Map<String, JsonElement> = emptyMap()) : PreferenceStore {
  private val state = MutableStateFlow(initial)

  public var updateCallCount: Int = 0
    private set

  override val entries: Flow<Map<String, JsonElement>> = state

  override suspend fun snapshot(): Map<String, JsonElement> = state.value

  override suspend fun update(transform: (Map<String, JsonElement>) -> Map<String, JsonElement>) {
    updateCallCount++
    state.value = transform(state.value)
  }
}
