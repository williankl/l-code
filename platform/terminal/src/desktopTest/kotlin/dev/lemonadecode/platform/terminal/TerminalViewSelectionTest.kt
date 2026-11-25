package dev.lemonadecode.platform.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
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
import java.awt.event.InputEvent
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.awt.event.KeyEvent as AwtKeyEvent

/** Selecting with the mouse in a real terminal and copying it. */
@OptIn(ExperimentalComposeUiApi::class)
class TerminalViewSelectionTest {
  private val colors = TerminalColors(Color.White, Color.Black, Color.Yellow, Color.Black, List(16) { Color.Gray }, selection = Color(255, 0, 0, 128))
  private val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
  private val source = JPanel()
  private val clipboard = object : TerminalClipboard {
    var copied: String? = null

    override fun read(): String? = null

    override fun write(text: String) {
      copied = text
    }
  }
  private var copyOnSelect by mutableStateOf(false)

  private class Rig(val scene: ImageComposeScene, val cellWidth: Float, val cellHeight: Float) {
    var time = 0L

    fun frame(): Image {
      repeat(3) {
        val _ = scene.render(time * 1_000_000L)
        time += 16
      }
      return scene.render(time * 1_000_000L)
    }

    /** The middle of a cell, nudged left so a drag snaps to its left edge. */
    fun at(column: Int, row: Int) = Offset((column + 0.2f) * cellWidth, (row + 0.5f) * cellHeight)

    fun mouse(type: PointerEventType, position: Offset, pressed: Boolean, keyboardModifiers: PointerKeyboardModifiers? = null) {
      scene.sendPointerEvent(
        eventType = type,
        position = position,
        timeMillis = time,
        buttons = PointerButtons(isPrimaryPressed = pressed),
        keyboardModifiers = keyboardModifiers,
        button = PointerButton.Primary,
      )
      time += 20
    }

    fun click(position: Offset) {
      mouse(PointerEventType.Press, position, pressed = true)
      mouse(PointerEventType.Release, position, pressed = false)
    }
  }

  private fun withTerminal(output: String = Plain, block: suspend Rig.() -> Unit) = runBlocking<Unit> {
    val script = "printf '$output'; sleep 30"
    val lastLine = output.substringAfterLast("\\n").trim()
    TerminalSession(TerminalLaunch(command = listOf("/bin/sh", "-c", script), workingDirectory = System.getProperty("java.io.tmpdir"))).use { session ->
      var width = 0f
      var height = 0f
      ImageComposeScene(width = 600, height = 200, density = Density(1f)) {
        val layout = rememberTextMeasurer().measure(AnnotatedString("M".repeat(100)), style, softWrap = false)
        width = layout.size.width / 100f
        height = layout.size.height.toFloat()
        TerminalView(session, colors, style, clipboard = clipboard, copyOnSelect = copyOnSelect)
      }.use { scene ->
        val rig = Rig(scene, 0f, 0f)
        val _ = rig.frame()
        withTimeout(5_000) {
          while (!session.screenText().contains(lastLine)) delay(20)
        }
        Rig(scene, width, height).apply {
          time = rig.time
          // The view starts handling the mouse at its first event: hover now, so a setting a test
          // changes later has to reach a terminal that is already listening.
          mouse(PointerEventType.Move, at(0, 0), pressed = false)
        }.block()
      }
    }
  }

  private fun Rig.copy() {
    val mac = System.getProperty("os.name").orEmpty().startsWith("Mac")
    val modifiers = if (mac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
    scene.sendKeyEvent(key(AwtKeyEvent(source, AwtKeyEvent.KEY_PRESSED, 0, modifiers, AwtKeyEvent.VK_C, 'c')))
  }

  private fun Rig.selectAll() {
    val mac = System.getProperty("os.name").orEmpty().startsWith("Mac")
    val modifiers = if (mac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
    scene.sendKeyEvent(key(AwtKeyEvent(source, AwtKeyEvent.KEY_PRESSED, 0, modifiers, AwtKeyEvent.VK_A, 'a')))
  }

  /** Drags from the first letter of [Indented]'s first line to the end of its last. */
  private fun Rig.dragBlock() {
    mouse(PointerEventType.Press, at(2, 0), pressed = true)
    mouse(PointerEventType.Move, at(3, 1), pressed = true)
    mouse(PointerEventType.Move, at(6, 2), pressed = true)
    mouse(PointerEventType.Release, at(6, 2), pressed = false)
  }

  /** Drags over the first four spaces of [Indented]'s second line, and nothing else. */
  private fun Rig.dragSpaces() {
    mouse(PointerEventType.Press, at(0, 1), pressed = true)
    mouse(PointerEventType.Move, at(4, 1), pressed = true)
    mouse(PointerEventType.Release, at(4, 1), pressed = false)
  }

  /** Presses at [position], moves the pointer by [wobble] and lets go there. */
  private fun Rig.wobblyClick(position: Offset, wobble: Offset) {
    mouse(PointerEventType.Press, position, pressed = true)
    mouse(PointerEventType.Move, position + wobble, pressed = true)
    mouse(PointerEventType.Release, position + wobble, pressed = false)
  }

  @Test
  fun `dragging across lines selects and copies that text`() = withTerminal {
    assertEquals(0, redPixels(frame()))
    mouse(PointerEventType.Press, at(6, 0), pressed = true)
    mouse(PointerEventType.Move, at(3, 1), pressed = true)
    mouse(PointerEventType.Move, at(5, 1), pressed = true)
    mouse(PointerEventType.Release, at(5, 1), pressed = false)
    assertTrue(redPixels(frame()) > 100, "the selection should be highlighted")
    copy()
    assertEquals("beta\ngamma", clipboard.copied)
  }

  @Test
  fun `a double click copies the word and a triple click the line`() = withTerminal {
    click(at(8, 1))
    click(at(8, 1))
    copy()
    assertEquals("delta", clipboard.copied)
    time += 1_000
    repeat(3) { click(at(2, 2)) }
    copy()
    assertEquals("epsilon", clipboard.copied)
  }

  @Test
  fun `a plain click or typing clears the selection`() = withTerminal {
    click(at(1, 0))
    click(at(1, 0))
    time += 1_000
    click(at(4, 1))
    copy()
    assertNull(clipboard.copied)

    click(at(1, 0))
    click(at(1, 0))
    scene.sendKeyEvent(key(AwtKeyEvent(source, AwtKeyEvent.KEY_TYPED, 0, 0, AwtKeyEvent.VK_UNDEFINED, 'x')))
    copy()
    assertNull(clipboard.copied)
  }

  @Test
  fun `with copy on select a finished drag is copied, and not before`() = withTerminal {
    copyOnSelect = true
    val _ = frame()
    mouse(PointerEventType.Press, at(6, 0), pressed = true)
    mouse(PointerEventType.Move, at(3, 1), pressed = true)
    mouse(PointerEventType.Move, at(5, 1), pressed = true)
    assertNull(clipboard.copied)
    mouse(PointerEventType.Release, at(5, 1), pressed = false)
    assertEquals("beta\ngamma", clipboard.copied)
  }

  @Test
  fun `with copy on select a double click copies the word and a triple click the line`() = withTerminal {
    copyOnSelect = true
    val _ = frame()
    click(at(8, 1))
    click(at(8, 1))
    assertEquals("delta", clipboard.copied)
    time += 1_000
    repeat(3) { click(at(2, 2)) }
    assertEquals("epsilon", clipboard.copied)
  }

  @Test
  fun `copy on select drops the indentation the lines share, and so does the copy key`() = withTerminal(Indented) {
    copyOnSelect = true
    val _ = frame()
    dragBlock()
    assertEquals("if x:\n    run()\ndone", clipboard.copied)
    clipboard.copied = null
    copy()
    assertEquals("if x:\n    run()\ndone", clipboard.copied)
  }

  @Test
  fun `without copy on select nothing is copied until the copy key, indentation and all`() = withTerminal(Indented) {
    dragBlock()
    assertNull(clipboard.copied)
    copy()
    assertEquals("if x:\n      run()\n  done", clipboard.copied)
  }

  @Test
  fun `with copy on select a click, a drag over spaces and select all leave the clipboard alone`() = withTerminal(Indented) {
    copyOnSelect = true
    val _ = frame()
    click(at(4, 0))
    assertNull(clipboard.copied)
    // Long enough that the next press starts a new click, not a double click.
    time += 1_000
    mouse(PointerEventType.Press, at(0, 1), pressed = true)
    mouse(PointerEventType.Move, at(2, 1), pressed = true)
    mouse(PointerEventType.Move, at(4, 1), pressed = true)
    mouse(PointerEventType.Release, at(4, 1), pressed = false)
    assertNull(clipboard.copied)
    selectAll()
    assertNull(clipboard.copied)
  }

  @Test
  fun `with copy on select a click whose pointer wobbles across a cell selects and copies nothing`() = withTerminal {
    copyOnSelect = true
    val _ = frame()
    wobblyClick(Offset(3.45f * cellWidth, 0.5f * cellHeight), wobble = Offset(0.8f, 0f))
    assertNull(clipboard.copied)
    assertEquals(0, redPixels(frame()))
    // Long enough that the next press starts a new click, not a double click.
    time += 1_000
    wobblyClick(Offset(3.45f * cellWidth, 0.95f * cellHeight), wobble = Offset(0f, 1.5f))
    assertNull(clipboard.copied)
  }

  @Test
  fun `without copy on select a click whose pointer wobbles across a cell selects nothing`() = withTerminal {
    wobblyClick(Offset(3.45f * cellWidth, 0.5f * cellHeight), wobble = Offset(0.8f, 0f))
    assertEquals(0, redPixels(frame()))
    copy()
    assertNull(clipboard.copied)
  }

  @Test
  fun `with copy on select a drag of one cell copies one character`() = withTerminal {
    copyOnSelect = true
    val _ = frame()
    mouse(PointerEventType.Press, at(6, 0), pressed = true)
    mouse(PointerEventType.Move, at(7, 0), pressed = true)
    mouse(PointerEventType.Release, at(7, 0), pressed = false)
    assertEquals("b", clipboard.copied)
  }

  @Test
  fun `with copy on select the copy key leaves blank text off the clipboard too`() = withTerminal(Indented) {
    copyOnSelect = true
    val _ = frame()
    dragSpaces()
    assertNull(clipboard.copied)
    copy()
    assertNull(clipboard.copied)
  }

  @Test
  fun `without copy on select the copy key writes blank text as it is`() = withTerminal(Indented) {
    dragSpaces()
    copy()
    assertEquals("    ", clipboard.copied)
  }

  @Test
  fun `with copy on select a shift-click extends the selection and copies it on release, not on press`() = withTerminal {
    copyOnSelect = true
    val _ = frame()
    click(at(1, 0))
    click(at(1, 0))
    clipboard.copied = null
    time += 1_000
    mouse(PointerEventType.Press, at(10, 0), pressed = true, keyboardModifiers = PointerKeyboardModifiers(isShiftPressed = true))
    assertNull(clipboard.copied)
    mouse(PointerEventType.Release, at(10, 0), pressed = false)
    assertEquals("alpha beta", clipboard.copied)
  }

  @Test
  fun `turning copy on select off reaches a terminal that is already open`() = withTerminal(Indented) {
    copyOnSelect = true
    val _ = frame()
    dragBlock()
    assertEquals("if x:\n    run()\ndone", clipboard.copied)
    copyOnSelect = false
    val _ = frame()
    clipboard.copied = null
    time += 1_000
    dragBlock()
    assertNull(clipboard.copied)
    copy()
    assertEquals("if x:\n      run()\n  done", clipboard.copied)
  }

  /** Wraps an AWT event the way the window does, so the view sees what it would see for real. */
  @OptIn(InternalComposeUiApi::class)
  private fun key(event: AwtKeyEvent): KeyEvent = KeyEvent(
    key = Key.Unknown,
    type = if (event.id == AwtKeyEvent.KEY_PRESSED) KeyEventType.KeyDown else KeyEventType.Unknown,
    codePoint = event.keyChar.code,
    nativeEvent = event,
  )

  /** Pixels tinted by the red selection colour over the black background. */
  private fun redPixels(image: Image): Int {
    val bitmap = Bitmap.makeFromImage(image)
    var count = 0
    for (y in 0 until bitmap.height) {
      for (x in 0 until bitmap.width) {
        val pixel = bitmap.getColor(x, y)
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        if (red > 90 && green < 60 && blue < 60) count++
      }
    }
    return count
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

/** What the terminal prints, as printf's format: plain lines, or lines sharing an indentation. */
private const val Plain = "alpha beta\\ngamma delta\\nepsilon"
private const val Indented = "  if x:\\n      run()\\n  done"
