package dev.lemonadecode.settings.impl

import dev.lemonadecode.domain.session.SubSessionLocation
import dev.lemonadecode.domain.settings.AppSettings
import dev.lemonadecode.domain.settings.ThemePreference
import dev.lemonadecode.platform.preferences.testing.FakePreferenceStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsServiceImplTest {
  @Test
  fun `updates are stored and read back`() = runTest {
    val store = FakePreferenceStore()
    val service = SettingsServiceImpl(store, backgroundScope)
    service.update { it.copy(theme = ThemePreference.Light, subSessionLocation = SubSessionLocation.NewWorktree) }

    val reloaded = SettingsServiceImpl(store, backgroundScope)
    val settings = reloaded.settings.first { it.theme == ThemePreference.Light }
    assertEquals(SubSessionLocation.NewWorktree, settings.subSessionLocation)
  }

  @Test
  fun `unknown values fall back to defaults`() = runTest {
    val store = FakePreferenceStore(
      mapOf(
        "settings" to buildJsonObject {
          put("theme", JsonPrimitive("Sepia"))
          put("keepAwake", JsonPrimitive(true))
        },
      ),
    )
    val settings = SettingsServiceImpl(store, backgroundScope).settings.first { it.keepAwake }
    assertEquals(AppSettings().theme, settings.theme)
  }
}
