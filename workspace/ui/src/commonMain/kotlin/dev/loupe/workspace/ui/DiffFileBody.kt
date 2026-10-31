package dev.loupe.workspace.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
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
import dev.loupe.platform.designsystem.LoupeCodeColors
import dev.loupe.platform.designsystem.LoupeTheme
import dev.loupe.platform.localization.resolve
import dev.loupe.workspace.strings.Res
import dev.loupe.workspace.strings.action_add_comment
import dev.loupe.workspace.strings.action_cancel
import dev.loupe.workspace.strings.comment_delete
import dev.loupe.workspace.strings.comment_in_session
import dev.loupe.workspace.strings.comment_on_line
import dev.loupe.workspace.strings.comment_placeholder
import dev.loupe.workspace.strings.comment_sent_to
import dev.loupe.workspace.strings.comment_unsent
import dev.loupe.workspace.strings.file_show_full
import dev.loupe.workspace.strings.file_show_full_link
import dev.loupe.workspace.strings.file_unchanged_below
import dev.loupe.workspace.strings.hunk_show_hidden
import dev.loupe.workspace.uimodel.CodeTokenKind
import dev.loupe.workspace.uimodel.CodeTokenModel
import dev.loupe.workspace.uimodel.CommentComposerModel
import dev.loupe.workspace.uimodel.CommentStatusModel
import dev.loupe.workspace.uimodel.DiffFileModel
import dev.loupe.workspace.uimodel.DiffLineKind
import dev.loupe.workspace.uimodel.DiffRowModel
import dev.loupe.workspace.uimodel.ReviewCommentModel
import dev.loupe.workspace.uimodel.WorkspaceEvent
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Plus, old number, new number and sign columns: 32 + 44 + 44 + 24. */
private val GutterWidth = 144.dp
private val CardMaxWidth = 880.dp

/** Root-space vertical extent of each line row, so a drag from + can find the line under it. */
private typealias LineBounds = MutableMap<Int, ClosedFloatingPointRange<Float>>

/**
 * Rows of one file. Code scrolls sideways as one block; comment cards and the composer stay
 * pinned to the left edge while it does, like `position: sticky` in the design.
 */
@Composable
internal fun DiffFileBody(
  file: DiffFileModel,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val radius = LemonadeTheme.radius.radius500
  val shape = RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
  val scroll = rememberScrollState()
  val lineBounds = remember(file.id) { mutableStateMapOf<Int, ClosedFloatingPointRange<Float>>() }
  val style = LoupeTheme.typography.code

  BoxWithConstraints(
    modifier = modifier
      .fillMaxWidth()
      .clip(shape)
      .background(LemonadeTheme.colors.background.bgSubtle)
      .padding(bottom = LemonadeTheme.spaces.spacing200),
  ) {
    val viewport = maxWidth
    val pinnedWidth = min(viewport, CardMaxWidth)
    Column(
      modifier = Modifier
        .horizontalScroll(scroll)
        .widthIn(min = viewport)
        .width(IntrinsicSize.Max),
    ) {
      file.rows.forEach { row ->
        key(row.key) {
          when (row) {
            is DiffRowModel.Hunk -> HunkRow(
              hunk = row,
              style = style,
              onExpand = { onEvent(WorkspaceEvent.ShowFullFile(file.id)) },
            )
            is DiffRowModel.Line -> {
              DiffLineRow(
                fileId = file.id,
                fileName = file.fileName,
                line = row,
                style = style,
                lineBounds = lineBounds,
                onEvent = onEvent,
              )
              row.comments.forEach { comment ->
                PinnedSlot(scroll = scroll, width = pinnedWidth) {
                  ReviewCommentCard(comment = comment, onEvent = onEvent)
                }
              }
              row.composer?.let { composer ->
                PinnedSlot(scroll = scroll, width = pinnedWidth) {
                  CommentComposerCard(composer = composer, onEvent = onEvent)
                }
              }
            }
            is DiffRowModel.FileTail -> FileTailRow(
              tail = row,
              scroll = scroll,
              width = pinnedWidth,
              onExpand = { onEvent(WorkspaceEvent.ShowFullFile(file.id)) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun PinnedSlot(
  scroll: ScrollState,
  width: Dp,
  content: @Composable () -> Unit,
) {
  val spaces = LemonadeTheme.spaces
  Box(
    modifier = Modifier
      .width(width)
      .offset { IntOffset(scroll.value, 0) }
      .padding(start = GutterWidth, end = spaces.spacing400, top = spaces.spacing200, bottom = spaces.spacing200),
  ) {
    content()
  }
}

@Composable
private fun HunkRow(
  hunk: DiffRowModel.Hunk,
  style: TextStyle,
  onExpand: () -> Unit,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
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
      modifier = Modifier.padding(end = LemonadeTheme.spaces.spacing600),
    )
  }
}

@Composable
private fun FileTailRow(
  tail: DiffRowModel.FileTail,
  scroll: ScrollState,
  width: Dp,
  onExpand: () -> Unit,
) {
  val secondary = LemonadeTheme.colors.content.contentSecondary
  Box(
    contentAlignment = Alignment.CenterStart,
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 36.dp)
      .background(LemonadeTheme.colors.background.bgElevated),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.width(width).offset { IntOffset(scroll.value, 0) },
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
      .fillMaxWidth()
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
        // Unclipped, so lines scrolled out of view still map to where they really are.
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
      text = rememberCodeLine(line.tokens, LoupeTheme.code),
      textStyle = style,
      maxLines = 1,
      modifier = Modifier.padding(end = LemonadeTheme.spaces.spacing600),
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
private fun rememberCodeLine(tokens: List<CodeTokenModel>, code: LoupeCodeColors): AnnotatedString = remember(tokens, code) {
  buildAnnotatedString {
    tokens.forEach { token ->
      val span = SpanStyle(
        color = token.kind.color(code),
        fontStyle = if (token.kind == CodeTokenKind.Comment) FontStyle.Italic else FontStyle.Normal,
        fontWeight = if (token.kind == CodeTokenKind.Heading) FontWeight.SemiBold else FontWeight.Normal,
      )
      withStyle(span) { append(token.text) }
    }
  }
}

private fun CodeTokenKind.color(code: LoupeCodeColors): Color = when (this) {
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
  val bringIntoView = remember { BringIntoViewRequester() }
  LaunchedEffect(composer.title) {
    withFrameNanos { }
    bringIntoView.bringIntoView()
  }
  LemonadeUi.Card(
    contentPadding = LemonadeCardPadding.Medium,
    background = LemonadeCardBackground.Default,
    modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing300)) {
      LemonadeUi.TextArea(
        input = composer.draft,
        onInputChanged = { onEvent(WorkspaceEvent.UpdateCommentDraft(it)) },
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
