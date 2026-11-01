package dev.lemonadecode.updates.impl

import dev.lemonadecode.domain.update.AppVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleaseChooserTest {
  private val current = AppVersion(1, 2, 0)
  private val mac = InstallKind.MacApp("/Applications/Lemonade Code.app", "arm64")

  @Test
  fun `the newest release above the current version wins`() {
    val chosen = ReleaseChooser.newest(listOf(release("v1.3.0"), release("v1.10.0"), release("v1.4.2")), current, mac::assetName)
    assertEquals(AppVersion(1, 10, 0), chosen?.version)
    assertEquals("Lemonade-Code-1.10.0-macos-arm64.dmg", chosen?.asset?.name)
    assertEquals(ReleaseChooser.ChecksumsFile, chosen?.checksums?.name)
    assertEquals("https://github.com/williankl/loupe/releases/tag/v1.10.0", chosen?.pageUrl)
  }

  @Test
  fun `drafts, pre-releases and releases that aren't newer are skipped`() {
    val releases = listOf(
      release("v1.5.0", draft = true),
      release("v1.4.0", prerelease = true),
      release("v1.2.0"),
      release("v1.1.9"),
      release("nightly"),
    )
    assertNull(ReleaseChooser.newest(releases, current, mac::assetName))
  }

  @Test
  fun `a release without this platform's file, or without checksums, doesn't count`() {
    val noMacFile = release("v1.5.0", files = listOf("Lemonade-Code-1.5.0-windows-x64.msi", ReleaseChooser.ChecksumsFile))
    val noChecksums = release("v1.4.0", files = listOf("Lemonade-Code-1.4.0-macos-arm64.dmg"))
    val stillUploading = release("v1.6.0").let { it.copy(assets = it.assets.map { asset -> asset.copy(complete = !asset.name.endsWith(".dmg")) }) }
    val good = release("v1.3.0")
    assertEquals(AppVersion(1, 3, 0), ReleaseChooser.newest(listOf(noMacFile, noChecksums, stillUploading, good), current, mac::assetName)?.version)
  }

  @Test
  fun `each installation looks for its own file`() {
    val version = AppVersion(2, 0, 1)
    assertEquals("Lemonade-Code-2.0.1-macos-x64.dmg", InstallKind.MacApp("/Applications/Lemonade Code.app", "x64").assetName(version))
    assertEquals("Lemonade-Code-2.0.1-windows-x64.msi", InstallKind.WindowsMsi("C:\\Lemonade Code.exe").assetName(version))
    assertEquals("lemonade-code_2.0.1_amd64.deb", InstallKind.Deb("/opt/lemonade-code/bin/Lemonade Code").assetName(version))
    assertEquals("lemonade-code-2.0.1-linux-x64.tar.gz", InstallKind.LinuxFolder("/apps/lc", "/apps/lc/bin/Lemonade Code").assetName(version))
    listOf(InstallKind.Homebrew, InstallKind.Snap, InstallKind.Development, InstallKind.Unsupported).forEach { assertNull(it.assetName(version)) }
  }

  @Test
  fun `checksums are read from a sha256sum listing`() {
    val hash = "a".repeat(64)
    val listing = """
      ${"b".repeat(64)}  Lemonade-Code-1.3.0-macos-x64.dmg
      ${hash.uppercase()} *Lemonade-Code-1.3.0-macos-arm64.dmg
      tooshort  lemonade-code_1.3.0_amd64.deb
    """.trimIndent()
    assertEquals(hash, ReleaseChooser.checksumOf(listing, "Lemonade-Code-1.3.0-macos-arm64.dmg"))
    assertNull(ReleaseChooser.checksumOf(listing, "lemonade-code_1.3.0_amd64.deb"))
    assertNull(ReleaseChooser.checksumOf(listing, "Lemonade-Code-1.3.0-windows-x64.msi"))
  }

  private fun release(
    tag: String,
    draft: Boolean = false,
    prerelease: Boolean = false,
    files: List<String> = listOf("Lemonade-Code-${tag.removePrefix("v")}-macos-arm64.dmg", ReleaseChooser.ChecksumsFile),
  ) = PublishedRelease(
    tag = tag,
    draft = draft,
    prerelease = prerelease,
    pageUrl = "https://github.com/williankl/loupe/releases/tag/$tag",
    assets = files.map { ReleaseAsset(it, "https://github.com/williankl/loupe/releases/download/$tag/$it", size = 100) },
  )
}
