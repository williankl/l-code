package dev.lemonadecode.workspace.ui

import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.teya.lemonade.Button
import com.teya.lemonade.Card
import com.teya.lemonade.Icon
import com.teya.lemonade.IconButton
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.Link
import com.teya.lemonade.SymbolContainer
import com.teya.lemonade.Tag
import com.teya.lemonade.Text
import com.teya.lemonade.TextArea
import com.teya.lemonade.ThemedHue
import com.teya.lemonade.core.LemonadeAssetSize
import com.teya.lemonade.core.LemonadeButtonSize
import com.teya.lemonade.core.LemonadeButtonType
import com.teya.lemonade.core.LemonadeButtonVariant
import com.teya.lemonade.core.LemonadeCardBackground
import com.teya.lemonade.core.LemonadeCardPadding
import com.teya.lemonade.core.LemonadeIcons
import com.teya.lemonade.core.LemonadeLinkSize
import com.teya.lemonade.core.SymbolContainerSize
import com.teya.lemonade.core.TagVoice
import dev.lemonadecode.platform.designsystem.AnimatedItems
import dev.lemonadecode.platform.designsystem.AnimatedPresence
import dev.lemonadecode.platform.designsystem.LemonadeCodeAnimation
import dev.lemonadecode.platform.designsystem.LemonadeCodeCodeColors
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.platform.localization.resolve
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.action_add_comment
import dev.lemonadecode.workspace.strings.action_cancel
import dev.lemonadecode.workspace.strings.comment_delete
import dev.lemonadecode.workspace.strings.comment_in_session
import dev.lemonadecode.workspace.strings.comment_on_line
import dev.lemonadecode.workspace.strings.comment_placeholder
import dev.lemonadecode.workspace.strings.comment_sent_to
import dev.lemonadecode.workspace.strings.comment_unsent
import dev.lemonadecode.workspace.strings.file_show_full
import dev.lemonadecode.workspace.strings.file_show_full_link
import dev.lemonadecode.workspace.strings.file_unchanged_below
import dev.lemonadecode.workspace.strings.hunk_show_hidden
import dev.lemonadecode.workspace.uimodel.CodeTokenKind
import dev.lemonadecode.workspace.uimodel.CodeTokenModel
import dev.lemonadecode.workspace.uimodel.CommentComposerModel
import dev.lemonadecode.workspace.uimodel.CommentStatusModel
import dev.lemonadecode.workspace.uimodel.DiffFileModel
import dev.lemonadecode.workspace.uimodel.DiffLineKind
import dev.lemonadecode.workspace.uimodel.DiffRowModel
import dev.lemonadecode.workspace.uimodel.ReviewCommentModel
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import kotlinx.coroutines.flow.drop
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.ceil

/** Plus, old number, new number and sign columns: 32 + 44 + 44 + 24. */
private val GutterWidth = 144.dp
private val CardMaxWidth = 880.dp

/** Root-space vertical extent of each line row, so a drag from + can find the line under it. */
private typealias LineBounds = MutableMap<Int, ClosedFloatingPointRange<Float>>

/**
 * How many items of the diff's list a file's body takes: its preview, or its rows, at least one
 * so there is always an item to close the file's card.
 */
internal val DiffFileModel.bodyItemCount: Int
  get() = when {
    !expanded -> 0
    preview?.showing == true -> 1
    else -> maxOf(rows.size, 1)
  }

/**
 * How the diff's items move: lines that come, like those of a file being opened, are there at once
 * and what's below glides down off them; lines that go leave at once and what's below glides up.
 * Nothing fades: a fading item kept composed off screen, like the composer while it holds focus,
 * never finishes fading in, so the list never comes to rest.
 */
@Composable
internal fun LazyItemScope.diffItemMotion(): Modifier = Modifier.animateItem(
  fadeInSpec = null,
  placementSpec = LemonadeCodeAnimation.specOrNull(LemonadeCodeAnimation.PanelMillis),
  fadeOutSpec = null,
)

/**
 * Rows of one file, each an item of the diff's list, so only the lines on screen are composed.
 * Code scrolls sideways as one block through [state]; comment cards and the composer stay pinned
 * to the left edge while it does, like `position: sticky` in the design.
 */
internal fun LazyListScope.diffFileRows(
  file: DiffFileModel,
  state: DiffFileRowsState,
  measure: CodeMeasure,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val rows = file.rows
  items(
    count = file.bodyItemCount,
    key = { index -> rows.getOrNull(index)?.let { "row:${file.id}:${it.key}" } ?: "end:${file.id}" },
    contentType = { index ->
      when (rows.getOrNull(index)) {
        is DiffRowModel.Line -> "line"
        is DiffRowModel.Hunk -> "hunk"
        is DiffRowModel.FileTail -> "tail"
        null -> "end"
      }
    },
  ) { index ->
    val row = rows.getOrNull(index)
    // The item gets only what it draws, not the file: comparing a file's rows to skip an
    // unchanged item would cost as much as the file is long.
    DiffRowItem(
      modifier = diffItemMotion(),
      fileId = file.id,
      fileName = file.fileName,
      row = row,
      last = index >= rows.lastIndex,
      codeWidthPx = if (row is DiffRowModel.Line || row is DiffRowModel.Hunk) state.codeWidth(rows, measure) else 0,
      state = state,
      onEvent = onEvent,
    )
  }
}

/**
 * What a file's rows share while they scroll in and out of the list: the code's sideways scroll,
 * where each line on screen is, and how wide the code is.
 */
@Stable
internal class DiffFileRowsState {
  val scroll: ScrollState = ScrollState(initial = 0)
  val lineBounds: LineBounds = HashMap()
  private var measuredRows: List<DiffRowModel>? = null
  private var measuredWith: CodeMeasure? = null
  private var width = 0

  /**
   * The width, in pixels, of every row that scrolls with the code: that of the widest one, as when
   * the rows were one column as wide as its widest. Measured again only when the code changes, not
   * for a selection, comment or composer over it.
   */
  fun codeWidth(rows: List<DiffRowModel>, measure: CodeMeasure): Int {
    if (measure !== measuredWith || !sameCode(rows, measuredRows)) {
      width = rows.maxOfOrNull(measure::rowWidth) ?: 0
      measuredWith = measure
    }
    measuredRows = rows
    return width
  }

  private fun sameCode(rows: List<DiffRowModel>, measured: List<DiffRowModel>?): Boolean = rows === measured ||
    (measured != null && rows.size == measured.size && rows.indices.all { sameCode(rows[it], measured[it]) })

  private fun sameCode(row: DiffRowModel, measured: DiffRowModel): Boolean = row === measured ||
    when (row) {
      is DiffRowModel.Line -> measured is DiffRowModel.Line && row.tokens === measured.tokens
      is DiffRowModel.Hunk -> measured is DiffRowModel.Hunk && row.header == measured.header
      is DiffRowModel.FileTail -> measured is DiffRowModel.FileTail
    }
}

/**
 * Measures a row of code as it lays itself out: gutter, text and the space after it. The code font
 * is monospace, so text of printable ASCII is its length in cells; only text with anything else in
 * it, like a tab or a wide character, is laid out to be measured.
 */
internal class CodeMeasure(
  private val measurer: TextMeasurer,
  private val style: TextStyle,
  private val code: LemonadeCodeCodeColors,
  private val gutter: Int,
  private val endPadding: Int,
) {
  private val cells = HashMap<CodeTokenKind, Float>()

  /** Should the system's monospace font be anything but, every line is laid out to be measured. */
  private val monospace = abs(cellOf("i", CodeTokenKind.Plain) - cellOf("W", CodeTokenKind.Plain)) < 0.01f

  fun rowWidth(row: DiffRowModel): Int = when (row) {
    is DiffRowModel.Line -> gutter + ceil(lineWidth(row.tokens)).toInt() + endPadding
    is DiffRowModel.Hunk -> gutter + ceil(textWidth(row.header)).toInt() + endPadding
    is DiffRowModel.FileTail -> 0
  }

  private fun lineWidth(tokens: List<CodeTokenModel>): Float {
    var width = 0f
    for (token in tokens) {
      if (!monospace || !token.text.isPrintableAscii()) return measured(codeLine(tokens, code))
      width += token.text.length * cell(token.kind)
    }
    return width
  }

  private fun textWidth(text: String): Float = if (monospace && text.isPrintableAscii()) {
    text.length * cell(CodeTokenKind.Plain)
  } else {
    measured(AnnotatedString(text))
  }

  private fun cell(kind: CodeTokenKind): Float = cells.getOrPut(kind) { cellOf("0", kind) }

  /** The advance of [char] in the style of [kind], to a hundredth of a pixel. */
  private fun cellOf(char: String, kind: CodeTokenKind): Float = measured(
    buildAnnotatedString { withStyle(kind.span(code)) { append(char.repeat(CellSample)) } },
  ) / CellSample

  private fun measured(text: AnnotatedString): Float = measurer.measure(text, style, softWrap = false, maxLines = 1).multiParagraph.intrinsics.maxIntrinsicWidth

  private fun String.isPrintableAscii(): Boolean = all { it in ' '..'~' }

  private companion object {
    const val CellSample = 100
  }
}

@Composable
internal fun rememberCodeMeasure(): CodeMeasure {
  val measurer = rememberTextMeasurer(cacheSize = 0)
  val style = LemonadeCodeTheme.typography.code
  val code = LemonadeCodeTheme.code
  val density = LocalDensity.current
  val gutter = with(density) { GutterWidth.roundToPx() }
  val endPadding = with(density) { LemonadeTheme.spaces.spacing600.roundToPx() }
  return remember(measurer, style, code, gutter, endPadding) { CodeMeasure(measurer, style, code, gutter, endPadding) }
}

@Composable
private fun DiffRowItem(
  modifier: Modifier,
  fileId: String,
  fileName: String,
  row: DiffRowModel?,
  last: Boolean,
  codeWidthPx: Int,
  state: DiffFileRowsState,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val radius = LemonadeTheme.radius.radius500
  val background = LemonadeTheme.colors.background.bgSubtle
  val style = LemonadeCodeTheme.typography.code
  val codeWidth = with(LocalDensity.current) { codeWidthPx.toDp() }
  // The last item closes the file's card, as its header opens it.
  Column(
    modifier = modifier.then(
      if (last) {
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(bottomStart = radius, bottomEnd = radius))
          .background(background)
          .padding(bottom = LemonadeTheme.spaces.spacing200)
      } else {
        Modifier.fillMaxWidth().background(background)
      },
    ),
  ) {
    when (row) {
      is DiffRowModel.Hunk -> HunkRow(
        hunk = row,
        style = style,
        codeWidth = codeWidth,
        scroll = state.scroll,
        onExpand = { onEvent(WorkspaceEvent.ShowFullFile(fileId)) },
      )
      is DiffRowModel.Line -> {
        DiffLineRow(
          fileId = fileId,
          fileName = fileName,
          line = row,
          style = style,
          codeWidth = codeWidth,
          scroll = state.scroll,
          lineBounds = state.lineBounds,
          onEvent = onEvent,
        )
        // Cards fade in where they open, and the lines below glide down to make room. Ones already
        // there as the line scrolls into view just show.
        AnimatedItems(
          items = row.comments,
          key = { it.id },
          enter = LemonadeCodeAnimation.fadeIn(),
          exit = LemonadeCodeAnimation.fadeOut(LemonadeCodeAnimation.PopoverOutMillis),
        ) { _, comment ->
          PinnedSlot(scroll = state.scroll) {
            ReviewCommentCard(comment = comment, onEvent = onEvent)
          }
        }
        // Gone at once when it closes: as a comment it's sent, the card takes its place.
        AnimatedPresence(value = row.composer, enter = LemonadeCodeAnimation.fadeIn(), exit = ExitTransition.None) { composer ->
          PinnedSlot(scroll = state.scroll) {
            CommentComposerCard(composer = composer, onEvent = onEvent)
          }
        }
      }
      is DiffRowModel.FileTail -> FileTailRow(
        tail = row,
        scroll = state.scroll,
        onExpand = { onEvent(WorkspaceEvent.ShowFullFile(fileId)) },
      )
      null -> Unit
    }
  }
}

/**
 * Scrolls the code sideways from over something that stays put while it does, like a comment card,
 * as it did when everything sat in the code's scrolling column.
 */
@Composable
private fun Modifier.scrollsCode(scroll: ScrollState): Modifier = scrollable(
  state = scroll,
  orientation = Orientation.Horizontal,
  reverseDirection = ScrollableDefaults.reverseDirection(LocalLayoutDirection.current, Orientation.Horizontal, reverseScrolling = false),
)

/** A row that scrolls with the code: as wide as the widest, and never narrower than the list. */
private fun Modifier.scrollingWithCode(scroll: ScrollState, codeWidth: Dp): Modifier = fillMaxWidth()
  .horizontalScroll(scroll)
  .width(codeWidth)

@Composable
private fun PinnedSlot(
  scroll: ScrollState,
  content: @Composable () -> Unit,
) {
  val spaces = LemonadeTheme.spaces
  Box(modifier = Modifier.fillMaxWidth().scrollsCode(scroll)) {
    Box(
      modifier = Modifier
        .widthIn(max = CardMaxWidth)
        .fillMaxWidth()
        .padding(start = GutterWidth, end = spaces.spacing400, top = spaces.spacing200, bottom = spaces.spacing200),
    ) {
      content()
    }
  }
}

@Composable
private fun HunkRow(
  hunk: DiffRowModel.Hunk,
  style: TextStyle,
  codeWidth: Dp,
  scroll: ScrollState,
  onExpand: () -> Unit,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .scrollingWithCode(scroll, codeWidth)
      .heightIn(min = 36.dp)
      .background(LemonadeTheme.colors.background.bgElevated),
  ) {
    Box(modifier = Modifier.width(GutterWidth).padding(start = LemonadeTheme.spaces.spacing100)) {
      if (hunk.hiddenLineCount > 0) {
        LemonadeUi.IconButton(
          icon = LemonadeIcons.ChevronsUpDown,
          contentDescription = pluralStringResource(Res.plurals.hunk_show_hidden, hunk.hiddenLineCount, hunk.hiddenLineCount),
          onClick = onExpand,
          variant = LemonadeButtonVariant.Neutral,
          type = LemonadeButtonType.Ghost,
          size = LemonadeButtonSize.XSmall,
        )
      }
    }
    Mono(
      text = hunk.header,
      style = style,
      color = LemonadeTheme.colors.content.contentSecondary,
      maxLines = 1,
      // Measured on its own, so a header can't wrap should it come out a hair wider than measured.
      modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).padding(end = LemonadeTheme.spaces.spacing600),
    )
  }
}

@Composable
private fun FileTailRow(
  tail: DiffRowModel.FileTail,
  scroll: ScrollState,
  onExpand: () -> Unit,
) {
  val secondary = LemonadeTheme.colors.content.contentSecondary
  Box(
    contentAlignment = Alignment.CenterStart,
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 36.dp)
      .background(LemonadeTheme.colors.background.bgElevated)
      .scrollsCode(scroll),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.widthIn(max = CardMaxWidth).fillMaxWidth(),
    ) {
      Box(modifier = Modifier.width(GutterWidth).padding(start = LemonadeTheme.spaces.spacing100)) {
        LemonadeUi.IconButton(
          icon = LemonadeIcons.ChevronsUpDown,
          contentDescription = stringResource(Res.string.file_show_full),
          onClick = onExpand,
          variant = LemonadeButtonVariant.Neutral,
          type = LemonadeButtonType.Ghost,
          size = LemonadeButtonSize.XSmall,
        )
      }
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing100),
      ) {
        LemonadeUi.Text(
          text = pluralStringResource(Res.plurals.file_unchanged_below, tail.hiddenLineCount, tail.hiddenLineCount),
          textStyle = LemonadeTheme.typography.bodySmallRegular,
          color = secondary,
        )
        LemonadeUi.Text(text = "·", textStyle = LemonadeTheme.typography.bodySmallRegular, color = secondary)
        LemonadeUi.Link(text = stringResource(Res.string.file_show_full_link), onClick = onExpand, size = LemonadeLinkSize.Small)
      }
    }
  }
}

@Composable
private fun DiffLineRow(
  fileId: String,
  fileName: String,
  line: DiffRowModel.Line,
  style: TextStyle,
  codeWidth: Dp,
  scroll: ScrollState,
  lineBounds: LineBounds,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val colors = LemonadeTheme.colors
  val interactionSource = remember { MutableInteractionSource() }
  val hovered by interactionSource.collectIsHoveredAsState()
  var pressing by remember { mutableStateOf(false) }
  val selectionEdge = colors.content.contentBrand
  val tertiary = colors.content.contentTertiary

  DisposableEffect(fileId, line.index) {
    onDispose { lineBounds.remove(line.index) }
  }

  Row(
    modifier = Modifier
      .scrollingWithCode(scroll, codeWidth)
      .heightIn(min = 20.dp)
      .background(
        when (line.kind) {
          DiffLineKind.Added -> colors.background.bgPositiveSubtle
          DiffLineKind.Removed -> colors.background.bgCriticalSubtle
          DiffLineKind.Context -> Color.Transparent
        },
      )
      .drawBehind {
        if (line.selected) drawRect(color = selectionEdge, size = Size(3.dp.toPx(), size.height))
      }
      .hoverable(interactionSource)
      .onGloballyPositioned { coordinates ->
        // Unclipped, so a line half out of view still maps to where it really is.
        val top = coordinates.positionInRoot().y
        lineBounds[line.index] = top..(top + coordinates.size.height)
      },
  ) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.width(32.dp).height(20.dp)) {
      if (hovered || pressing || line.isSelectionAnchor) {
        CommentPlusButton(
          fileId = fileId,
          lineIndex = line.index,
          label = stringResource(Res.string.comment_on_line, "$fileName:${line.newNumber ?: line.oldNumber}"),
          lineBounds = lineBounds,
          onPressChange = { pressing = it },
          onEvent = onEvent,
        )
      }
    }
    Mono(
      text = line.oldNumber?.toString().orEmpty(),
      style = style.copy(textAlign = TextAlign.End),
      color = tertiary,
      modifier = Modifier.width(44.dp).padding(end = LemonadeTheme.spaces.spacing200),
    )
    Mono(
      text = line.newNumber?.toString().orEmpty(),
      style = style.copy(textAlign = TextAlign.End),
      color = tertiary,
      modifier = Modifier.width(44.dp).padding(end = LemonadeTheme.spaces.spacing200),
    )
    Mono(
      text = when (line.kind) {
        DiffLineKind.Added -> "+"
        DiffLineKind.Removed -> "−"
        DiffLineKind.Context -> ""
      },
      style = style.copy(textAlign = TextAlign.Center),
      color = if (line.kind == DiffLineKind.Added) colors.content.contentPositive else colors.content.contentCritical,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.width(24.dp),
    )
    LemonadeUi.Text(
      text = rememberCodeLine(line.tokens, LemonadeCodeTheme.code),
      textStyle = style,
      maxLines = 1,
      // Measured on its own, so a line can't wrap should it come out a hair wider than measured.
      modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).padding(end = LemonadeTheme.spaces.spacing600),
    )
  }
}

/**
 * The lime + in the gutter. Press to start a range on this line, drag over other lines to grow
 * it, release to open the composer. Shift-press extends a range that is already open.
 */
@Composable
private fun CommentPlusButton(
  fileId: String,
  lineIndex: Int,
  label: String,
  lineBounds: LineBounds,
  onPressChange: (Boolean) -> Unit,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val colors = LemonadeTheme.colors
  val currentOnEvent by rememberUpdatedState(onEvent)
  val currentOnPressChange by rememberUpdatedState(onPressChange)
  val interactionSource = remember { MutableInteractionSource() }
  val hovered by interactionSource.collectIsHoveredAsState()
  var originY by remember { mutableFloatStateOf(0f) }

  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .size(18.dp)
      .clip(LemonadeTheme.shapes.radius150)
      .background(if (hovered) colors.interaction.bgBrandInteractive else colors.background.bgBrand)
      .hoverable(interactionSource)
      .onGloballyPositioned { originY = it.positionInRoot().y }
      .semantics {
        contentDescription = label
        role = Role.Button
        onClick {
          currentOnEvent(WorkspaceEvent.StartCommentRange(fileId, lineIndex))
          currentOnEvent(WorkspaceEvent.EndCommentRange)
          true
        }
      }
      .pointerInput(fileId, lineIndex) {
        awaitEachGesture {
          val down = awaitFirstDown()
          down.consume()
          if (currentEvent.keyboardModifiers.isShiftPressed) {
            currentOnEvent(WorkspaceEvent.ExtendCommentRange(fileId, lineIndex))
            waitForUpOrCancellation()
            return@awaitEachGesture
          }
          currentOnPressChange(true)
          currentOnEvent(WorkspaceEvent.StartCommentRange(fileId, lineIndex))
          var last = lineIndex
          try {
            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              if (!change.pressed) {
                change.consume()
                break
              }
              val y = originY + change.position.y
              val hit = lineBounds.entries.firstOrNull { y in it.value }?.key
              if (hit != null && hit != last) {
                last = hit
                currentOnEvent(WorkspaceEvent.DragCommentRange(fileId, hit))
              }
              change.consume()
            }
          } finally {
            currentOnPressChange(false)
          }
          currentOnEvent(WorkspaceEvent.EndCommentRange)
        }
      },
  ) {
    LemonadeUi.Icon(
      icon = LemonadeIcons.Plus,
      contentDescription = null,
      size = LemonadeAssetSize.XSmall,
      tint = colors.content.contentOnBrandHigh,
    )
  }
}

@Composable
private fun rememberCodeLine(tokens: List<CodeTokenModel>, code: LemonadeCodeCodeColors): AnnotatedString = remember(tokens, code) { codeLine(tokens, code) }

private fun codeLine(tokens: List<CodeTokenModel>, code: LemonadeCodeCodeColors): AnnotatedString = buildAnnotatedString {
  tokens.forEach { token ->
    withStyle(token.kind.span(code)) { append(token.text) }
  }
}

private fun CodeTokenKind.span(code: LemonadeCodeCodeColors): SpanStyle = SpanStyle(
  color = color(code),
  fontStyle = if (this == CodeTokenKind.Comment) FontStyle.Italic else FontStyle.Normal,
  fontWeight = if (this == CodeTokenKind.Heading) FontWeight.SemiBold else FontWeight.Normal,
)

private fun CodeTokenKind.color(code: LemonadeCodeCodeColors): Color = when (this) {
  CodeTokenKind.Plain -> code.plain
  CodeTokenKind.Keyword, CodeTokenKind.Heading -> code.keyword
  CodeTokenKind.String -> code.string
  CodeTokenKind.Number -> code.number
  CodeTokenKind.Function -> code.function
  CodeTokenKind.Type -> code.type
  CodeTokenKind.Comment -> code.comment
  CodeTokenKind.Punctuation -> code.punctuation
}

@Composable
private fun ReviewCommentCard(
  comment: ReviewCommentModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val spaces = LemonadeTheme.spaces
  LemonadeUi.Card(
    contentPadding = LemonadeCardPadding.Medium,
    background = LemonadeCardBackground.Default,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing200)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spaces.spacing200),
        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
      ) {
        LemonadeUi.SymbolContainer(
          text = comment.authorInitials,
          theme = ThemedHue.Indigo,
          size = SymbolContainerSize.XSmall,
        )
        LemonadeUi.Text(text = comment.authorName.resolve(), textStyle = LemonadeTheme.typography.bodySmallSemiBold)
        LemonadeUi.Text(
          text = comment.location,
          textStyle = LemonadeTheme.typography.bodyXSmallRegular,
          color = LemonadeTheme.colors.content.contentSecondary,
        )
        when (val status = comment.status) {
          CommentStatusModel.Unsent -> LemonadeUi.Tag(label = stringResource(Res.string.comment_unsent), icon = LemonadeIcons.Clock, voice = TagVoice.Neutral)
          is CommentStatusModel.Sent -> LemonadeUi.Tag(
            label = stringResource(Res.string.comment_sent_to, status.sessionName),
            icon = LemonadeIcons.CircleCheck,
            voice = TagVoice.Positive,
          )
          is CommentStatusModel.InSubSession -> LemonadeUi.Tag(
            label = stringResource(Res.string.comment_in_session, status.sessionName),
            icon = LemonadeIcons.ArrowSplit,
            voice = TagVoice.Neutral,
          )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (comment.canDelete) {
          LemonadeUi.IconButton(
            icon = LemonadeIcons.Trash,
            contentDescription = stringResource(Res.string.comment_delete),
            onClick = { onEvent(WorkspaceEvent.DeleteComment(comment.id)) },
            variant = LemonadeButtonVariant.Neutral,
            type = LemonadeButtonType.Ghost,
            size = LemonadeButtonSize.XSmall,
          )
        }
      }
      LemonadeUi.Text(text = comment.text, textStyle = LemonadeTheme.typography.bodySmallRegular)
    }
  }
}

@Composable
private fun CommentComposerCard(
  composer: CommentComposerModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val spaces = LemonadeTheme.spaces
  // The list keeps the composer while it's open, as it did when every row was there: scrolled
  // away, it keeps its text, caret and focus, and scrolling back doesn't pull the list to it again.
  val pinnable = LocalPinnableContainer.current
  DisposableEffect(pinnable) {
    val pinned = pinnable?.pin()
    onDispose { pinned?.release() }
  }
  val bringIntoView = remember { BringIntoViewRequester() }
  LaunchedEffect(composer.title) {
    withFrameNanos { }
    bringIntoView.bringIntoView()
  }
  // The field keeps its own text and caret and reports edits. Fed the draft back from the Model,
  // it could redraw against the older, shorter text before the Model caught up and drop the caret
  // to its end: the first letter typed ended up after the second.
  val draft = remember(composer.title) { TextFieldState(composer.draft) }
  val currentOnEvent by rememberUpdatedState(onEvent)
  LaunchedEffect(draft) {
    snapshotFlow { draft.text.toString() }
      .drop(1)
      .collect { currentOnEvent(WorkspaceEvent.UpdateCommentDraft(it)) }
  }
  LemonadeUi.Card(
    contentPadding = LemonadeCardPadding.Medium,
    background = LemonadeCardBackground.Default,
    modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing300)) {
      LemonadeUi.TextArea(
        state = draft,
        label = composer.title.resolve(),
        placeholderText = stringResource(Res.string.comment_placeholder),
        height = 112.dp,
        modifier = Modifier.fillMaxWidth(),
      )
      Row(
        horizontalArrangement = Arrangement.spacedBy(spaces.spacing200, Alignment.End),
        modifier = Modifier.fillMaxWidth(),
      ) {
        LemonadeUi.Button(
          label = stringResource(Res.string.action_cancel),
          onClick = { onEvent(WorkspaceEvent.CancelComment) },
          variant = LemonadeButtonVariant.Neutral,
          type = LemonadeButtonType.Subtle,
          size = LemonadeButtonSize.Small,
        )
        LemonadeUi.Button(
          label = stringResource(Res.string.action_add_comment),
          onClick = { onEvent(WorkspaceEvent.AddComment) },
          variant = LemonadeButtonVariant.Primary,
          type = LemonadeButtonType.Solid,
          size = LemonadeButtonSize.Small,
          enabled = composer.canSubmit,
        )
      }
    }
  }
}
