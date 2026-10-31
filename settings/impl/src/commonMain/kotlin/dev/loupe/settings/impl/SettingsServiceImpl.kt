package dev.loupe.settings.impl

import dev.loupe.domain.settings.AppSettings
import dev.loupe.platform.di.AppScope
import dev.loupe.platform.preferences.PreferenceStore
import dev.loupe.settings.api.SettingsService
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
internal class SettingsServiceImpl(
  private val store: PreferenceStore,
  scope: CoroutineScope,
) : SettingsService {
  private val json = Json { ignoreUnknownKeys = true }

  override val settings: StateFlow<AppSettings> = store.entries
    .map { entries -> entries[Key]?.let(::decode) ?: AppSettings() }
    .stateIn(scope, SharingStarted.Eagerly, AppSettings())

  override suspend fun update(transform: (AppSettings) -> AppSettings) {
    store.update { entries ->
      val current = entries[Key]?.let(::decode) ?: AppSettings()
      entries + (Key to json.encodeToJsonElement(SettingsRecord.serializer(), transform(current).toRecord()))
    }
  }

  private fun decode(element: JsonElement): AppSettings = runCatching { json.decodeFromJsonElement(SettingsRecord.serializer(), element).toDomain() }.getOrDefault(AppSettings())

  private companion object {
    const val Key = "settings"
  }
}
