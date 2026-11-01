package dev.lemonadecode.platform.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextInCodePointsCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.text.input.TextEditingScope
import androidx.compose.ui.text.input.TextEditorState
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Turns the system input method on while the terminal has focus, so text it composes reaches the
 * program: dead keys (on an international layout ' then e types é, and ' then space types '),
 * IBus, CJK input. Compose only turns the input method on for its own text fields; without this a
 * dead key types nothing.
 *
 * Plain keys still arrive as key events. [onCommit] gets each piece of text the input method
 * finishes; [onComposing] the text it is still composing ("" once there is none), to show at the
 * cursor. [cursorRect], in this node's pixels, places the input method's popups.
 *
 * Must come before `focusable()` in the chain, so it hears that element's focus changes.
 */
internal fun Modifier.terminalTextInput(
  onCommit: (String) -> Unit,
  onComposing: (String) -> Unit,
  cursorRect: () -> Rect?,
): Modifier = this then TerminalTextInputElement(onCommit, onComposing, cursorRect)

private data class TerminalTextInputElement(
  val onCommit: (String) -> Unit,
  val onComposing: (String) -> Unit,
  val cursorRect: () -> Rect?,
) : ModifierNodeElement<TerminalTextInputNode>() {
  override fun create(): TerminalTextInputNode = TerminalTextInputNode(onCommit, onComposing, cursorRect)

  override fun update(node: TerminalTextInputNode) {
    node.onCommit = onCommit
    node.onComposing = onComposing
    node.cursorRect = cursorRect
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "terminalTextInput"
  }
}

private class TerminalTextInputNode(
  var onCommit: (String) -> Unit,
  var onComposing: (String) -> Unit,
  var cursorRect: () -> Rect?,
) : Modifier.Node(),
  PlatformTextInputModifierNode,
  FocusEventModifierNode,
  LayoutAwareModifierNode {
  private var session: Job? = null
  private var coordinates: LayoutCoordinates? = null

  /** Only the text being composed; finished text goes straight to the program. */
  private val editor = ComposingText()

  override fun onFocusEvent(focusState: FocusState) {
    if (focusState.isFocused) start() else stop()
  }

  override fun onPlaced(coordinates: LayoutCoordinates) {
    this.coordinates = coordinates
  }

  override fun onDetach() {
    stop()
  }

  private fun start() {
    if (session?.isActive == true) return
    session = coroutineScope.launch {
      establishTextInputSession { startInputMethod(Request()) }
    }
  }

  private fun stop() {
    session?.cancel()
    session = null
    setComposing("")
  }

  private fun setComposing(text: String) {
    if (editor.text == text) return
    editor.text = text
    onComposing(text)
  }

  private fun commit(text: CharSequence) {
    setComposing("")
    if (text.isNotEmpty()) onCommit(text.toString())
  }

  @OptIn(ExperimentalComposeUiApi::class)
  private val editing = object : TextEditingScope {
    override fun commitText(text: CharSequence, newCursorPosition: Int) = commit(text)

    override fun setComposingText(text: CharSequence, newCursorPosition: Int) = setComposing(text.toString())

    override fun finishComposingText() = commit(editor.text)

    // Text before the cursor is the program's: erase it the way a key would.
    override fun deleteSurroundingTextInCodePoints(lengthBeforeCursor: Int, lengthAfterCursor: Int) {
      if (lengthBeforeCursor > 0) onCommit(Backspace.repeat(lengthBeforeCursor))
    }
  }

  /** Where the node sits in the window, or null while it is not laid out. */
  private fun origin(): LayoutCoordinates? = coordinates?.takeIf { it.isAttached }

  @OptIn(ExperimentalComposeUiApi::class)
  private inner class Request : PlatformTextInputMethodRequest {
    override val state: TextEditorState = editor
    override val value: () -> TextFieldValue = { TextFieldValue(editor.text, editor.selection, editor.composition) }
    override val imeOptions: ImeOptions = ImeOptions(autoCorrect = false)
    override val onImeAction: ((ImeAction) -> Unit)? = null
    override val textLayoutResult: () -> TextLayoutResult? = { null }
    override val focusedRectInRoot: () -> Rect? = {
      origin()?.let { node -> cursorRect()?.translate(node.localToRoot(Offset.Zero)) }
    }
    override val textFieldRectInRoot: () -> Rect? = { origin()?.boundsInRoot() }
    override val textClippingRectInRoot: () -> Rect? = { origin()?.boundsInRoot() }
    override val unclippedTextOffsetInRoot: () -> Offset? = { origin()?.localToRoot(Offset.Zero) }
    override val editText: (TextEditingScope.() -> Unit) -> Unit = { block -> editing.block() }
    override val onEditCommand: (List<EditCommand>) -> Unit = { commands ->
      commands.forEach { command ->
        when (command) {
          is CommitTextCommand -> editing.commitText(command.text, command.newCursorPosition)
          is SetComposingTextCommand -> editing.setComposingText(command.text, command.newCursorPosition)
          is FinishComposingTextCommand -> editing.finishComposingText()
          is DeleteSurroundingTextCommand -> editing.deleteSurroundingTextInCodePoints(command.lengthBeforeCursor, 0)
          is DeleteSurroundingTextInCodePointsCommand -> editing.deleteSurroundingTextInCodePoints(command.lengthBeforeCursor, 0)
          else -> Unit
        }
      }
    }
  }
}

/** The input method's view of the terminal: the text being composed, with the caret after it. */
@OptIn(ExperimentalComposeUiApi::class)
private class ComposingText : TextEditorState {
  // Snapshot state, so the input service sees the composition end.
  var text: String by mutableStateOf("")

  override val length: Int get() = text.length

  override fun get(index: Int): Char = text[index]

  override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)

  override val selection: TextRange get() = TextRange(text.length)

  override val composition: TextRange? get() = text.takeIf { it.isNotEmpty() }?.let { TextRange(0, it.length) }
}

private const val Backspace = "\u007f"
