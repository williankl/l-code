package dev.lemonadecode.domain.settings

import dev.lemonadecode.domain.session.SubSessionLocation

public enum class ThemePreference { Dark, Light, System }

public data class AppSettings(
  val theme: ThemePreference = ThemePreference.Dark,
  val keepAwake: Boolean = false,
  /** The location the new sub-session panel opened with last time. */
  val subSessionLocation: SubSessionLocation = SubSessionLocation.SameFolder,
  val sidebarExpanded: Boolean = true,
)
