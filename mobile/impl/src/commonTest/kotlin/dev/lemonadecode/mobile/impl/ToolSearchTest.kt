package dev.lemonadecode.mobile.impl

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolSearchTest {
  @Test
  fun `on a Mac the PATH comes first, then the SDK, Homebrew and the login shell's PATH`() {
    val candidates = toolCandidates(
      HostOs.Mac,
      mapOf("PATH" to "/usr/bin:/bin", "ANDROID_HOME" to "/opt/android"),
      home = "/Users/me",
      loginShellPath = "/Users/me/.local/bin:/usr/bin",
    )
    assertEquals(
      listOf(
        "/usr/bin/adb",
        "/bin/adb",
        "/opt/android/platform-tools/adb",
        "/Users/me/Library/Android/sdk/platform-tools/adb",
        "/opt/homebrew/bin/adb",
        "/usr/local/bin/adb",
        "/Users/me/.local/bin/adb",
      ),
      candidates.adb,
    )
    assertEquals(
      listOf("/usr/bin/idevice_id", "/bin/idevice_id", "/opt/homebrew/bin/idevice_id", "/usr/local/bin/idevice_id", "/Users/me/.local/bin/idevice_id"),
      candidates.idevice,
    )
  }

  @Test
  fun `on Linux the SDK is in the home folder`() {
    val candidates = toolCandidates(HostOs.Linux, mapOf("ANDROID_SDK_ROOT" to "/sdk/"), home = "/home/me", loginShellPath = null)
    assertEquals(
      listOf("/sdk/platform-tools/adb", "/home/me/Android/Sdk/platform-tools/adb", "/opt/homebrew/bin/adb", "/usr/local/bin/adb", "/usr/bin/adb"),
      candidates.adb,
    )
  }

  @Test
  fun `on Windows the programs end in exe and the SDK is in the local app data`() {
    val candidates = toolCandidates(
      HostOs.Windows,
      mapOf("Path" to "C:\\Tools;\"C:\\Program Files\\libimobiledevice\"", "LOCALAPPDATA" to "C:\\Users\\me\\AppData\\Local"),
      home = "C:\\Users\\me",
      loginShellPath = "/ignored",
    )
    assertContentEquals(
      listOf("C:\\Tools\\adb.exe", "C:\\Program Files\\libimobiledevice\\adb.exe", "C:\\Users\\me\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe"),
      candidates.adb,
    )
    assertEquals(listOf("C:\\Tools\\idevice_id.exe", "C:\\Program Files\\libimobiledevice\\idevice_id.exe"), candidates.idevice)
  }

  @Test
  fun `folders and the login shell's answer`() {
    assertEquals("/opt/homebrew/bin", folderOf("/opt/homebrew/bin/idevice_id"))
    assertEquals("C:\\Tools", folderOf("C:\\Tools\\idevice_id.exe"))
    assertEquals("/usr/bin:/opt/homebrew/bin", parseLoginShellPath("Welcome!\n$LoginPathMarker/usr/bin:/opt/homebrew/bin$LoginPathMarker\n"))
    assertNull(parseLoginShellPath("zsh: command not found: echo"))
    assertEquals(HostOs.Mac, HostOs.of("Mac OS X"))
    assertEquals(HostOs.Windows, HostOs.of("Windows 11"))
    assertEquals(HostOs.Linux, HostOs.of("Linux"))
  }

  @Test
  fun `screenshot names are safe for files`() {
    assertEquals("Pixel-8-20261102-101403", screenshotName("Pixel 8", "20261102-101403"))
    assertEquals("Willian-s-iPhone-20261102-101403", screenshotName("Willian's iPhone", "20261102-101403"))
    assertEquals("device-20261102-101403", screenshotName("../", "20261102-101403"))
  }

  @Test
  fun `png sizes come from the header, after anything printed before it`() {
    val picture = png(1080, 2400)
    assertEquals(1080 to 2400, readPng(picture)?.let { it.width to it.height })
    val warned = "WARNING: linker: something\n".encodeToByteArray() + picture
    val found = readPng(warned)
    assertEquals(1080 to 2400, found?.let { it.width to it.height })
    assertContentEquals(picture, found?.bytes)
    assertNull(readPng("MM\u0000*not a png at all, a tiff".encodeToByteArray()))
    assertNull(readPng(ByteArray(0)))
  }
}
