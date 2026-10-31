package dev.loupe.platform.terminal

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertTrue

/** Text sits on the cell grid, whatever width the font draws a character at. */
class TerminalViewGridTest {
  private val colors = TerminalColors(Color.White, Color.Black, Color.Yellow, Color.Black, List(16) { Color.Gray })
  private val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

  @Test
  fun `text after a symbol the font draws wide stays in its cells`() = runBlocking<Unit> {
    // Claude starts its messages with ⏺, which this font draws over one and a half cells.
    val script = "printf '\\342\\217\\272 M'; sleep 30"
    TerminalSession(TerminalLaunch(command = listOf("/bin/sh", "-c", script), workingDirectory = System.getProperty("java.io.tmpdir"))).use { session ->
      var cellWidth = 0f
      var cellHeight = 0f
      ImageComposeScene(width = 400, height = 100, density = Density(1f)) {
        val layout = rememberTextMeasurer().measure(AnnotatedString("M".repeat(100)), style, softWrap = false)
        cellWidth = layout.size.width / 100f
        cellHeight = layout.size.height.toFloat()
        TerminalView(session, colors, style)
      }.use { scene ->
        withTimeout(5_000) {
          while (!session.firstLine().contains("M")) delay(20)
        }
        repeat(3) { val _ = scene.render(it * 16_000_000L) }
        val bitmap = Bitmap.makeFromImage(scene.render(100_000_000L))
        val column = session.firstLine().indexOf('M')
        fun ink(from: Float, to: Float) = (0 until cellHeight.toInt()).sumOf { y ->
          ((from * cellWidth).toInt() until (to * cellWidth).toInt()).count { x -> (bitmap.getColor(x, y) and 0xFF) > 120 }
        }
        assertTrue(ink(column + 0.2f, column + 0.5f) > 0, "the M should be drawn in its own cell, column $column")
        assertTrue(ink(column + 1.15f, column + 1.6f) == 0, "nothing should spill into the next cell")
      }
    }
  }

  private fun TerminalSession.firstLine(): String {
    textBuffer.lock()
    return try {
      textBuffer.getLine(0).text
    } finally {
      textBuffer.unlock()
    }
  }
}
