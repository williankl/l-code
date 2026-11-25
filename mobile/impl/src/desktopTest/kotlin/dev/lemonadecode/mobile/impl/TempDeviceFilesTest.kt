package dev.lemonadecode.mobile.impl

import dev.lemonadecode.platform.architecture.testing.FakeLemonadeCodeCoroutineDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TempDeviceFilesTest {
  private val root = Files.createTempDirectory("lemonade-code-files").toFile()
  private val videos = File(root, "Movies/Lemonade Code")
  private val files = TempDeviceFiles(FakeLemonadeCodeCoroutineDispatchers(), root, recordings = videos)

  @Test
  fun `screenshots are named after the device and the time, and never overwrite each other`() = runTest {
    val first = File(files.saveScreenshot("Willian's Pixel", byteArrayOf(1, 2, 3)))
    val second = File(files.saveScreenshot("Willian's Pixel", byteArrayOf(4)))

    assertEquals(File(root, "screenshots"), first.parentFile)
    assertTrue(Regex("""Willian-s-Pixel-\d{8}-\d{6}\.png""").matches(first.name), first.name)
    assertTrue(first.isAbsolute)
    assertContentEquals(byteArrayOf(1, 2, 3), first.readBytes())
    assertTrue(first != second && second.exists())
  }

  @Test
  fun `recordings are kept in a videos folder, named like screenshots, each in a file of its own`() = runTest {
    val first = File(files.recordingPath("Willian's Pixel"))
    val second = File(files.recordingPath("Willian's Pixel"))

    assertEquals(videos, first.parentFile)
    assertTrue(Regex("""Willian-s-Pixel-\d{8}-\d{6}\.mp4""").matches(first.name), first.name)
    assertTrue(first.isAbsolute && first.exists(), "the name is taken at once")
    assertTrue(first != second && second.exists())
  }

  @Test
  fun `a name another recording takes between looking and making goes on to the next`() {
    var tries = 0
    // Another device of the same name starts recording in the same second, and gets there first.
    val file = reserveFile(root, "Pixel-8-20261125-101403", "mp4") { candidate ->
      if (tries++ == 0) {
        assertTrue(candidate.createNewFile())
      }
      candidate.createNewFile()
    }
    assertEquals("Pixel-8-20261125-101403-2.mp4", file.name)
    assertTrue(file.exists())
  }

  @Test
  fun `recordings started together never share a file`() = runTest {
    val paths = (1..20).map { async(Dispatchers.IO) { files.recordingPath("Pixel 8") } }.awaitAll()
    assertEquals(20, paths.toSet().size)
  }

  @Test
  fun `on Linux, recordings go to the videos folder the desktop names, in the user's language`() {
    val userDirs = "# Written by xdg-user-dirs-update\nXDG_DESKTOP_DIR=\"\$HOME/Schreibtisch\"\nXDG_VIDEOS_DIR=\"\$HOME/Vídeos\"\n"
    assertEquals("/home/ana/Vídeos", xdgVideosFolder("/home/ana", environment = null, userDirs = userDirs))
    assertEquals("/media/data/Filme", xdgVideosFolder("/home/ana", environment = "/media/data/Filme", userDirs = userDirs))
    assertEquals("/home/ana/Videos", xdgVideosFolder("/home/ana", environment = null, userDirs = "XDG_VIDEOS_DIR=\"\${HOME}/Videos/\""))
    assertNull(xdgVideosFolder("/home/ana", environment = null, userDirs = "XDG_VIDEOS_DIR=\"\$HOME/\""), "the home folder itself means none")
    assertNull(xdgVideosFolder("/home/ana", environment = null, userDirs = null))
  }

  @Test
  fun `a capture file is read once and removed`() = runTest {
    val path = files.capturePath()
    File(path).writeBytes(byteArrayOf(9, 9))
    assertContentEquals(byteArrayOf(9, 9), files.take(path))
    assertFalse(File(path).exists())

    val unwritten = files.capturePath()
    assertNull(files.take(unwritten), "an empty file means the tool wrote nothing")
    assertFalse(File(unwritten).exists())
  }
}
