package dev.lemonadecode.platform.terminal

import com.jediterm.core.typeahead.TerminalTypeAheadManager
import com.jediterm.core.typeahead.TypeAheadTerminalModel
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.RequestOrigin
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.TerminalExecutorServiceManager
import com.jediterm.terminal.TerminalStarter
import com.jediterm.terminal.TtyBasedArrayDataStream
import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.JediTerminal
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalSelection
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem
import com.jediterm.terminal.util.CharUtils
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** What a running terminal reports besides the screen itself. */
public sealed interface TerminalSignal {
  /** The program wrote output. */
  public data object Output : TerminalSignal

  /** The program reported what it is doing; see [ProgramStatus]. */
  public data class Program(val status: ProgramStatus) : TerminalSignal

  public data class Exited(val exitCode: Int) : TerminalSignal
}

/**
 * How to launch the program behind a terminal. [restoredOutput] is shown first, above whatever
 * the program prints: the output of an earlier session, brought back.
 */
public data class TerminalLaunch(
  val command: List<String>,
  val workingDirectory: String,
  val environment: Map<String, String> = emptyMap(),
  val columns: Int = 120,
  val rows: Int = 32,
  val restoredOutput: List<String> = emptyList(),
)

/**
 * One program running in a pseudo-terminal, with JediTerm's emulator keeping the screen model.
 * The screen is read from [textBuffer]; [version] ticks whenever it changes so UI can redraw.
 */
public class TerminalSession(launch: TerminalLaunch) : AutoCloseable {
  private val process: PtyProcess = PtyProcessBuilder(launch.command.toTypedArray())
    // On Windows a folder may come written as git writes it, `C:/Users/me/repo`; File makes it `C:\Users\me\repo`.
    .setDirectory(if (IsWindows) File(launch.workingDirectory).path else launch.workingDirectory)
    .setEnvironment(System.getenv() + TerminalEnvironment + launch.environment)
    .setInitialColumns(launch.columns)
    .setInitialRows(launch.rows)
    .setConsole(false)
    // Windows: ConPTY (pty4j bundles it) rather than winpty, which drops sequences such as the
    // folder reports. Ignored elsewhere.
    .setUseWinConPty(true)
    .start()

  private val signalFlow = MutableSharedFlow<TerminalSignal>(
    extraBufferCapacity = 64,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  private val versionFlow = MutableStateFlow(0L)
  private val display = SessionDisplay()
  private val closed = AtomicBoolean(false)
  private val executors = SessionExecutors()

  private val outputFlow = MutableStateFlow(0L)

  /** The folder the shell last reported (OSC 7 or 9;9), for when the OS can't be asked. */
  @Volatile internal var reportedDirectory: String? = null
    private set

  public val signals: SharedFlow<TerminalSignal> = signalFlow.asSharedFlow()
  public val version: StateFlow<Long> = versionFlow.asStateFlow()

  /**
   * How many times the program has written output. Unlike [signals], it can be read at any time,
   * so waiting on it never misses output that came before anyone was listening.
   */
  public val outputs: StateFlow<Long> = outputFlow.asStateFlow()

  private val styleState = StyleState()
  public val textBuffer: TerminalTextBuffer = TerminalTextBuffer(launch.columns, launch.rows, styleState)
  private val terminal = JediTerminal(display, textBuffer, styleState)
  private val connector = PtyConnector(
    process,
    onOutput = {
      outputFlow.update { it + 1 }
      signalFlow.tryEmit(TerminalSignal.Output)
    },
    onStatus = { status -> signalFlow.tryEmit(TerminalSignal.Program(status)) },
    onFolder = { folder -> reportedDirectory = folder },
  )
  private val starter = TerminalStarter(
    terminal,
    connector,
    TtyBasedArrayDataStream(connector),
    TerminalTypeAheadManager(NoTypeAhead),
    executors,
  )

  public val cursorColumn: Int get() = terminal.cursorX - 1
  public val cursorRow: Int get() = terminal.cursorY - 1
  public val cursorVisible: Boolean get() = display.showsCursor
  public val columns: Int get() = textBuffer.width
  public val rows: Int get() = textBuffer.height

  /**
   * Lines above the screen that can be scrolled back to. None while a full-screen program has the
   * alternate screen: like xterm's, it keeps no scrollback. JediTerm files whatever is erased or
   * scrolled off it there, so every repaint of a full-screen app would otherwise pile up above it.
   */
  public val scrollbackLines: Int get() = if (textBuffer.isUsingAlternateBuffer) 0 else textBuffer.historyLinesCount

  /** Whether the program asked for mouse reports (xterm mouse tracking), so the wheel is its to handle. */
  public val reportsMouse: Boolean get() = display.trackingMode in MouseTracking
  public val isRunning: Boolean get() = process.isAlive

  /** The id of the program's process (the shell, for a session). */
  public val pid: Long get() = process.pid()

  /**
   * The current folder of the program the shell started that holds the terminal, else the shell's
   * own, read from the OS. A program that moves without the shell, like Claude Code entering a
   * worktree, is followed too. Where the OS can't be asked (Windows), the folder the shell last
   * reported, if it exists here. Null when it can't be told. Blocks while it asks the OS.
   */
  public fun currentDirectory(): String? {
    if (!process.isAlive) return null
    if (!ProcessDirectory.isSupported) return reportedDirectory?.takeIf { File(it).isDirectory }
    return programGroup()?.let(ProcessDirectory::of) ?: ProcessDirectory.of(process.pid())
  }

  /**
   * The last [maxLines] lines of output, history included, as text: soft-wrapped lines joined back
   * into one, trailing blanks dropped. What [TerminalLaunch.restoredOutput] takes to bring them back.
   */
  public fun recentOutput(maxLines: Int): List<String> {
    textBuffer.lock()
    try {
      val lines = mutableListOf<String>()
      val current = StringBuilder()
      for (index in -scrollbackLines until textBuffer.height) {
        val line = textBuffer.getLine(index)
        current.append(line.text.replace(CharUtils.DWC.toString(), "").replace(CharUtils.NUL_CHAR, ' '))
        if (!line.isWrapped) {
          lines += current.toString().trimEnd()
          current.setLength(0)
        }
      }
      if (current.isNotEmpty()) lines += current.toString().trimEnd()
      return lines.dropLastWhile { it.isBlank() }.takeLast(maxLines)
    } finally {
      textBuffer.unlock()
    }
  }

  /**
   * Whether a program the shell started holds the terminal (true) or the shell is back at its
   * prompt (false); null when it can't be told. Blocks while it asks the OS.
   */
  public fun programInForeground(): Boolean? {
    if (!process.isAlive) return false
    val group = ForegroundProcess.groupOf(process.pid()) ?: return null
    return group != process.pid()
  }

  /**
   * The name of the program the shell started that holds the terminal, e.g. `claude`; null while
   * the shell is at its prompt or when it can't be told. Blocks while it asks the OS.
   */
  public fun foregroundProgram(): String? {
    if (!process.isAlive) return null
    return programGroup()?.let(ForegroundProcess::nameOf)
  }

  /** The process group of the program the shell started that holds the terminal; null while the shell does. */
  private fun programGroup(): Long? = ForegroundProcess.groupOf(process.pid())?.takeIf { it != process.pid() }

  init {
    // Cells inside an OSC 8 link get tagged with where it points, for the view to open.
    terminal.setUrlHyperlinkFilter { uri -> LinkResult(LinkResultItem(0, uri.length, UrlLink(uri))) }
    textBuffer.addModelListener { versionFlow.value++ }
    launch.restoredOutput.forEach { line ->
      terminal.writeString(line)
      terminal.carriageReturn()
      terminal.newLine()
    }
    val _ = thread(name = "lemonade-code-terminal-${process.pid()}", isDaemon = true) { starter.start() }
    val _ = thread(name = "lemonade-code-terminal-exit-${process.pid()}", isDaemon = true) {
      val code = process.waitFor()
      signalFlow.tryEmit(TerminalSignal.Exited(code))
      versionFlow.value++
    }
  }

  /** Sends raw input, as if typed. */
  public fun send(text: String) {
    if (closed.get()) return
    starter.sendString(text, true)
  }

  public fun send(bytes: ByteArray) {
    if (closed.get()) return
    starter.sendBytes(bytes, true)
  }

  /**
   * Pastes [text] the way a terminal paste does: wrapped in bracketed-paste markers when the
   * program asked for them, so multi-line text lands as one block instead of running line by line.
   */
  public fun paste(text: String) {
    val normalized = text.replace("\r\n", "\n").replace('\n', '\r')
    send(if (display.bracketedPaste) "$PasteStart$normalized$PasteEnd" else normalized)
  }

  /**
   * Reports [turns] turns of the wheel, [up] or down, over the cell at [column], [row] (counted from
   * 0) to the program, in the format it asked for. Sends nothing and returns false when it didn't ask
   * for mouse reports.
   */
  public fun sendWheel(column: Int, row: Int, up: Boolean, turns: Int): Boolean {
    if (!reportsMouse || turns < 1) return false
    val report = mouseReport(display.reportFormat, if (up) WheelUp else WheelDown, column + 1, row + 1)
    repeat(turns) { send(report) }
    return true
  }

  /** Escape sequence for a non-text key (arrows, Enter, function keys), or null for text keys. */
  public fun keyCode(awtKeyCode: Int, awtModifiersEx: Int): ByteArray? = terminal.getCodeForKey(awtKeyCode, awtModifiersEx)

  public fun resize(columns: Int, rows: Int) {
    if (columns < 2 || rows < 2 || (columns == this.columns && rows == this.rows)) return
    starter.postResize(TermSize(columns, rows), RequestOrigin.User)
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    starter.requestEmulatorStop()
    connector.close()
    executors.shutdownWhenAllExecuted()
  }

  private inner class SessionDisplay : TerminalDisplay {
    @Volatile var showsCursor: Boolean = true

    @Volatile var bracketedPaste: Boolean = false

    @Volatile var trackingMode: MouseMode = MouseMode.MOUSE_REPORTING_NONE

    @Volatile var reportFormat: MouseFormat = MouseFormat.MOUSE_FORMAT_XTERM
    private var title = ""

    override fun setCursor(x: Int, y: Int) {
      versionFlow.value++
    }

    override fun setCursorShape(cursorShape: CursorShape?) = Unit

    override fun beep() = Unit

    override fun scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int) = Unit

    override fun setCursorVisible(isCursorVisible: Boolean) {
      showsCursor = isCursorVisible
      versionFlow.value++
    }

    override fun useAlternateScreenBuffer(useAlternateScreenBuffer: Boolean) {
      versionFlow.value++
    }

    override fun getWindowTitle(): String = title

    override fun setWindowTitle(windowTitle: String) {
      title = windowTitle
    }

    override fun getSelection(): TerminalSelection? = null

    override fun terminalMouseModeSet(mouseMode: MouseMode) {
      trackingMode = mouseMode
    }

    override fun setMouseFormat(mouseFormat: MouseFormat) {
      reportFormat = mouseFormat
    }

    override fun ambiguousCharsAreDoubleWidth(): Boolean = false

    override fun setBracketedPasteMode(bracketedPasteModeEnabled: Boolean) {
      bracketedPaste = bracketedPasteModeEnabled
    }
  }

  private companion object {
    const val PasteStart = "\u001b[200~"
    const val PasteEnd = "\u001b[201~"

    /** xterm's button codes for the wheel. */
    const val WheelUp = 64
    const val WheelDown = 65
    val MouseTracking = setOf(
      MouseMode.MOUSE_REPORTING_NORMAL,
      MouseMode.MOUSE_REPORTING_HILITE,
      MouseMode.MOUSE_REPORTING_BUTTON_MOTION,
      MouseMode.MOUSE_REPORTING_ALL_MOTION,
    )
    val TerminalEnvironment = mapOf(
      "TERM" to "xterm-256color",
      "COLORTERM" to "truecolor",
      "TERM_PROGRAM" to "lemonade-code",
    )
  }
}

private class PtyConnector(
  private val process: PtyProcess,
  private val onOutput: () -> Unit,
  onStatus: (ProgramStatus) -> Unit,
  onFolder: (String) -> Unit,
) : ProcessTtyConnector(process, StandardCharsets.UTF_8) {
  // Answered from the reading thread, so the reply goes out before any later query's.
  private val reports = OscScanner(
    reply = { write(it.toByteArray(StandardCharsets.UTF_8)) },
    report = onStatus,
    folder = onFolder,
    windows = IsWindows,
  )

  override fun read(buf: CharArray, offset: Int, length: Int): Int {
    val read = super.read(buf, offset, length)
    if (read > 0) {
      reports.scan(buf, offset, read)
      onOutput()
    }
    return read
  }

  override fun resize(termSize: TermSize) {
    if (process.isAlive) process.winSize = WinSize(termSize.columns, termSize.rows)
  }

  override fun getName(): String = "lemonade-code"

  override fun isConnected(): Boolean = process.isAlive
}

private val IsWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

/**
 * An xterm mouse report of [button] at [column], [row] (counted from 1), in [format]: SGR (1006) and
 * urxvt (1015) as text, the UTF-8 extension (1005) with each value as a character, and the original
 * format as raw bytes, which can't go past column or row 223.
 */
internal fun mouseReport(format: MouseFormat, button: Int, column: Int, row: Int): ByteArray = when (format) {
  MouseFormat.MOUSE_FORMAT_SGR -> "\u001b[<$button;$column;${row}M".toByteArray(StandardCharsets.UTF_8)
  MouseFormat.MOUSE_FORMAT_URXVT -> "\u001b[${button + 32};$column;${row}M".toByteArray(StandardCharsets.UTF_8)
  MouseFormat.MOUSE_FORMAT_XTERM_EXT -> buildString {
    append("\u001b[M")
    appendCodePoint(32 + button)
    appendCodePoint(32 + column)
    appendCodePoint(32 + row)
  }.toByteArray(StandardCharsets.UTF_8)
  MouseFormat.MOUSE_FORMAT_XTERM -> byteArrayOf(0x1b, '['.code.toByte(), 'M'.code.toByte()) +
    listOf(button, column, row).map { (32 + it).coerceAtMost(255).toByte() }
}

private class SessionExecutors : TerminalExecutorServiceManager {
  private val single: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "lemonade-code-terminal-io").apply { isDaemon = true }
  }
  private val unbounded: ExecutorService = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "lemonade-code-terminal-worker").apply { isDaemon = true }
  }

  override fun getSingleThreadScheduledExecutor(): ScheduledExecutorService = single

  override fun getUnboundedExecutorService(): ExecutorService = unbounded

  override fun shutdownWhenAllExecuted() {
    single.shutdown()
    unbounded.shutdown()
  }
}

private object NoTypeAhead : TypeAheadTerminalModel {
  override fun insertCharacter(ch: Char, index: Int) = Unit
  override fun removeCharacters(from: Int, count: Int) = Unit
  override fun moveCursor(index: Int) = Unit
  override fun forceRedraw() = Unit
  override fun clearPredictions() = Unit
  override fun lock() = Unit
  override fun unlock() = Unit
  override fun isUsingAlternateBuffer(): Boolean = false
  override fun getCurrentLineWithCursor() = TypeAheadTerminalModel.LineWithCursorX(StringBuffer(), 0)
  override fun getTerminalWidth(): Int = 0
  override fun isTypeAheadEnabled(): Boolean = false
  override fun getLatencyThreshold(): Long = 0
  override fun getShellType() = TypeAheadTerminalModel.ShellType.Unknown
}
