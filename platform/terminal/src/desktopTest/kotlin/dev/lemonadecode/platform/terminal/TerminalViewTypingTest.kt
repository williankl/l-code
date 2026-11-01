package dev.lemonadecode.platform.terminal

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import javax.swing.JPanel
import kotlin.test.Test
import java.awt.event.KeyEvent as AwtKeyEvent

class TerminalViewTypingTest {
  private val colors = TerminalColors(Color.White, Color.Black, Color.Yellow, Color.Black, List(16) { Color.Gray })
  private val source = JPanel()

  @Test
  fun `keys typed into the view reach the program`() = runBlocking<Unit> {
    TerminalSession(TerminalLaunch(command = listOf("/bin/cat"), workingDirectory = System.getProperty("java.io.tmpdir"))).use { session ->
      val focus = FocusRequester()
      ImageComposeScene(width = 800, height = 400) {
        TerminalView(session, colors, TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp), focusRequester = focus)
        LaunchedEffect(Unit) { focus.requestFocus() }
      }.use { scene ->
        var time = 0L
        fun frames() = repeat(5) {
          scene.render(time)
          time += 16_000_000L
        }
        frames()
        "hi".forEach { char ->
          scene.sendKeyEvent(key(AwtKeyEvent(source, AwtKeyEvent.KEY_PRESSED, 0, 0, AwtKeyEvent.getExtendedKeyCodeForChar(char.code), char)))
          scene.sendKeyEvent(key(AwtKeyEvent(source, AwtKeyEvent.KEY_TYPED, 0, 0, AwtKeyEvent.VK_UNDEFINED, char)))
        }
        scene.sendKeyEvent(key(AwtKeyEvent(source, AwtKeyEvent.KEY_PRESSED, 0, 0, AwtKeyEvent.VK_ENTER, '\n')))
        scene.sendKeyEvent(key(AwtKeyEvent(source, AwtKeyEvent.KEY_TYPED, 0, 0, AwtKeyEvent.VK_UNDEFINED, '\n')))
        withTimeout(5_000) {
          while (session.screen().lines().count { it.trimEnd() == "hi" } < 2) {
            frames()
            delay(20)
          }
        }
      }
    }
  }

  /** Wraps an AWT event the way the window does, so the view sees what it would see for real. */
  @OptIn(InternalComposeUiApi::class)
  private fun key(event: AwtKeyEvent): KeyEvent = KeyEvent(
    key = Key.Unknown,
    type = if (event.id == AwtKeyEvent.KEY_PRESSED) KeyEventType.KeyDown else KeyEventType.Unknown,
    codePoint = event.keyChar.code,
    nativeEvent = event,
  )

  private fun TerminalSession.screen(): String {
    textBuffer.lock()
    return try {
      textBuffer.getScreenLines()
    } finally {
      textBuffer.unlock()
    }
  }
}
