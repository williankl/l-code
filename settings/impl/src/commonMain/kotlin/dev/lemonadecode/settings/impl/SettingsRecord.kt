package dev.lemonadecode.settings.impl

import dev.lemonadecode.domain.session.SubSessionLocation
import dev.lemonadecode.domain.settings.AppLanguage
import dev.lemonadecode.domain.settings.AppSettings
import dev.lemonadecode.domain.settings.DevicesLayout
import dev.lemonadecode.domain.settings.ThemePreference
import kotlinx.serialization.Serializable

/** How settings are stored. Enums are written as names so a renamed value falls back to its default. */
@Serializable
internal data class SettingsRecord(
  val theme: String? = null,
  val keepAwake: Boolean? = null,
  val subSessionLocation: String? = null,
  val sidebarExpanded: Boolean? = null,
  val sidebarWidth: Int? = null,
  val language: String? = null,
  val mobilePlugin: Boolean? = null,
  val devicesLayout: String? = null,
  val devicesPanelWidth: Int? = null,
  val devicesPanelWideWidth: Int? = null,
)

internal fun SettingsRecord.toDomain(): AppSettings {
  val defaults = AppSettings()
  return AppSettings(
    theme = ThemePreference.entries.firstOrNull { it.name == theme } ?: defaults.theme,
    keepAwake = keepAwake ?: defaults.keepAwake,
    subSessionLocation = SubSessionLocation.entries.firstOrNull { it.name == subSessionLocation } ?: defaults.subSessionLocation,
    sidebarExpanded = sidebarExpanded ?: defaults.sidebarExpanded,
    sidebarWidth = sidebarWidth?.takeIf { it > 0 } ?: defaults.sidebarWidth,
    language = AppLanguage.entries.firstOrNull { it.name == language } ?: defaults.language,
    mobilePlugin = mobilePlugin ?: defaults.mobilePlugin,
    devicesLayout = DevicesLayout.entries.firstOrNull { it.name == devicesLayout } ?: defaults.devicesLayout,
    devicesPanelWidth = devicesPanelWidth?.takeIf { it > 0 } ?: defaults.devicesPanelWidth,
    devicesPanelWideWidth = devicesPanelWideWidth?.takeIf { it > 0 } ?: defaults.devicesPanelWideWidth,
  )
}

internal fun AppSettings.toRecord(): SettingsRecord = SettingsRecord(
  theme = theme.name,
  keepAwake = keepAwake,
  subSessionLocation = subSessionLocation.name,
  sidebarExpanded = sidebarExpanded,
  sidebarWidth = sidebarWidth,
  language = language.name,
  mobilePlugin = mobilePlugin,
  devicesLayout = devicesLayout.name,
  devicesPanelWidth = devicesPanelWidth,
  devicesPanelWideWidth = devicesPanelWideWidth,
)
