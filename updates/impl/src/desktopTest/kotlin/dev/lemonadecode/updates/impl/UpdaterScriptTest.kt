package dev.lemonadecode.updates.impl

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs the helper script that swaps a Mac bundle or a Linux app folder, as the updater does. */
class UpdaterScriptTest {
  private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
  private val folder = Files.createTempDirectory("lemonade-code-swap-test").toFile()
  private val target = File(folder, "lemonade-code").apply { mkdirs() }.also { File(it, "version").writeText("1.2.0") }
  private val container = File(folder, ".lemonade-code-update").apply { mkdirs() }
  private val staged = File(container, "lemonade-code").apply { mkdirs() }.also { File(it, "version").writeText("1.3.0") }
  private val opened = File(folder, "opened")

  @Test
  fun `once the app has quit, the new folder takes the old one's place and the app opens`() {
    if (windows) return
    swap(staged)
    assertEquals("1.3.0", File(target, "version").readText())
    assertFalse(container.exists())
    assertEquals(listOf("lemonade-code", "opened"), folder.list()?.sorted())
    assertTrue(opened.exists())
  }

  @Test
  fun `if the new folder can't be moved in, the old one stays and still opens`() {
    if (windows) return
    swap(File(container, "missing"))
    assertEquals("1.2.0", File(target, "version").readText())
    assertTrue(opened.exists())
  }

  private fun swap(newFolder: File) {
    val script = File.createTempFile("lemonade-code-update", ".sh")
    script.writeBytes(checkNotNull(HelperUpdateInstaller::class.java.getResourceAsStream("swap.sh")).use { it.readBytes() })
    // A process that has already quit stands in for the app.
    val app = ProcessBuilder("true").start().apply { waitFor() }
    val helper = ProcessBuilder(
      "/bin/sh",
      script.path,
      app.pid().toString(),
      target.path,
      newFolder.path,
      container.path,
      "touch",
      opened.path,
    ).redirectErrorStream(true).start()
    assertTrue(helper.waitFor(10, TimeUnit.SECONDS), helper.inputStream.bufferedReader().readText())
    assertFalse(script.exists(), "the script removes itself")
  }
}
