package dev.lemonadecode.settings.impl

import dev.lemonadecode.domain.session.SubSessionLocation
import dev.lemonadecode.domain.settings.AppSettings
import dev.lemonadecode.domain.settings.ThemePreference
import dev.lemonadecode.platform.preferences.testing.FakePreferenceStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsServiceImplTest {
  @Test
  fun `updates are stored and read back`() = runTest {
    val store = FakePreferenceStore()
    val service = SettingsServiceImpl(store, backgroundScope)
    service.update {
      it.copy(theme = ThemePreference.Light, subSessionLocation = SubSessionLocation.NewWorktree, sidebarWidth = 320, devicesPanelWidth = 480, devicesPanelWideWidth = 900)
    }

    val reloaded = SettingsServiceImpl(store, backgroundScope)
    val settings = reloaded.settings.first { it.theme == ThemePreference.Light }
    assertEquals(SubSessionLocation.NewWorktree, settings.subSessionLocation)
    assertEquals(320, settings.sidebarWidth)
    assertEquals(480, settings.devicesPanelWidth)
    assertEquals(900, settings.devicesPanelWideWidth)
  }

  @Test
  fun `unknown values fall back to defaults`() = runTest {
    val store = FakePreferenceStore(
      mapOf(
        "settings" to buildJsonObject {
          put("theme", JsonPrimitive("Sepia"))
          put("keepAwake", JsonPrimitive(true))
          put("sidebarWidth", JsonPrimitive(-40))
        },
      ),
    )
    val settings = SettingsServiceImpl(store, backgroundScope).settings.first { it.keepAwake }
    assertEquals(AppSettings().theme, settings.theme)
    assertEquals(AppSettings().sidebarWidth, settings.sidebarWidth)
  }

  @Test
  fun `status sounds are on until turned off, and stay off once they are`() = runTest {
    // Settings saved before status sounds existed have no value for them.
    val store = FakePreferenceStore(mapOf("settings" to buildJsonObject { put("keepAwake", JsonPrimitive(true)) }))
    val service = SettingsServiceImpl(store, backgroundScope)
    assertTrue(service.settings.first { it.keepAwake }.statusSounds)
    assertTrue(SettingsServiceImpl(FakePreferenceStore(), backgroundScope).settings.value.statusSounds)

    service.update { it.copy(statusSounds = false) }

    assertEquals(JsonPrimitive(false), store.snapshot().getValue("settings").jsonObject["statusSounds"])
    val reloaded = SettingsServiceImpl(store, backgroundScope)
    assertFalse(reloaded.settings.first { it.keepAwake }.statusSounds)
  }

  @Test
  fun `a record keeps every setting as it was`() {
    val settings = AppSettings(keepAwake = true, statusSounds = false, theme = ThemePreference.System, mobilePlugin = true)
    assertEquals(settings, settings.toRecord().toDomain())
    assertEquals(AppSettings(), SettingsRecord().toDomain())
  }
}
