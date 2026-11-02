package dev.lemonadecode.mobile.impl

import dev.lemonadecode.platform.architecture.testing.FakeLemonadeCodeCoroutineDispatchers
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
  private val files = TempDeviceFiles(FakeLemonadeCodeCoroutineDispatchers(), root)

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
