package dev.loupe.workspace.ui.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import dev.loupe.workspace.strings.Res
import dev.loupe.workspace.strings.preview_empty
import dev.loupe.workspace.strings.preview_loading
import dev.loupe.workspace.strings.preview_unreadable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** Files up to this size are parsed while composing, so they show in the first frame. */
private const val ImmediateParseBytes = 32 * 1024

private sealed interface ParsedText<out T> {
  data object Empty : ParsedText<Nothing>

  class Ready<T>(val value: T) : ParsedText<T>

  class Failed(val reason: String) : ParsedText<Nothing>
}

/** The file as UTF-8 text, without a byte order mark. Malformed bytes become U+FFFD. */
internal fun decodePreviewText(bytes: ByteArray): String {
  val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
  return bytes.decodeToString(startIndex = if (bom) 3 else 0, endIndex = bytes.size)
}

/**
 * Decodes [bytes] as text, parses it with [parse] (off the main thread when the file is large)
 * and draws [content] with the result. Shows a note while loading, for a blank file, or if
 * parsing fails. [key] stands for whatever else [parse] depends on.
 */
@Composable
internal fun <T> ParsedTextPreview(
  bytes: ByteArray,
  parse: (String) -> T,
  modifier: Modifier = Modifier,
  key: Any? = null,
  content: @Composable (T) -> Unit,
) {
  val currentParse by rememberUpdatedState(parse)
  val immediate = remember(bytes, key) { if (bytes.size <= ImmediateParseBytes) parseText(bytes, parse) else null }
  val background by produceState<Pair<ByteArray, ParsedText<T>>?>(initialValue = null, bytes, key) {
    if (immediate == null) value = bytes to withContext(Dispatchers.Default) { parseText(bytes, currentParse) }
  }
  val state = immediate ?: background?.takeIf { it.first === bytes }?.second
  Box(modifier = modifier.fillMaxWidth()) {
    when (state) {
      null -> PreviewMessage(stringResource(Res.string.preview_loading), loading = true)
      ParsedText.Empty -> PreviewMessage(stringResource(Res.string.preview_empty))
      is ParsedText.Failed -> PreviewMessage(stringResource(Res.string.preview_unreadable, state.reason))
      is ParsedText.Ready -> content(state.value)
    }
  }
}

private fun <T> parseText(bytes: ByteArray, parse: (String) -> T): ParsedText<T> {
  val text = decodePreviewText(bytes)
  if (text.isBlank()) return ParsedText.Empty
  return try {
    ParsedText.Ready(parse(text))
  } catch (e: Exception) {
    ParsedText.Failed(e.message ?: e::class.simpleName.orEmpty())
  }
}
