package dev.lemonadecode.domain.settings

import dev.lemonadecode.domain.session.SubSessionLocation

public enum class ThemePreference { Dark, Light, System }

/** The interface language. [code] is the BCP 47 tag; [System] follows the computer's own. */
public enum class AppLanguage(public val code: String?) {
  System(null),
  English("en"),
  German("de"),
  Spanish("es"),
  French("fr"),
  Portuguese("pt"),
  Slovak("sk"),
}

/** How a device's screen and logs share the Devices panel. */
public enum class DevicesLayout { SideBySide, Tabs }

public data class AppSettings(
  val theme: ThemePreference = ThemePreference.Dark,
  val keepAwake: Boolean = false,
  /** A ding when a session's agent finishes working, and a double ding when it needs input. */
  val statusSounds: Boolean = true,
  /** The location the new sub-session panel opened with last time. */
  val subSessionLocation: SubSessionLocation = SubSessionLocation.SameFolder,
  val sidebarExpanded: Boolean = true,
  /** The sessions sidebar's width in dp, as the user last dragged it. */
  val sidebarWidth: Int = 232,
  val language: AppLanguage = AppLanguage.System,
  /** The Mobile plugin: mirrors Android and iOS screens, pairs devices and reads their logs. */
  val mobilePlugin: Boolean = false,
  val devicesLayout: DevicesLayout = DevicesLayout.SideBySide,
  /** The docked Devices panel's width in dp, as last dragged, for the list, pairing and tabs. */
  val devicesPanelWidth: Int = 420,
  /** The same for a device's screen and logs together, which want more room. */
  val devicesPanelWideWidth: Int = 760,
)
