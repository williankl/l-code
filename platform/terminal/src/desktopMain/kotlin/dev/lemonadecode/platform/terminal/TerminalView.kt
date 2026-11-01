package dev.lemonadecode.platform.terminal

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jediterm.terminal.HyperlinkStyle
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.model.TerminalLine
import com.jediterm.terminal.util.CharUtils
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import com.jediterm.terminal.TextStyle as CellStyle
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * The colours a terminal draws with; [ansi] holds the 16 base ANSI colours, normal then bright.
 * [selection] is laid over selected text, so it should be see-through.
 */
@Immutable
public data class TerminalColors(
  val foreground: Color,
  val background: Color,
  val cursor: Color,
  val cursorText: Color,
  val ansi: List<Color>,
  val selection: Color = Color(0x80, 0x9C, 0xFF, 0x66),
) {
  init {
    require(ansi.size == 16) { "ansi needs 16 colours" }
  }
}

/** Where the terminal copies to and pastes from: the system clipboard, unless a test swaps it. */
public interface TerminalClipboard {
  public fun read(): String?

  public fun write(text: String)

  public companion object {
    public val System: TerminalClipboard = object : TerminalClipboard {
      override fun read(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
      }.getOrNull()

      override fun write(text: String) {
        val _ = runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
      }
    }
  }
}

/**
 * Draws [session]'s screen and sends keyboard input to it. Click to focus; drag to select, double
 * click for a word, triple click for a line, shift-click to extend; ⌘C copies and ⌘A selects all
 * (Ctrl+Shift+C / Ctrl+Shift+A elsewhere); ⌘V / Ctrl+Shift+V pastes; the wheel scrolls back.
 * Links, written out or marked by the program, underline under the mouse; Ctrl- or ⌘-click hands
 * them to [onOpenLink].
 */
@Composable
public fun TerminalView(
  session: TerminalSession,
  colors: TerminalColors,
  textStyle: TextStyle,
  modifier: Modifier = Modifier,
  focusRequester: FocusRequester = remember { FocusRequester() },
  clipboard: TerminalClipboard = TerminalClipboard.System,
  onOpenLink: (String) -> Unit = ::openInBrowser,
) {
  val version by session.version.collectAsState()
  val measurer = rememberTextMeasurer()
  val density = LocalDensity.current
  val cell = remember(textStyle, density) {
    val layout = measurer.measure(AnnotatedString("M".repeat(MeasureRun)), textStyle, softWrap = false)
    with(density) { CellSize((layout.size.width.toFloat() / MeasureRun).toDp(), layout.size.height.toDp()) }
  }
  val glyphs = remember(textStyle, density, cell) { GlyphFit(measurer, textStyle, with(density) { cell.width.toPx() }) }
  var scrollBack by remember(session) { mutableIntStateOf(0) }
  var focused by remember { mutableStateOf(false) }
  var selection by remember(session) { mutableStateOf<TerminalSelection?>(null) }
  var hovered by remember(session) { mutableStateOf<TerminalLink?>(null) }
  var composing by remember(session) { mutableStateOf("") }
  val cursorAnchor = remember { CursorAnchor() }

  BoxWithConstraints(
    modifier = modifier
      .background(colors.background)
      .onKeyEvent { event ->
        val keys = TerminalKeys(
          session = session,
          paste = { clipboard.read() },
          copy = { selection?.takeUnless { it.isEmpty }?.let { clipboard.write(it.text(session::lineText)) } != null },
          selectAll = { selection = session.everything() },
        )
        when (handleKey(event, keys)) {
          KeyOutcome.Sent -> {
            scrollBack = 0
            selection = null
            true
          }
          KeyOutcome.Handled -> true
          KeyOutcome.Ignored -> false
        }
      }
      .onFocusChanged { focused = it.isFocused }
      .terminalTextInput(
        onCommit = { text ->
          session.send(text)
          scrollBack = 0
          selection = null
        },
        onComposing = { composing = it },
        cursorRect = { cursorAnchor.rect },
      )
      .focusRequester(focusRequester)
      .focusable()
      .pointerHoverIcon(if (hovered != null) PointerIcon.Hand else PointerIcon.Text)
      .pointerInput(session, cell, onOpenLink) {
        val clicks = ClickCounter(viewConfiguration.doubleTapTimeoutMillis, viewConfiguration.touchSlop)
        var dragging = false

        /** The text position under [position]; [nearest] snaps to the closest cell edge, as a drag does. */
        fun pointAt(position: Offset, nearest: Boolean): TextPoint {
          val width = cell.width.toPx()
          val height = cell.height.toPx()
          val columns = max(2, (size.width / width).toInt())
          val rows = max(2, (size.height / height).toInt())
          val column = (if (nearest) (position.x / width).roundToInt() else floor(position.x / width).toInt()).coerceIn(0, columns)
          val row = floor(position.y / height).toInt().coerceIn(0, rows - 1)
          return TextPoint(session.textBuffer.historyLinesCount + row - scrollBack, column)
        }

        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue
            when (event.type) {
              PointerEventType.Press -> {
                focusRequester.requestFocus()
                val modifiers = event.keyboardModifiers
                // On a Mac, Ctrl-click can arrive as a secondary press.
                val linkClick = (modifiers.isCtrlPressed || modifiers.isMetaPressed) && (event.buttons.isPrimaryPressed || event.buttons.isSecondaryPressed)
                val link = if (linkClick) session.linkAt(pointAt(change.position, nearest = false)) else null
                if (link != null) {
                  onOpenLink(link.url)
                  change.consume()
                  continue
                }
                if (!event.buttons.isPrimaryPressed) continue
                val columns = max(2, (size.width / cell.width.toPx()).toInt())
                val current = selection
                when (clicks.press(change.uptimeMillis, change.position)) {
                  1 -> {
                    val at = pointAt(change.position, nearest = true)
                    selection = if (event.keyboardModifiers.isShiftPressed && current != null) current.copy(focus = at) else TerminalSelection(at, at)
                    dragging = true
                  }
                  2 -> {
                    val at = pointAt(change.position, nearest = false)
                    selection = TerminalSelection.word(at.line, at.column, session.lineText(at.line)?.text.orEmpty())
                    dragging = false
                  }
                  else -> {
                    selection = TerminalSelection.line(pointAt(change.position, nearest = false).line, columns)
                    dragging = false
                  }
                }
                change.consume()
              }
              PointerEventType.Move -> if (dragging && event.buttons.isPrimaryPressed) {
                // Dragging past the top or bottom edge scrolls, so a selection can run longer than the view.
                val history = session.textBuffer.historyLinesCount
                if (change.position.y < 0) scrollBack = (scrollBack + 1).coerceAtMost(history)
                if (change.position.y > size.height) scrollBack = (scrollBack - 1).coerceAtLeast(0)
                selection = selection?.copy(focus = pointAt(change.position, nearest = true))
                hovered = null
                change.consume()
              } else {
                hovered = session.linkAt(pointAt(change.position, nearest = false))
              }
              PointerEventType.Enter -> hovered = session.linkAt(pointAt(change.position, nearest = false))
              PointerEventType.Exit -> hovered = null
              PointerEventType.Release -> if (dragging) {
                dragging = false
                if (selection?.isEmpty == true) selection = null
              }
              PointerEventType.Scroll -> {
                val delta = change.scrollDelta.y
                val history = session.textBuffer.historyLinesCount
                scrollBack = (scrollBack - delta.roundToInt() * WheelLines).coerceIn(0, history)
                hovered = null
                event.changes.forEach { it.consume() }
              }
            }
          }
        }
      },
  ) {
    val columns = max(2, (maxWidth / cell.width).toInt())
    val rows = max(2, (maxHeight / cell.height).toInt())
    LaunchedEffect(session, columns, rows) { session.resize(columns, rows) }

    val screen = remember(version, scrollBack, colors, rows, columns, selection, glyphs) {
      readScreen(session, rows, columns, scrollBack, colors, selection, glyphs::fits)
    }
    val lineStyle = textStyle.copy(color = colors.foreground)
    Column(modifier = Modifier.fillMaxSize()) {
      screen.lines.forEach { segments ->
        Box(modifier = Modifier.fillMaxWidth().height(cell.height)) {
          segments.forEach { segment ->
            BasicText(
              text = segment.text,
              style = lineStyle,
              softWrap = false,
              maxLines = 1,
              modifier = Modifier.offset(x = cell.width * segment.column),
            )
          }
        }
      }
    }
    val cursorAt = screen.cursor
    SideEffect {
      cursorAnchor.rect = cursorAt?.let {
        with(density) { Rect(Offset(cell.width.toPx() * it.column, cell.height.toPx() * it.row), Size(cell.width.toPx(), cell.height.toPx())) }
      }
    }
    cursorAt?.let { cursor ->
      if (composing.isEmpty()) {
        Cursor(cursor = cursor, focused = focused, restartKey = version, cell = cell, colors = colors, textStyle = textStyle)
      } else {
        ComposingText(text = composing, cursor = cursor, cell = cell, colors = colors, textStyle = textStyle)
      }
    }
    val link = hovered
    if (screen.highlights.any { it != null } || link != null) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val width = cell.width.toPx()
        val height = cell.height.toPx()
        screen.highlights.forEachIndexed { row, columns ->
          if (columns != null) {
            drawRect(
              color = colors.selection,
              topLeft = Offset(columns.first * width, row * height),
              size = Size((columns.last + 1 - columns.first) * width, height),
            )
          }
        }
        link?.spans?.forEach { (line, columns) ->
          val row = line - screen.top
          if (row in screen.lines.indices) {
            // Snapped to whole pixels so it stays a crisp line at any density.
            val thickness = max(1f, floor(UnderlineThickness.toPx()))
            val top = floor((row + 1) * height - UnderlineGap.toPx() - thickness)
            drawRect(
              color = colors.foreground,
              topLeft = Offset(columns.first * width, top),
              size = Size((columns.last + 1 - columns.first) * width, thickness),
            )
          }
        }
      }
    }
  }
}

/** Tells single, double and triple clicks apart: presses close in time and place count up. */
private class ClickCounter(private val timeoutMillis: Long, private val slop: Float) {
  private var count = 0
  private var lastTime = Long.MIN_VALUE / 2
  private var lastPosition = Offset.Zero

  fun press(time: Long, position: Offset): Int {
    val again = time - lastTime <= timeoutMillis && (position - lastPosition).getDistance() <= slop
    count = if (again) count % 3 + 1 else 1
    lastTime = time
    lastPosition = position
    return count
  }
}

/** One line of the whole text (history included), as the selection sees it; null past either end. */
private fun TerminalSession.lineText(line: Int): LineText? {
  val buffer = textBuffer
  buffer.lock()
  try {
    val index = line - buffer.historyLinesCount
    if (index < -buffer.historyLinesCount || index >= buffer.height) return null
    val content = buffer.getLine(index)
    return LineText(content.text, content.isWrapped)
  } finally {
    buffer.unlock()
  }
}

/** A link on screen: where it points, and the columns it covers on each line of the whole text. */
private data class TerminalLink(val url: String, val spans: Map<Int, IntRange>)

/**
 * The openable link at [point], looking across soft-wrapped lines so a long URL that wrapped is
 * still one link.
 */
private fun TerminalSession.linkAt(point: TextPoint): TerminalLink? {
  val buffer = textBuffer
  buffer.lock()
  try {
    val history = buffer.historyLinesCount
    fun line(at: Int): TerminalLine? = (at - history).takeIf { it >= -history && it < buffer.height }?.let(buffer::getLine)
    if (line(point.line) == null) return null
    var first = point.line
    while (line(first - 1)?.isWrapped == true) first--
    var last = point.line
    while (line(last)?.isWrapped == true && line(last + 1) != null) last++

    val text = StringBuilder()
    val targets = mutableListOf<String?>()
    val cells = mutableListOf<TextPoint>()
    for (at in first..last) {
      var column = 0
      line(at)?.entries?.forEach { entry ->
        val target = ((entry.style as? HyperlinkStyle)?.linkInfo as? UrlLink)?.url
        entry.text.toString().forEach { char ->
          text.append(if (char == CharUtils.NUL_CHAR) ' ' else char)
          targets += target
          cells += TextPoint(at, column++)
        }
      }
    }
    val index = cells.indexOf(point).takeIf { it >= 0 } ?: return null
    val found = linkAt(text.toString(), index, targets)?.takeIf { isOpenable(it.url) } ?: return null
    val spans = found.range.map { cells[it] }.groupBy { it.line }.mapValues { (_, points) -> points.minOf { it.column }..points.maxOf { it.column } }
    return TerminalLink(found.url.replace(CharUtils.DWC.toString(), ""), spans)
  } finally {
    buffer.unlock()
  }
}

/** Everything from the oldest history line to the last line with something on it. */
private fun TerminalSession.everything(): TerminalSelection? {
  val buffer = textBuffer
  buffer.lock()
  try {
    val history = buffer.historyLinesCount
    val last = (buffer.height - 1 downTo -history).firstOrNull { buffer.getLine(it).text.isNotBlank() } ?: return null
    return TerminalSelection(TextPoint(0, 0), TextPoint(history + last, buffer.width))
  } finally {
    buffer.unlock()
  }
}

/**
 * A block cursor that blinks while the terminal has focus, starting solid again after every change
 * so it never vanishes mid-typing; a hollow box while focus is elsewhere.
 */
@Composable
private fun Cursor(
  cursor: CursorCell,
  focused: Boolean,
  restartKey: Any,
  cell: CellSize,
  colors: TerminalColors,
  textStyle: TextStyle,
) {
  val phase = remember { Animatable(0f) }
  LaunchedEffect(restartKey, focused) {
    phase.snapTo(0f)
    if (focused) phase.animateTo(1f, infiniteRepeatable(tween(BlinkPeriodMillis, easing = LinearEasing)))
  }
  val position = Modifier
    .offset(x = cell.width * cursor.column, y = cell.height * cursor.row)
    .size(cell.width, cell.height)
  when {
    !focused -> Box(modifier = position.border(1.dp, colors.cursor))
    phase.value < 0.5f -> Box(modifier = position.background(colors.cursor)) {
      BasicText(
        text = cursor.char.toString(),
        style = textStyle.copy(color = colors.cursorText),
        softWrap = false,
      )
    }
  }
}

private const val BlinkPeriodMillis = 1100

/**
 * Text an input method is still composing (a dead key's accent, a CJK reading), drawn underlined
 * over the cursor until it commits.
 */
@Composable
private fun ComposingText(text: String, cursor: CursorCell, cell: CellSize, colors: TerminalColors, textStyle: TextStyle) {
  BasicText(
    text = text,
    style = textStyle.copy(color = colors.foreground, textDecoration = TextDecoration.Underline),
    softWrap = false,
    maxLines = 1,
    modifier = Modifier
      .offset(x = cell.width * cursor.column, y = cell.height * cursor.row)
      .height(cell.height)
      .background(colors.background),
  )
}

/** Where the cursor was last drawn, in the view's pixels, so input method popups open beside it. */
private class CursorAnchor {
  var rect: Rect? = null
}

/**
 * What is on screen at one moment: the visible lines, the cursor when shown, and each row's
 * selected columns. [top] is the line of the whole text shown in the first row.
 */
private data class Screen(
  val lines: List<List<Segment>>,
  val cursor: CursorCell?,
  val highlights: List<IntRange?>,
  val top: Int,
)

private data class CursorCell(
  val column: Int,
  val row: Int,
  val char: Char,
)

private data class CellSize(val width: Dp, val height: Dp)

/** A run of text drawn from [column] on: the screen is drawn in runs so that every run starts on its cell. */
private data class Segment(val column: Int, val text: AnnotatedString)

/**
 * Which characters the font draws exactly one cell wide. Those can share a run; any other (a
 * symbol from a fallback font, an emoji) is drawn on its own at its column, so the text after it
 * stays on the grid, under the cursor, selection and link underline that are placed by cell.
 */
private class GlyphFit(
  private val measurer: TextMeasurer,
  private val style: TextStyle,
  private val cellWidth: Float,
) {
  private val known = HashMap<Char, Boolean>()

  fun fits(char: Char): Boolean {
    if (char in ' '..'~') return true
    return known.getOrPut(char) {
      if (char.isSurrogate()) return@getOrPut false
      val width = measurer.measure(AnnotatedString(char.toString().repeat(MeasureRun)), style, softWrap = false).size.width.toFloat() / MeasureRun
      abs(width - cellWidth) <= cellWidth * FitTolerance
    }
  }
}

private const val FitTolerance = 0.01f

private const val WheelLines = 3

private val UnderlineGap = 1.dp
private val UnderlineThickness = 1.dp

/** Laid-out sizes round to whole pixels, so one glyph's width is measured over a long run. */
private const val MeasureRun = 100

/** Reads lines and cursor under one lock, so the cursor always matches the text drawn. */
private fun readScreen(
  session: TerminalSession,
  rows: Int,
  columns: Int,
  scrollBack: Int,
  colors: TerminalColors,
  selection: TerminalSelection?,
  fits: (Char) -> Boolean,
): Screen {
  val buffer = session.textBuffer
  buffer.lock()
  try {
    val visible = minOf(rows, buffer.height)
    val lines = (0 until visible).map { row -> buffer.getLine(row - scrollBack).toSegments(colors, fits) }
    val column = session.cursorColumn.coerceAtLeast(0)
    val row = session.cursorRow.coerceAtLeast(0)
    val shown = scrollBack == 0 && session.cursorVisible && session.isRunning && row < visible
    val cursor = if (shown) {
      val char = runCatching { buffer.getCharAt(column, row) }.getOrDefault(' ')
      CursorCell(column, row, if (char == CharUtils.NUL_CHAR || char == CharUtils.DWC) ' ' else char)
    } else {
      null
    }
    val top = buffer.historyLinesCount - scrollBack
    val highlights = (0 until visible).map { row -> selection?.columnsOn(top + row, columns) }
    return Screen(lines, cursor, highlights, top)
  } finally {
    buffer.unlock()
  }
}

/**
 * The line as runs of text, each starting at its cell: characters [fits] keeps together, any other
 * on its own. A wide character's placeholder cell is skipped; an empty cell is a space.
 */
private fun TerminalLine.toSegments(colors: TerminalColors, fits: (Char) -> Boolean): List<Segment> {
  val segments = mutableListOf<Segment>()
  var run = AnnotatedString.Builder()
  var runStart = 0
  var column = 0
  fun flush() {
    if (run.length > 0) segments += Segment(runStart, run.toAnnotatedString())
    run = AnnotatedString.Builder()
  }
  entries.forEach { entry ->
    val span = entry.style.takeUnless { it == CellStyle.EMPTY }?.toSpan(colors)
    val text = entry.text.toString()
    var index = 0
    while (index < text.length) {
      val char = text[index]
      when {
        char == CharUtils.DWC -> {
          flush()
          column++
          index++
        }
        fits(char) || char == CharUtils.NUL_CHAR -> {
          if (run.length == 0) runStart = column
          run.appendStyled(if (char == CharUtils.NUL_CHAR) " " else char.toString(), span)
          column++
          index++
        }
        else -> {
          flush()
          // A character outside the basic plane comes as two halves; they are drawn together.
          val width = if (char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) 2 else 1
          segments += Segment(column, AnnotatedString.Builder().apply { appendStyled(text.substring(index, index + width), span) }.toAnnotatedString())
          column += width
          index += width
        }
      }
    }
  }
  flush()
  return segments
}

private fun AnnotatedString.Builder.appendStyled(text: String, span: SpanStyle?) {
  if (span == null) {
    append(text)
  } else {
    pushStyle(span)
    append(text)
    pop()
  }
}

private fun CellStyle.toSpan(colors: TerminalColors): SpanStyle {
  val inverse = hasOption(CellStyle.Option.INVERSE)
  var foreground = foreground?.resolve(colors) ?: colors.foreground
  var background = background?.resolve(colors) ?: Color.Unspecified
  if (inverse) {
    val swapped = if (background == Color.Unspecified) colors.background else background
    background = foreground
    foreground = swapped
  }
  if (hasOption(CellStyle.Option.DIM)) foreground = foreground.copy(alpha = foreground.alpha * DimAlpha)
  if (hasOption(CellStyle.Option.HIDDEN)) foreground = Color.Transparent
  return SpanStyle(
    color = foreground,
    background = background,
    fontWeight = if (hasOption(CellStyle.Option.BOLD)) FontWeight.Bold else null,
    fontStyle = if (hasOption(CellStyle.Option.ITALIC)) FontStyle.Italic else null,
    textDecoration = if (hasOption(CellStyle.Option.UNDERLINED)) TextDecoration.Underline else null,
  )
}

private const val DimAlpha = 0.6f

private fun TerminalColor.resolve(colors: TerminalColors): Color {
  if (!isIndexed) {
    val c = toColor()
    return Color(c.red, c.green, c.blue, c.alpha)
  }
  val index = colorIndex
  return when {
    index < 16 -> colors.ansi[index]
    index < 232 -> {
      val n = index - 16
      Color(cubeLevel(n / 36), cubeLevel((n / 6) % 6), cubeLevel(n % 6))
    }
    index < 256 -> {
      val level = 8 + (index - 232) * 10
      Color(level, level, level)
    }
    else -> colors.foreground
  }
}

private fun cubeLevel(step: Int): Int = if (step == 0) 0 else 55 + step * 40

private val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac")

/** Characters whose key-press already produced bytes, so the typed event must not repeat them. */
private val keysSentOnPress = setOf('\n', '\r', '\b', '\t', '\u001b', '\u007f')

private enum class KeyOutcome {
  /** Bytes went to the program: the view returns to the bottom and the selection goes. */
  Sent,

  /** Taken by the view itself (copy, select all). */
  Handled,

  Ignored,
}

private class TerminalKeys(
  val session: TerminalSession,
  val paste: () -> String?,
  /** Copies the selection; false when there is none. */
  val copy: () -> Boolean,
  val selectAll: () -> Unit,
)

private fun handleKey(event: KeyEvent, keys: TerminalKeys): KeyOutcome {
  val awt = event.awtEventOrNull ?: return KeyOutcome.Ignored
  val session = keys.session
  val modifiers = awt.modifiersEx
  val command = modifiers and InputEvent.META_DOWN_MASK != 0
  val ctrlShift = modifiers and InputEvent.CTRL_DOWN_MASK != 0 && modifiers and InputEvent.SHIFT_DOWN_MASK != 0
  val shortcut = (isMac && command) || (!isMac && ctrlShift)
  return when (awt.id) {
    AwtKeyEvent.KEY_PRESSED -> {
      if (shortcut) {
        when (awt.keyCode) {
          AwtKeyEvent.VK_V -> {
            keys.paste()?.let(session::paste)
            return KeyOutcome.Sent
          }
          AwtKeyEvent.VK_C -> return if (keys.copy()) KeyOutcome.Handled else KeyOutcome.Ignored
          AwtKeyEvent.VK_A -> return KeyOutcome.Handled.also { keys.selectAll() }
        }
      }
      if (command) return KeyOutcome.Ignored
      val plain = modifiers and (InputEvent.SHIFT_DOWN_MASK or InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK) == 0
      when {
        awt.keyCode == AwtKeyEvent.VK_ESCAPE -> return KeyOutcome.Sent.also { session.send("\u001b") }
        awt.keyCode == AwtKeyEvent.VK_TAB && plain -> return KeyOutcome.Sent.also { session.send("\t") }
      }
      val code = session.keyCode(awt.keyCode, modifiers) ?: return KeyOutcome.Ignored
      session.send(code)
      KeyOutcome.Sent
    }
    AwtKeyEvent.KEY_TYPED -> {
      val char = awt.keyChar
      // Ctrl+Shift shortcuts also arrive as typed control characters; they were handled on press.
      if (command || (!isMac && ctrlShift) || char == AwtKeyEvent.CHAR_UNDEFINED || char in keysSentOnPress) return KeyOutcome.Ignored
      session.send(char.toString())
      KeyOutcome.Sent
    }
    else -> KeyOutcome.Ignored
  }
}
