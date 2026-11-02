package dev.lemonadecode.workspace.ui.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import com.teya.lemonade.HorizontalDivider
import com.teya.lemonade.Icon
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.Text
import com.teya.lemonade.core.LemonadeAssetSize
import com.teya.lemonade.core.LemonadeIcons
import com.teya.lemonade.textStyle
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.preview_markdown_alert_caution
import dev.lemonadecode.workspace.strings.preview_markdown_alert_important
import dev.lemonadecode.workspace.strings.preview_markdown_alert_note
import dev.lemonadecode.workspace.strings.preview_markdown_alert_tip
import dev.lemonadecode.workspace.strings.preview_markdown_alert_warning
import dev.lemonadecode.workspace.strings.preview_markdown_details
import dev.lemonadecode.workspace.strings.preview_markdown_image
import dev.lemonadecode.workspace.strings.preview_markdown_truncated
import org.jetbrains.compose.resources.stringResource

/** Top-level blocks drawn at most; the preview isn't lazy, so a huge file stops here. */
private const val MaxBlocks = 1_000
private const val MaxCodeLines = 1_000
private const val MaxTableRows = 200

/** Pictures in a line of text are drawn no wider than this, so one big picture can't swamp the line. */
private val MaxInlinePictureWidth = 480.dp

/** A Markdown file drawn formatted, the way GitHub shows a README. */
@Composable
internal fun MarkdownPreview(
  bytes: ByteArray,
  modifier: Modifier = Modifier,
  images: MarkdownImageSource = MarkdownImageSource.None,
) {
  ParsedTextPreview(bytes = bytes, parse = MarkdownParser::parse, modifier = modifier) { blocks ->
    MarkdownDocument(blocks, images = images)
  }
}

@Composable
internal fun MarkdownDocument(
  blocks: List<MarkdownBlock>,
  modifier: Modifier = Modifier,
  images: MarkdownImageSource = MarkdownImageSource.None,
) {
  val style = rememberMarkdownStyle()
  CompositionLocalProvider(LocalMarkdownImages provides images) {
    SelectionContainer(modifier = modifier.fillMaxWidth()) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Blocks(blocks.take(MaxBlocks), style, spacing = BlockSpacing.Loose, listDepth = 0, align = null)
        if (blocks.size > MaxBlocks) {
          LemonadeUi.Text(
            text = stringResource(Res.string.preview_markdown_truncated),
            textStyle = LemonadeTheme.typography.bodyXSmallRegular,
            color = LemonadeTheme.colors.content.contentSecondary,
            modifier = Modifier.padding(top = LemonadeTheme.spaces.spacing400),
          )
        }
      }
    }
  }
}

/** Paragraphs are spaced apart, except the ones in a tight list's items. */
private enum class BlockSpacing { Loose, Tight }

/** Colours and type, read from the theme once per document. */
private class MarkdownStyle(
  val body: TextStyle,
  val secondary: Color,
  val headings: List<TextStyle>,
  val code: TextStyle,
  val codeBackground: Color,
  val line: Color,
  val link: Color,
  /** "Image: %s" with the alt text in place of the marker. */
  val imageLabel: (String) -> String,
)

private const val ImageMarker = "\u0000"

@Composable
private fun rememberMarkdownStyle(): MarkdownStyle {
  val colors = LemonadeTheme.colors
  val typography = LemonadeTheme.typography
  val primary = colors.content.contentPrimary
  val body = typography.bodySmallRegular.textStyle.copy(color = primary)
  val headings = listOf(
    typography.headingSmall.textStyle.copy(color = primary),
    typography.headingXSmall.textStyle.copy(color = primary),
    typography.headingXXSmall.textStyle.copy(color = primary),
    typography.bodyMediumSemiBold.textStyle.copy(color = primary),
    typography.bodySmallSemiBold.textStyle.copy(color = primary),
    typography.bodySmallSemiBold.textStyle.copy(color = colors.content.contentSecondary),
  )
  val code = LemonadeCodeTheme.typography.codeSmall.copy(color = primary)
  val imageTemplate = stringResource(Res.string.preview_markdown_image, ImageMarker)
  val link = LemonadeCodeTheme.code.path
  return remember(body, headings, code, imageTemplate, link, colors) {
    MarkdownStyle(
      body = body,
      secondary = colors.content.contentSecondary,
      headings = headings,
      code = code,
      codeBackground = colors.background.bgElevated,
      line = colors.border.borderNeutralLow,
      link = link,
      imageLabel = { alt -> imageTemplate.replace(ImageMarker, alt) },
    )
  }
}

private fun MarkdownAlignment?.textAlign(): TextAlign = when (this) {
  MarkdownAlignment.Center -> TextAlign.Center
  MarkdownAlignment.End -> TextAlign.End
  MarkdownAlignment.Start, null -> TextAlign.Start
}

private fun MarkdownAlignment?.horizontal(): Alignment.Horizontal = when (this) {
  MarkdownAlignment.Center -> Alignment.CenterHorizontally
  MarkdownAlignment.End -> Alignment.End
  MarkdownAlignment.Start, null -> Alignment.Start
}

/** [align] is the alignment the blocks inherit from the HTML around them, like `<div align="center">`. */
@Composable
private fun Blocks(
  blocks: List<MarkdownBlock>,
  style: MarkdownStyle,
  spacing: BlockSpacing,
  listDepth: Int,
  align: MarkdownAlignment?,
  textColor: Color? = null,
) {
  val gap = if (spacing == BlockSpacing.Loose) LemonadeTheme.spaces.spacing300 else LemonadeTheme.spaces.spacing100
  Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = align.horizontal(), modifier = Modifier.fillMaxWidth()) {
    blocks.forEach { block -> Block(block, style, listDepth, align, textColor) }
  }
}

@Composable
private fun Block(
  block: MarkdownBlock,
  style: MarkdownStyle,
  listDepth: Int,
  align: MarkdownAlignment?,
  textColor: Color?,
) {
  when (block) {
    is MarkdownBlock.Heading -> Heading(block, style, block.align ?: align, textColor)
    is MarkdownBlock.Paragraph -> Inlines(block.content, style, style.body.withColor(textColor), block.align ?: align)
    is MarkdownBlock.Code -> CodeBlock(block, style)
    is MarkdownBlock.Quote -> Quote(block, style, listDepth, align)
    is MarkdownBlock.Alert -> Alert(block, style, listDepth, align)
    is MarkdownBlock.ListBlock -> ListBlock(block, style, listDepth, textColor)
    is MarkdownBlock.Table -> Table(block, style)
    is MarkdownBlock.Section -> Blocks(block.blocks, style, BlockSpacing.Loose, listDepth, block.align ?: align, textColor)
    is MarkdownBlock.HtmlTable -> HtmlTable(block, style, listDepth, textColor)
    is MarkdownBlock.Details -> Details(block, style, listDepth, align, textColor)
    is MarkdownBlock.Spacer -> Spacer(modifier = Modifier.height(lineHeight(style.body) * block.lines))
    MarkdownBlock.ThematicBreak -> LemonadeUi.HorizontalDivider(modifier = Modifier.padding(vertical = LemonadeTheme.spaces.spacing200))
  }
}

@Composable
private fun lineHeight(textStyle: TextStyle): Dp = with(LocalDensity.current) {
  if (textStyle.lineHeight.isSpecified) textStyle.lineHeight.toDp() else (textStyle.fontSize * 1.4f).toDp()
}

@Composable
private fun Heading(
  block: MarkdownBlock.Heading,
  style: MarkdownStyle,
  align: MarkdownAlignment?,
  textColor: Color?,
) {
  val level = block.level.coerceIn(1, style.headings.size)
  Column(modifier = Modifier.fillMaxWidth().padding(top = if (level <= 2) LemonadeTheme.spaces.spacing200 else 0.dp)) {
    Inlines(block.content, style, style.headings[level - 1].withColor(textColor), align)
    // GitHub rules off the two biggest levels.
    if (level <= 2) {
      LemonadeUi.HorizontalDivider(modifier = Modifier.padding(top = LemonadeTheme.spaces.spacing200))
    }
  }
}

/** A picture in a line of pictures; [link] is where clicking it goes, when it's wrapped in a link. */
private class LinedPicture(val image: MarkdownInline.Image, val link: String?)

/** A paragraph cut at its line breaks, so lines of pictures can lay out as pictures. */
private sealed interface InlineRun {
  class Text(val inlines: List<MarkdownInline>) : InlineRun

  /** A line with nothing but pictures, like a row of badges. */
  class Pictures(val pictures: List<LinedPicture>) : InlineRun

  /** Text with pictures in it, drawn in the line like characters. */
  class Mixed(val inlines: List<MarkdownInline>) : InlineRun
}

private fun MarkdownInline.hasPicture(): Boolean = this is MarkdownInline.Image || (this is MarkdownInline.Link && children.any { it is MarkdownInline.Image })

private fun MarkdownInline.isBlank(): Boolean = this == MarkdownInline.SoftBreak || (this is MarkdownInline.Text && text.isBlank())

/** The pictures [inline] stands for, or null when it holds anything else. */
private fun MarkdownInline.pictures(): List<LinedPicture>? = when (this) {
  is MarkdownInline.Image -> listOf(LinedPicture(this, link = null))
  is MarkdownInline.Link -> children.filterNot { it.isBlank() }.let { inner ->
    if (inner.isNotEmpty() && inner.all { it is MarkdownInline.Image }) inner.map { LinedPicture(it as MarkdownInline.Image, destination) } else null
  }
  else -> null
}

/** Null when there's no picture to lay out, so plain text takes the quick way. */
private fun inlineRuns(inlines: List<MarkdownInline>): List<InlineRun>? {
  if (inlines.none { it.hasPicture() }) return null
  val lines = mutableListOf(mutableListOf<MarkdownInline>())
  inlines.forEach { if (it == MarkdownInline.HardBreak) lines += mutableListOf<MarkdownInline>() else lines.last() += it }
  val runs = mutableListOf<InlineRun>()
  val text = mutableListOf<List<MarkdownInline>>()
  fun flushText() {
    if (text.isEmpty()) return
    runs += InlineRun.Text(text.flatMapIndexed { index, line -> if (index == 0) line else listOf(MarkdownInline.HardBreak) + line })
    text.clear()
  }
  lines.forEach { line ->
    val content = line.filterNot { it.isBlank() }
    val pictures = content.map { it.pictures() }
    when {
      content.isNotEmpty() && pictures.all { it != null } -> {
        flushText()
        runs += InlineRun.Pictures(pictures.flatMap { it.orEmpty() })
      }
      line.any { it.hasPicture() } -> {
        flushText()
        runs += InlineRun.Mixed(line)
      }
      else -> text += line
    }
  }
  flushText()
  return runs
}

@Composable
private fun Inlines(
  inlines: List<MarkdownInline>,
  style: MarkdownStyle,
  textStyle: TextStyle,
  align: MarkdownAlignment?,
) {
  val runs = remember(inlines) { inlineRuns(inlines) }
  val aligned = textStyle.copy(textAlign = align.textAlign())
  if (runs == null) {
    PlainText(inlines, style, aligned)
    return
  }
  val gap = LemonadeTheme.spaces.spacing100
  Column(horizontalAlignment = align.horizontal(), verticalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxWidth()) {
    runs.forEach { run ->
      when (run) {
        is InlineRun.Text -> PlainText(run.inlines, style, aligned)
        is InlineRun.Mixed -> MixedText(run.inlines, style, aligned)
        is InlineRun.Pictures -> FlowRow(
          horizontalArrangement = Arrangement.spacedBy(gap, align.horizontal()),
          verticalArrangement = Arrangement.spacedBy(gap),
          modifier = Modifier.fillMaxWidth(),
        ) {
          run.pictures.forEach { MarkdownPicture(it.image, it.link, style) }
        }
      }
    }
  }
}

@Composable
private fun PlainText(
  inlines: List<MarkdownInline>,
  style: MarkdownStyle,
  textStyle: TextStyle,
) {
  val text = remember(inlines, style) { annotated(inlines, style) }
  LemonadeUi.Text(text = text, textStyle = textStyle, modifier = Modifier.fillMaxWidth())
}

/** Text with pictures in its lines, each one sized like the page would: by its attributes, else its own size. */
@Composable
private fun MixedText(
  inlines: List<MarkdownInline>,
  style: MarkdownStyle,
  textStyle: TextStyle,
) {
  val (text, pictures) = remember(inlines, style) {
    val found = mutableListOf<MarkdownInline.Image>()
    annotated(inlines, style, found) to found.toList()
  }
  val density = LocalDensity.current
  val line = lineHeight(textStyle)
  BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val widest = if (constraints.hasBoundedWidth) minOf(maxWidth, MaxInlinePictureWidth) else MaxInlinePictureWidth
    val content = pictures.mapIndexed { index, image ->
      val state by rememberPicture(image.source)
      val picture = (state as? PictureState.Ready)?.picture
      val (width, height) = inlineSize(image, picture, line, widest)
      // Small pictures sit in the middle of the line like an icon; big ones stand on the text's baseline.
      val verticalAlign = if (height > line * 2) PlaceholderVerticalAlign.AboveBaseline else PlaceholderVerticalAlign.TextCenter
      "$PicturePrefix$index" to InlineTextContent(
        with(density) { Placeholder(width.toSp(), height.toSp(), verticalAlign) },
      ) {
        PictureContent(image, state, style, Modifier.fillMaxSize())
      }
    }.toMap()
    BasicText(text = text, style = textStyle, inlineContent = content, modifier = Modifier.fillMaxWidth())
  }
}

private const val PicturePrefix = "picture:"

/**
 * A picture's size in a line of text: its attributes, else its own size, else a square the height
 * of the line; never wider than [widest].
 */
private fun inlineSize(image: MarkdownInline.Image, picture: Picture?, line: Dp, widest: Dp): Pair<Dp, Dp> {
  val ratio = picture?.let { it.width / it.height.coerceAtLeast(1f) } ?: 1f
  val width = (image.width as? MarkdownLength.Pixels)?.value
  val height = (image.height as? MarkdownLength.Pixels)?.value
  val w = width ?: height?.let { it * ratio } ?: picture?.width ?: line.value
  val h = height ?: width?.let { it / ratio } ?: picture?.height ?: line.value
  val scale = minOf(1f, widest.value / w.coerceAtLeast(1f))
  return (w * scale).dp to (h * scale).dp
}

/** A picture as a block of its own: its attributes' size, else its own size, never wider than the page. */
@Composable
private fun MarkdownPicture(
  image: MarkdownInline.Image,
  link: String?,
  style: MarkdownStyle,
) {
  val state by rememberPicture(image.source)
  val uriHandler = LocalUriHandler.current
  val clickable = if (link != null && opensInBrowser(link)) {
    Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { uriHandler.openUri(link.trim()) }
  } else {
    Modifier
  }
  val picture = (state as? PictureState.Ready)?.picture
  if (picture == null) {
    PictureContent(image, state, style, clickable)
    return
  }
  val ratio = (image.width as? MarkdownLength.Pixels)?.value?.let { w -> (image.height as? MarkdownLength.Pixels)?.value?.let { h -> w / h } }
    ?: (picture.width / picture.height.coerceAtLeast(1f))
  val size = when (val width = image.width) {
    is MarkdownLength.Percent -> Modifier.fillMaxWidth(width.value / 100f)
    is MarkdownLength.Pixels -> Modifier.widthIn(max = width.value.dp).fillMaxWidth()
    null -> {
      val natural = (image.height as? MarkdownLength.Pixels)?.value?.let { it * ratio } ?: picture.width
      Modifier.widthIn(max = natural.dp).fillMaxWidth()
    }
  }
  PictureContent(image, state, style, size.aspectRatio(ratio.coerceAtLeast(0.01f)).then(clickable))
}

/** The picture itself, or while it loads or if it can't, its alt text. */
@Composable
private fun PictureContent(
  image: MarkdownInline.Image,
  state: PictureState,
  style: MarkdownStyle,
  modifier: Modifier,
) {
  val description = image.alt.ifBlank { image.source.substringAfterLast('/') }
  when (state) {
    is PictureState.Ready -> when (val picture = state.picture) {
      is Picture.Raster -> Image(
        bitmap = picture.image,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.Medium,
        modifier = modifier,
      )
      is Picture.Vector -> Image(painter = picture.painter, contentDescription = description, contentScale = ContentScale.Fit, modifier = modifier)
    }
    PictureState.Loading -> Box(
      modifier = modifier
        .heightIn(min = 20.dp)
        .clip(LemonadeTheme.shapes.radius100)
        .background(style.codeBackground)
        .semantics { contentDescription = description },
    )
    PictureState.Failed -> LemonadeUi.Text(
      text = style.imageLabel(description),
      textStyle = style.body.copy(color = style.secondary, fontStyle = FontStyle.Italic),
      modifier = modifier,
    )
  }
}

@Composable
private fun CodeBlock(
  block: MarkdownBlock.Code,
  style: MarkdownStyle,
) {
  val text = remember(block.text) {
    val lines = block.text.trimEnd('\n').lines()
    if (lines.size > MaxCodeLines) (lines.take(MaxCodeLines) + "⋯").joinToString("\n") else lines.joinToString("\n")
  }
  val shape = LemonadeTheme.shapes.radius200
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(style.codeBackground)
      .border(LemonadeTheme.borderWidths.base.border25, style.line, shape),
  ) {
    // Code keeps its lines: it scrolls sideways instead of wrapping.
    Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(LemonadeTheme.spaces.spacing300)) {
      LemonadeUi.Text(text = text, textStyle = style.code)
    }
    if (block.language.isNotEmpty()) {
      LemonadeUi.Text(
        text = block.language,
        textStyle = LemonadeTheme.typography.bodyXSmallRegular.textStyle.copy(color = style.secondary),
        modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = LemonadeTheme.spaces.spacing200, vertical = LemonadeTheme.spaces.spacing100),
      )
    }
  }
}

/** A bar down the left edge, drawn behind the content so it's as tall as whatever is inside. */
@Composable
private fun Modifier.edgeBar(color: Color): Modifier {
  val radius = with(LocalDensity.current) { 2.dp.toPx() }
  return drawBehind {
    drawRoundRect(color, size = Size(3.dp.toPx(), size.height), cornerRadius = CornerRadius(radius, radius))
  }.padding(start = 3.dp + LemonadeTheme.spaces.spacing300)
}

@Composable
private fun Quote(
  block: MarkdownBlock.Quote,
  style: MarkdownStyle,
  listDepth: Int,
  align: MarkdownAlignment?,
) {
  Box(modifier = Modifier.fillMaxWidth().edgeBar(LemonadeTheme.colors.border.borderNeutralHigh)) {
    Blocks(block.blocks, style, BlockSpacing.Loose, listDepth, align, textColor = style.secondary)
  }
}

/** A GitHub alert: a coloured edge and a title, like a quote that means something. */
@Composable
private fun Alert(
  block: MarkdownBlock.Alert,
  style: MarkdownStyle,
  listDepth: Int,
  align: MarkdownAlignment?,
) {
  val colors = LemonadeTheme.colors
  val (color, icon, title) = when (block.kind) {
    MarkdownAlertKind.Note -> Triple(colors.content.contentInfo, LemonadeIcons.CircleInfo, Res.string.preview_markdown_alert_note)
    MarkdownAlertKind.Tip -> Triple(colors.content.contentPositive, LemonadeIcons.Lightbulb, Res.string.preview_markdown_alert_tip)
    MarkdownAlertKind.Important -> Triple(colors.content.contentFeatured, LemonadeIcons.Megaphone, Res.string.preview_markdown_alert_important)
    MarkdownAlertKind.Warning -> Triple(colors.content.contentCaution, LemonadeIcons.TriangleAlert, Res.string.preview_markdown_alert_warning)
    MarkdownAlertKind.Caution -> Triple(colors.content.contentCritical, LemonadeIcons.CircleAlert, Res.string.preview_markdown_alert_caution)
  }
  Column(
    verticalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing200),
    modifier = Modifier.fillMaxWidth().edgeBar(color),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing200), verticalAlignment = Alignment.CenterVertically) {
      LemonadeUi.Icon(icon = icon, contentDescription = null, size = LemonadeAssetSize.Small, tint = color)
      LemonadeUi.Text(text = stringResource(title), textStyle = style.body.copy(color = color, fontWeight = FontWeight.SemiBold))
    }
    Blocks(block.blocks, style, BlockSpacing.Loose, listDepth, align)
  }
}

@Composable
private fun ListBlock(
  block: MarkdownBlock.ListBlock,
  style: MarkdownStyle,
  listDepth: Int,
  textColor: Color?,
) {
  val spaces = LemonadeTheme.spaces
  val spacing = if (block.loose) BlockSpacing.Loose else BlockSpacing.Tight
  val markerStyle = style.body.withColor(textColor ?: style.secondary).copy(fontFeatureSettings = "tnum", textAlign = TextAlign.End)
  Column(verticalArrangement = Arrangement.spacedBy(if (block.loose) spaces.spacing300 else spaces.spacing100), modifier = Modifier.fillMaxWidth()) {
    block.items.forEachIndexed { index, item ->
      Row(horizontalArrangement = Arrangement.spacedBy(spaces.spacing200)) {
        Box(contentAlignment = Alignment.TopEnd, modifier = Modifier.widthIn(min = 20.dp)) {
          when {
            item.checked != null -> TaskBox(checked = item.checked, style = style)
            block.ordered -> LemonadeUi.Text(text = "${block.start + index}.", textStyle = markerStyle)
            else -> LemonadeUi.Text(text = Bullets[listDepth % Bullets.size], textStyle = markerStyle)
          }
        }
        Box(modifier = Modifier.weight(1f)) {
          Blocks(item.blocks, style, spacing, listDepth + 1, align = null, textColor = textColor)
        }
      }
    }
  }
}

private val Bullets = listOf("•", "◦", "▪")

/** A task list's box: drawn, not clickable, since the preview can't change the file. */
@Composable
private fun TaskBox(
  checked: Boolean,
  style: MarkdownStyle,
) {
  val colors = LemonadeTheme.colors
  val shape = LemonadeTheme.shapes.radius100
  // Sits on the first line of the item's text.
  Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = lineHeight(style.body))) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(14.dp)
        .clip(shape)
        .background(if (checked) colors.background.bgBrand else Color.Transparent)
        .border(LemonadeTheme.borderWidths.base.border25, if (checked) colors.background.bgBrand else colors.border.borderNeutralHigh, shape),
    ) {
      if (checked) {
        LemonadeUi.Icon(icon = LemonadeIcons.Check, contentDescription = null, size = LemonadeAssetSize.XSmall, tint = colors.content.contentOnBrandHigh)
      }
    }
  }
}

@Composable
private fun Table(
  block: MarkdownBlock.Table,
  style: MarkdownStyle,
) {
  val columns = block.header.size
  val rows = block.rows.take(MaxTableRows)
  val headerStyle = style.body.copy(fontWeight = FontWeight.SemiBold)
  Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
    PreviewTableGrid(
      rows = rows.size + 1,
      columns = columns,
      cellPadding = PaddingValues(horizontal = LemonadeTheme.spaces.spacing300, vertical = LemonadeTheme.spaces.spacing100),
      alignment = { _, column ->
        when (block.alignments.getOrNull(column)) {
          MarkdownAlignment.Center -> Alignment.CenterHorizontally
          MarkdownAlignment.End -> Alignment.End
          MarkdownAlignment.Start, null -> Alignment.Start
        }
      },
    ) { row, column ->
      val align = block.alignments.getOrNull(column)
      if (row == 0) {
        TableCellText(block.header[column], style, headerStyle, align)
      } else {
        TableCellText(rows[row - 1].getOrElse(column) { emptyList() }, style, style.body, align)
      }
    }
  }
}

/** A Markdown table cell: as wide as its text, so the column fits it. */
@Composable
private fun TableCellText(
  inlines: List<MarkdownInline>,
  style: MarkdownStyle,
  textStyle: TextStyle,
  align: MarkdownAlignment?,
) {
  if (inlines.none { it.hasPicture() }) {
    val text = remember(inlines, style) { annotated(inlines, style) }
    LemonadeUi.Text(text = text, textStyle = textStyle.copy(textAlign = align.textAlign()))
  } else {
    Box(modifier = Modifier.widthIn(max = 320.dp)) { Inlines(inlines, style, textStyle, align) }
  }
}

/** An HTML table fills the width, its columns sharing it by their `width` attributes. */
@Composable
private fun HtmlTable(
  block: MarkdownBlock.HtmlTable,
  style: MarkdownStyle,
  listDepth: Int,
  textColor: Color?,
) {
  val rows = block.rows.take(MaxTableRows)
  val columns = rows.maxOf { it.size }
  val weights = remember(block) {
    val percents = (0 until columns).map { column -> rows.firstNotNullOfOrNull { (it.getOrNull(column)?.width as? MarkdownLength.Percent)?.value } }
    val missing = percents.count { it == null }
    val share = if (missing == 0) 0f else maxOf(100f - percents.sumOf { (it ?: 0f).toDouble() }.toFloat(), missing * 10f) / missing
    percents.map { it ?: share }
  }
  val headerRows = if (rows.first().all { it.header }) 1 else 0
  val spaces = LemonadeTheme.spaces
  PreviewTableGrid(
    rows = rows.size,
    columns = columns,
    cellPadding = PaddingValues(horizontal = spaces.spacing300, vertical = spaces.spacing200),
    columnWeights = weights,
    headerRows = headerRows,
    verticalAlignment = { row, column ->
      when (rows[row].getOrNull(column)?.verticalAlign) {
        MarkdownVerticalAlignment.Top -> Alignment.Top
        MarkdownVerticalAlignment.Bottom -> Alignment.Bottom
        MarkdownVerticalAlignment.Middle, null -> Alignment.CenterVertically
      }
    },
    modifier = Modifier.fillMaxWidth(),
  ) { row, column ->
    val cell = rows[row].getOrNull(column)
    if (cell != null) {
      val align = cell.align ?: if (cell.header) MarkdownAlignment.Center else null
      Blocks(cell.blocks, style, BlockSpacing.Loose, listDepth, align, textColor)
    }
  }
}

/** `<details>`: its summary, which opens and folds the rest. */
@Composable
private fun Details(
  block: MarkdownBlock.Details,
  style: MarkdownStyle,
  listDepth: Int,
  align: MarkdownAlignment?,
  textColor: Color?,
) {
  var open by remember(block) { mutableStateOf(block.open) }
  val spaces = LemonadeTheme.spaces
  Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing200), modifier = Modifier.fillMaxWidth()) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(spaces.spacing100),
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { open = !open },
    ) {
      LemonadeUi.Icon(
        icon = if (open) LemonadeIcons.ChevronDownSmall else LemonadeIcons.ChevronRightSmall,
        contentDescription = null,
        size = LemonadeAssetSize.Small,
        tint = style.secondary,
      )
      val summary = block.summary.ifEmpty { listOf(MarkdownInline.Text(stringResource(Res.string.preview_markdown_details))) }
      Box { PlainText(summary, style, style.body.withColor(textColor)) }
    }
    if (open) {
      Box(modifier = Modifier.padding(start = spaces.spacing500)) {
        Blocks(block.blocks, style, BlockSpacing.Loose, listDepth, align, textColor)
      }
    }
  }
}

private fun TextStyle.withColor(color: Color?): TextStyle = if (color == null) this else copy(color = color)

/**
 * Formatting as spans. Only web and mail links open; the rest are coloured but stay text.
 * Pictures become their alt text, or, when [pictures] is given, placeholders for the pictures,
 * which are added to it in order.
 */
private fun annotated(
  inlines: List<MarkdownInline>,
  style: MarkdownStyle,
  pictures: MutableList<MarkdownInline.Image>? = null,
): AnnotatedString = buildAnnotatedString {
  fun add(items: List<MarkdownInline>) {
    items.forEach { inline ->
      when (inline) {
        is MarkdownInline.Text -> append(inline.text)
        is MarkdownInline.Code -> withStyle(
          SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.9.em, background = style.codeBackground),
        ) { append(inline.text) }
        is MarkdownInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { add(inline.children) }
        is MarkdownInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { add(inline.children) }
        is MarkdownInline.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { add(inline.children) }
        is MarkdownInline.Underline -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { add(inline.children) }
        is MarkdownInline.Small -> withStyle(
          SpanStyle(
            fontSize = 0.8.em,
            baselineShift = when (inline.shift) {
              MarkdownTextShift.None -> null
              MarkdownTextShift.Down -> BaselineShift(-0.15f)
              MarkdownTextShift.Up -> BaselineShift(0.4f)
            },
          ),
        ) { add(inline.children) }
        is MarkdownInline.Link -> {
          val linkStyle = SpanStyle(color = style.link)
          if (opensInBrowser(inline.destination)) {
            withLink(
              LinkAnnotation.Url(
                url = inline.destination.trim(),
                styles = TextLinkStyles(style = linkStyle, hoveredStyle = linkStyle.copy(textDecoration = TextDecoration.Underline)),
              ),
            ) { add(inline.children) }
          } else {
            withStyle(linkStyle) { add(inline.children) }
          }
        }
        is MarkdownInline.Image -> if (pictures != null) {
          appendInlineContent("$PicturePrefix${pictures.size}", inline.alt)
          pictures += inline
        } else {
          withStyle(SpanStyle(color = style.secondary, fontStyle = FontStyle.Italic)) {
            append(style.imageLabel(inline.alt.ifBlank { inline.source.substringAfterLast('/') }))
          }
        }
        MarkdownInline.SoftBreak -> append(' ')
        MarkdownInline.HardBreak -> append('\n')
      }
    }
  }
  add(inlines)
}

internal fun opensInBrowser(destination: String): Boolean {
  val lower = destination.trim().lowercase()
  return lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:")
}
