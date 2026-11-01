package dev.lemonadecode.platform.terminal

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
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
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Links in a real terminal: found, underlined under the mouse, opened with Ctrl-click. */
@OptIn(ExperimentalComposeUiApi::class)
class TerminalViewLinksTest {
  private val colors = TerminalColors(Color.White, Color.Black, Color.Yellow, Color.Black, List(16) { Color.Gray })
  private val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
  private val opened = mutableListOf<String>()
  private val longUrl = "https://example.com/" + "a".repeat(100)

  private class Rig(val scene: ImageComposeScene, val cellWidth: Float, val cellHeight: Float) {
    var time = 0L

    fun frame(): Image {
      repeat(3) {
        val _ = scene.render(time * 1_000_000L)
        time += 16
      }
      return scene.render(time * 1_000_000L)
    }

    fun at(column: Int, row: Int) = Offset((column + 0.5f) * cellWidth, (row + 0.5f) * cellHeight)

    fun click(position: Offset, ctrl: Boolean) {
      val keys = PointerKeyboardModifiers(isCtrlPressed = ctrl)
      scene.sendPointerEvent(PointerEventType.Press, position, timeMillis = time, buttons = PointerButtons(isPrimaryPressed = true), keyboardModifiers = keys, button = PointerButton.Primary)
      scene.sendPointerEvent(PointerEventType.Release, position, timeMillis = time + 10, buttons = PointerButtons(), keyboardModifiers = keys, button = PointerButton.Primary)
      time += 1_000
    }

    fun hover(position: Offset) {
      scene.sendPointerEvent(PointerEventType.Move, position, timeMillis = time, buttons = PointerButtons())
      time += 20
    }

    /** Light pixels on the line where row [row]'s underline goes: just under the text, one pixel thick here. */
    fun underlinePixels(image: Image, row: Int): Int {
      val bitmap = Bitmap.makeFromImage(image)
      val y = ((row + 1) * cellHeight).toInt() - 2
      return (0 until bitmap.width).count { x -> (bitmap.getColor(x, y) and 0xFF) > 150 }
    }
  }

  private fun withTerminal(block: Rig.() -> Unit) = runBlocking<Unit> {
    val marked = "\\033]8;;https://lemonade-code.dev\\033\\\\docs\\033]8;;\\033\\\\ here"
    val script = "sleep 1; printf 'see https://example.com/path.\\n$marked\\n\\n$longUrl'; sleep 30"
    TerminalSession(TerminalLaunch(command = listOf("/bin/sh", "-c", script), workingDirectory = System.getProperty("java.io.tmpdir"))).use { session ->
      var width = 0f
      var height = 0f
      ImageComposeScene(width = 600, height = 200, density = Density(1f)) {
        val layout = rememberTextMeasurer().measure(AnnotatedString("M".repeat(100)), style, softWrap = false)
        width = layout.size.width / 100f
        height = layout.size.height.toFloat()
        TerminalView(session, colors, style, onOpenLink = { opened += it })
      }.use { scene ->
        val warmup = Rig(scene, 0f, 0f)
        val _ = warmup.frame()
        withTimeout(5_000) {
          while (session.screenText().lines().getOrNull(4)?.contains("aaa") != true) {
            val _ = warmup.frame()
            delay(20)
          }
        }
        Rig(scene, width, height).apply {
          time = warmup.time
          val _ = frame()
        }.block()
      }
    }
  }

  @Test
  fun `ctrl-clicking a written url opens it, without the full stop`() = withTerminal {
    click(at(6, 0), ctrl = true)
    assertEquals(listOf("https://example.com/path"), opened)
  }

  @Test
  fun `a plain click opens nothing`() = withTerminal {
    click(at(6, 0), ctrl = false)
    click(at(1, 1), ctrl = false)
    assertEquals(emptyList(), opened)
  }

  @Test
  fun `a link the program marked opens where it points`() = withTerminal {
    click(at(1, 1), ctrl = true)
    click(at(6, 1), ctrl = true)
    assertEquals(listOf("https://lemonade-code.dev"), opened)
  }

  @Test
  fun `a url that wrapped opens whole from either line`() = withTerminal {
    click(at(5, 3), ctrl = true)
    click(at(5, 4), ctrl = true)
    assertEquals(listOf(longUrl, longUrl), opened)
  }

  @Test
  fun `hovering a link underlines it`() = withTerminal {
    val before = underlinePixels(frame(), row = 0)
    hover(at(10, 0))
    val after = underlinePixels(frame(), row = 0)
    assertTrue(after > before + 100, "underline pixels went from $before to $after")
    hover(at(2, 2))
    assertTrue(underlinePixels(frame(), row = 0) < before + 20, "the underline should go once the mouse leaves")
  }

  private fun TerminalSession.screenText(): String {
    textBuffer.lock()
    return try {
      textBuffer.getScreenLines()
    } finally {
      textBuffer.unlock()
    }
  }
}
