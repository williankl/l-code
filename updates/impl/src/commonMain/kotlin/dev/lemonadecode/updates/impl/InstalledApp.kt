package dev.lemonadecode.updates.impl

import dev.lemonadecode.domain.update.AppVersion
import dev.lemonadecode.domain.update.UpdateChannel

/** The running app: its version and how it was installed. */
internal interface InstalledApp {
  /** Null for a development build. */
  val version: AppVersion?
  val kind: InstallKind
}

internal data class Installation(
  override val version: AppVersion?,
  override val kind: InstallKind,
) : InstalledApp

/** How the running app was installed, which decides whether and how it updates itself. */
internal sealed interface InstallKind {
  /** Run from the repository (`./gradlew run`). */
  data object Development : InstallKind

  /** A Homebrew cask: `brew upgrade` updates it. */
  data object Homebrew : InstallKind

  /** The Snap Store updates it. */
  data object Snap : InstallKind

  /** A system or processor no release has a build for. */
  data object Unsupported : InstallKind

  /** A bundle copied out of a DMG, e.g. `/Applications/Lemonade Code.app`. [arch] is `arm64` or `x64`. */
  data class MacApp(val bundle: String, val arch: String) : InstallKind

  /** Installed from the MSI; [launcher] is its `Lemonade Code.exe`. */
  data class WindowsMsi(val launcher: String) : InstallKind

  /** Installed from the .deb, in /opt/lemonade-code. */
  data class Deb(val launcher: String) : InstallKind

  /** The tar.gz unpacked into [root] (or any app folder), started by [launcher], `<root>/bin/Lemonade Code`. */
  data class LinuxFolder(val root: String, val launcher: String) : InstallKind
}

/**
 * The release file this installation updates from; null when it doesn't update itself. The names
 * are the ones `.github/workflows/build-*.yml` give the files.
 */
internal fun InstallKind.assetName(version: AppVersion): String? = when (this) {
  is InstallKind.MacApp -> "Lemonade-Code-$version-macos-$arch.dmg"
  is InstallKind.WindowsMsi -> "Lemonade-Code-$version-windows-x64.msi"
  is InstallKind.Deb -> "lemonade-code_${version}_amd64.deb"
  is InstallKind.LinuxFolder -> "lemonade-code-$version-linux-x64.tar.gz"
  InstallKind.Development, InstallKind.Homebrew, InstallKind.Snap, InstallKind.Unsupported -> null
}

/** How the installation gets new versions, as the settings show it. */
internal val InstallKind.channel: UpdateChannel
  get() = when (this) {
    InstallKind.Development -> UpdateChannel.Development
    InstallKind.Homebrew -> UpdateChannel.Homebrew
    InstallKind.Snap -> UpdateChannel.Snap
    InstallKind.Unsupported -> UpdateChannel.Unsupported
    is InstallKind.MacApp, is InstallKind.WindowsMsi, is InstallKind.Deb, is InstallKind.LinuxFolder -> UpdateChannel.SelfUpdate
  }
