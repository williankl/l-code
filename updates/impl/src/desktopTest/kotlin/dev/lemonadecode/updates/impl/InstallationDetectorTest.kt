package dev.lemonadecode.updates.impl

import dev.lemonadecode.domain.update.AppVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstallationDetectorTest {
  private val version = AppVersion(1, 2, 0)

  private fun detect(
    os: String,
    appPath: String?,
    arch: String = "amd64",
    appVersion: String? = "1.2.0",
    existing: Set<String> = emptySet(),
  ) = InstallationDetector.detect(os, arch, appPath, appVersion, existing::contains)

  @Test
  fun `a build without a jpackage launcher is a development build`() {
    val dev = detect("Linux", appPath = null, appVersion = null)
    assertEquals(InstallKind.Development, dev.kind)
    assertNull(dev.version)
  }

  @Test
  fun `a mac bundle updates from the DMG for its processor`() {
    val path = "/Applications/Lemonade Code.app/Contents/MacOS/Lemonade Code"
    assertEquals(Installation(version, InstallKind.MacApp("/Applications/Lemonade Code.app", "arm64")), detect("Mac OS X", path, arch = "aarch64"))
    assertEquals(InstallKind.MacApp("/Applications/Lemonade Code.app", "x64"), detect("Mac OS X", path, arch = "x86_64").kind)
  }

  @Test
  fun `a mac app with a Homebrew cask is left to Homebrew`() {
    val path = "/Applications/Lemonade Code.app/Contents/MacOS/Lemonade Code"
    assertEquals(InstallKind.Homebrew, detect("Mac OS X", path, arch = "aarch64", existing = setOf("/opt/homebrew/Caskroom/lemonade-code")).kind)
    assertEquals(InstallKind.Homebrew, detect("Mac OS X", path, arch = "x86_64", existing = setOf("/usr/local/Caskroom/lemonade-code")).kind)
  }

  @Test
  fun `on Linux, snaps are left to the Snap Store`() {
    assertEquals(InstallKind.Snap, detect("Linux", "/snap/lemonade-code/12/opt/lemonade-code/bin/Lemonade Code").kind)
  }

  @Test
  fun `on Linux, the deb's folder with dpkg's file list is the deb`() {
    val launcher = "/opt/lemonade-code/bin/Lemonade Code"
    assertEquals(InstallKind.Deb(launcher), detect("Linux", launcher, existing = setOf("/var/lib/dpkg/info/lemonade-code.list")).kind)
  }

  @Test
  fun `on Linux, any other app folder updates from the tar_gz`() {
    val launcher = "/home/you/.local/opt/lemonade-code/bin/Lemonade Code"
    assertEquals(InstallKind.LinuxFolder("/home/you/.local/opt/lemonade-code", launcher), detect("Linux", launcher).kind)
    // Copied out of /opt without dpkg knowing about it.
    assertEquals(InstallKind.LinuxFolder("/opt/lemonade-code", "/opt/lemonade-code/bin/Lemonade Code"), detect("Linux", "/opt/lemonade-code/bin/Lemonade Code").kind)
  }

  @Test
  fun `Windows updates from the MSI`() {
    val launcher = "C:\\Users\\you\\AppData\\Local\\Lemonade Code\\Lemonade Code.exe"
    assertEquals(InstallKind.WindowsMsi(launcher), detect("Windows 11", launcher).kind)
  }

  @Test
  fun `processors without a build are unsupported`() {
    assertEquals(InstallKind.Unsupported, detect("Linux", "/home/you/lemonade-code/bin/Lemonade Code", arch = "aarch64").kind)
    assertEquals(InstallKind.Unsupported, detect("FreeBSD", "/usr/local/lemonade-code/bin/Lemonade Code").kind)
  }
}
