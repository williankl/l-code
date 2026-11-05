package dev.lemonadecode.platform.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** The program hears about the size the view settles on, not every size it passes through. */
class TerminalViewResizeTest {
  private val colors = TerminalColors(Color.White, Color.Black, Color.Yellow, Color.Black, List(16) { Color.Gray })
  private val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

  @Test
  fun `a size the view only passes through never reaches the program`() = runBlocking<Unit> {
    // Prints "rows columns" each time the program is told the terminal's size changed.
    val script = "trap 'stty size' WINCH; while :; do sleep 0.05; done"
    TerminalSession(TerminalLaunch(command = listOf("/bin/sh", "-c", script), workingDirectory = System.getProperty("java.io.tmpdir"))).use { session ->
      var width by mutableIntStateOf(400)
      ImageComposeScene(width = 1000, height = 200, density = Density(1f)) {
        Box(modifier = Modifier.width(width.dp)) { TerminalView(session, colors, style) }
      }.use { scene ->
        var time = 0L
        fun frame() {
          Snapshot.sendApplyNotifications()
          val _ = scene.render(time)
          time += 16_000_000L
        }
        repeat(3) { frame() }
        // The first size goes straight through.
        withTimeout(5_000) { while (session.reportedColumns().size < 1) delay(20) }
        val first = session.reportedColumns().single()

        // Two widths in quick succession, as when a panel glides open.
        width = 600
        frame()
        width = 800
        frame()
        withTimeout(5_000) { while (session.reportedColumns().size < 2) delay(20) }
        delay(300)

        val reported = session.reportedColumns()
        assertEquals(2, reported.size, "expected the first size and the last one, got $reported")
        assertEquals(first, reported.first())
        assertEquals(session.columns, reported.last())
      }
    }
  }

  /** The column counts `stty size` printed, in order. */
  private fun TerminalSession.reportedColumns(): List<Int> {
    textBuffer.lock()
    val lines = try {
      (0 until textBuffer.height).map { textBuffer.getLine(it).text.trim() }
    } finally {
      textBuffer.unlock()
    }
    return lines.mapNotNull { line -> Regex("""^\d+ (\d+)$""").find(line)?.groupValues?.get(1)?.toInt() }
  }
}
