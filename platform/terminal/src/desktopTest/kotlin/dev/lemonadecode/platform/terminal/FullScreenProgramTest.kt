package dev.lemonadecode.platform.terminal

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import com.jediterm.terminal.emulator.mouse.MouseFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Programs that take over the whole screen, like Claude Code's fullscreen mode: the alternate screen
 * keeps no scrollback, and the wheel goes to a program that asked for mouse reports.
 */
@OptIn(ExperimentalComposeUiApi::class)
class FullScreenProgramTest {
  private val tmp = System.getProperty("java.io.tmpdir")

  /** Draws a full-screen frame tagged [tag], ending with "[tag] status", from the top-left corner. */
  private fun frame(tag: String) = "printf '\\033[H'; i=1; while [ \$i -le 9 ]; do printf '$tag row %d\\r\\n' \$i; i=\$((i+1)); done; printf '$tag status'"

  private val shellLines = "printf 'shell 1\\nshell 2\\nshell 3\\n'"

  @Test
  fun `repaints and scrolling on the alternate screen leave no scrollback`() = runBlocking<Unit> {
    // Draw, erase and draw again (what a full repaint does), then write past the bottom so it scrolls.
    val script = "$shellLines; printf '\\033[?1049h'; ${frame("A")}; printf '\\033[2J'; ${frame("B")}; printf '\\r\\nmore 1\\r\\nmore 2\\r\\nlast'; sleep 30"
    TerminalSession(TerminalLaunch(listOf("/bin/sh", "-c", script), tmp, columns = 40, rows = 10)).use { session ->
      session.awaitScreen("last")

      assertTrue(session.textBuffer.isUsingAlternateBuffer)
      assertEquals(0, session.scrollbackLines)
      val output = session.recentOutput(100)
      assertFalse(output.any { it.startsWith("A ") }, "the first frame is gone, not filed above: $output")
      assertEquals("last", output.last())
    }
  }

  @Test
  fun `leaving the alternate screen brings the shell's lines back`() = runBlocking<Unit> {
    val script = "$shellLines; printf '\\033[?1049h'; ${frame("A")}; printf '\\033[2J'; ${frame("B")}; printf '\\033[?1049l'; printf 'back'; sleep 30"
    TerminalSession(TerminalLaunch(listOf("/bin/sh", "-c", script), tmp, columns = 40, rows = 10)).use { session ->
      session.awaitScreen("back")

      assertFalse(session.textBuffer.isUsingAlternateBuffer)
      val output = session.recentOutput(100)
      assertEquals(listOf("shell 1", "shell 2", "shell 3", "back"), output)
    }
  }

  @Test
  fun `the wheel is reported to a program that asked for mouse reports`() = runBlocking<Unit> {
    TerminalSession(TerminalLaunch(listOf("/bin/sh", "-c", echoMouseReports), tmp, columns = 60, rows = 10)).use { session ->
      session.awaitScreen("ready")

      assertTrue(session.reportsMouse)
      assertTrue(session.sendWheel(column = 2, row = 1, up = true, turns = 1))

      session.awaitScreen("033 [ < 6 4 ; 3 ; 2 M")
    }
  }

  @Test
  fun `the wheel isn't sent to a program that didn't ask`() = runBlocking<Unit> {
    TerminalSession(TerminalLaunch(listOf("/bin/sh", "-c", "printf 'ready'; sleep 30"), tmp, columns = 40, rows = 5)).use { session ->
      session.awaitScreen("ready")

      assertFalse(session.reportsMouse)
      assertFalse(session.sendWheel(column = 0, row = 0, up = true, turns = 1))
    }
  }

  @Test
  fun `turning the wheel over the view reports it instead of scrolling`() = runBlocking<Unit> {
    TerminalSession(TerminalLaunch(listOf("/bin/sh", "-c", echoMouseReports), tmp, columns = 60, rows = 10)).use { session ->
      val colors = TerminalColors(Color.White, Color.Black, Color.Yellow, Color.Black, List(16) { Color.Gray })
      val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
      ImageComposeScene(width = 600, height = 200, density = Density(1f)) {
        TerminalView(session, colors, style)
      }.use { scene ->
        var time = 0L
        fun frame() {
          repeat(3) {
            val _ = scene.render(time * 1_000_000L)
            time += 16
          }
        }
        frame()
        session.awaitScreen("ready")

        // Downwards, over the top-left cell.
        scene.sendPointerEvent(PointerEventType.Scroll, Offset(2f, 2f), scrollDelta = Offset(0f, 1f), timeMillis = time)
        frame()

        session.awaitScreen("033 [ < 6 5 ; 1 ; 1 M")
      }
    }
  }

  @Test
  fun `reports come in the format the program asked for`() {
    assertEquals("\u001b[<64;3;2M", mouseReport(MouseFormat.MOUSE_FORMAT_SGR, 64, 3, 2).decodeToString())
    assertEquals("\u001b[97;10;20M", mouseReport(MouseFormat.MOUSE_FORMAT_URXVT, 65, 10, 20).decodeToString())
    assertContentEquals(byteArrayOf(27, '['.code.toByte(), 'M'.code.toByte(), 96, 35, 34), mouseReport(MouseFormat.MOUSE_FORMAT_XTERM, 64, 3, 2))
    assertEquals(255.toByte(), mouseReport(MouseFormat.MOUSE_FORMAT_XTERM, 64, 400, 2)[4], "the original format stops at 223")
    assertEquals("\u001b[M`Ŭ\"", mouseReport(MouseFormat.MOUSE_FORMAT_XTERM_EXT, 64, 332, 2).decodeToString())
  }

  /**
   * Turns on button tracking with SGR reports, says ready, then shows the next 10 bytes it reads
   * (one SGR wheel report near the top-left) with `od`.
   */
  private val echoMouseReports = "stty -icanon -echo min 1; printf '\\033[?1000h\\033[?1006hready\\n'; head -c 10 | od -An -c; sleep 30"

  private suspend fun TerminalSession.awaitScreen(text: String) {
    withTimeout(10_000) {
      while (!screenText().replace(Regex("\\s+"), " ").contains(text)) delay(20)
    }
  }

  private fun TerminalSession.screenText(): String {
    textBuffer.lock()
    try {
      return (0 until textBuffer.height).joinToString("\n") { textBuffer.getLine(it).text }
    } finally {
      textBuffer.unlock()
    }
  }
}
