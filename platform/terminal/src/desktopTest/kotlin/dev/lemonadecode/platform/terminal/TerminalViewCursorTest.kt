package dev.lemonadecode.platform.terminal

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertTrue

class TerminalViewCursorTest {
  private val colors = TerminalColors(Color.White, Color.Black, Color.Yellow, Color.Black, List(16) { Color.Gray })

  @Test
  fun `the cursor moves with the text`() = runBlocking<Unit> {
    TerminalSession(TerminalLaunch(command = listOf("/bin/cat"), workingDirectory = System.getProperty("java.io.tmpdir"))).use { session ->
      ImageComposeScene(width = 600, height = 200, density = Density(1f)) {
        TerminalView(session, colors, TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
      }.use { scene ->
        var time = 0L
        fun frame(): Image {
          repeat(3) {
            scene.render(time)
            time += 16_000_000L
          }
          return scene.render(time)
        }
        val before = cursorX(frame())
        session.send("abcdefgh")
        withTimeout(5_000) {
          while (!session.screen().contains("abcdefgh")) delay(20)
        }
        delay(100)
        val after = cursorX(frame())
        assertTrue(before != null && after != null && after > before + 40, "cursor stayed at x=$before, then x=$after")
      }
    }
  }

  @Test
  fun `the cursor blinks while focused and starts solid after typing`() = runBlocking<Unit> {
    TerminalSession(TerminalLaunch(command = listOf("/bin/cat"), workingDirectory = System.getProperty("java.io.tmpdir"))).use { session ->
      val focus = FocusRequester()
      ImageComposeScene(width = 600, height = 200, density = Density(1f)) {
        TerminalView(session, colors, TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp), focusRequester = focus)
        LaunchedEffect(Unit) { focus.requestFocus() }
      }.use { scene ->
        fun at(millis: Long): Image = scene.render(millis * 1_000_000L)
        repeat(5) { val _ = at(it * 16L) }
        val solid = (100L..400L step 100).map { cursorFilled(at(it)) }
        val blinked = (700L..1000L step 100).map { cursorFilled(at(it)) }
        val back = (1200L..1500L step 100).map { cursorFilled(at(it)) }
        assertTrue(solid.all { it }, "solid at first: $solid")
        assertTrue(blinked.all { !it }, "off in the second half: $blinked")
        assertTrue(back.all { it }, "on again: $back")
      }
    }
  }

  /** True when a solid cursor block is drawn, not just its outline. */
  private fun cursorFilled(image: Image): Boolean {
    val bitmap = Bitmap.makeFromImage(image)
    var count = 0
    for (x in 0 until bitmap.width) {
      for (y in 0 until bitmap.height) {
        val color = bitmap.getColor(x, y)
        if (((color shr 16) and 0xFF) > 200 && ((color shr 8) and 0xFF) > 200 && (color and 0xFF) < 80) count++
      }
    }
    return count > 60
  }

  /** Left edge of the yellow cursor block, or null when it isn't drawn. */
  private fun cursorX(image: Image): Int? {
    val bitmap = Bitmap.makeFromImage(image)
    for (x in 0 until bitmap.width) {
      for (y in 0 until bitmap.height) {
        val color = bitmap.getColor(x, y)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        if (r > 200 && g > 200 && b < 80) return x
      }
    }
    return null
  }

  private fun TerminalSession.screen(): String {
    textBuffer.lock()
    return try {
      textBuffer.getScreenLines()
    } finally {
      textBuffer.unlock()
    }
  }
}
