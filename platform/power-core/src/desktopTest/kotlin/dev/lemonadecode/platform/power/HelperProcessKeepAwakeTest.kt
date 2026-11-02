package dev.lemonadecode.platform.power

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelperProcessKeepAwakeTest {
  private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

  @Test
  fun `letting go stops the helper and every process under it`() {
    if (isWindows) return
    // Like the Linux helper: a waiting child (GNOME's inhibitor) under the top process.
    val keepAwake = HelperProcessKeepAwake { listOf("sh", "-c", "sh -c 'sleep 60'; sleep 60") }

    keepAwake.setHeld(true)
    assertTrue(keepAwake.isHeld)
    val tree = awaitDescendants()

    keepAwake.setHeld(false)

    assertFalse(keepAwake.isHeld)
    tree.forEach { runCatching { it.onExit().get(5, TimeUnit.SECONDS) } }
    assertTrue(tree.none { it.isAlive }, "left running: ${tree.filter { it.isAlive }.map { it.info().commandLine() }}")
  }

  @Test
  fun `holding twice starts one helper`() {
    if (isWindows) return
    var starts = 0
    val keepAwake = HelperProcessKeepAwake {
      starts++
      listOf("sleep", "60")
    }

    keepAwake.setHeld(true)
    keepAwake.setHeld(true)
    keepAwake.setHeld(false)

    assertEquals(1, starts)
    assertFalse(keepAwake.isHeld)
  }

  @Test
  fun `no helper for this system means nothing is held`() {
    val keepAwake = HelperProcessKeepAwake { null }

    keepAwake.setHeld(true)

    assertFalse(keepAwake.isHeld)
  }

  /** The helper's processes once its inner `sh` has started its `sleep`. */
  private fun awaitDescendants(): List<ProcessHandle> {
    val deadline = System.nanoTime() + 5_000_000_000
    while (System.nanoTime() < deadline) {
      val tree = ProcessHandle.current().descendants().toList()
      if (tree.any { it.info().command().orElse("").endsWith("sleep") }) return tree
      Thread.sleep(20)
    }
    error("the helper's child never started")
  }
}
