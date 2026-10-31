package dev.loupe.platform.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

/** Small persistent key/value store for app preferences, one JSON value per key. */
public interface PreferenceStore {
  public val entries: Flow<Map<String, JsonElement>>

  public suspend fun snapshot(): Map<String, JsonElement>

  public suspend fun update(transform: (Map<String, JsonElement>) -> Map<String, JsonElement>)
}
