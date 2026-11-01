package dev.lemonadecode.workspace.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
import dev.lemonadecode.workspace.strings.preview_markdown_image
import dev.lemonadecode.workspace.strings.preview_markdown_truncated
import org.jetbrains.compose.resources.stringResource

/** Top-level blocks drawn at most; the preview isn't lazy, so a huge file stops here. */
private const val MaxBlocks = 1_000
private const val MaxCodeLines = 1_000
private const val MaxTableRows = 200

/** A Markdown file drawn formatted, the way GitHub shows a README. */
@Composable
internal fun MarkdownPreview(
  bytes: ByteArray,
  modifier: Modifier = Modifier,
) {
  ParsedTextPreview(bytes = bytes, parse = MarkdownParser::parse, modifier = modifier) { blocks ->
    MarkdownDocument(blocks)
  }
}

@Composable
internal fun MarkdownDocument(
  blocks: List<MarkdownBlock>,
  modifier: Modifier = Modifier,
) {
  val style = rememberMarkdownStyle()
  SelectionContainer(modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Blocks(blocks.take(MaxBlocks), style, spacing = BlockSpacing.Loose, listDepth = 0)
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

@Composable
private fun Blocks(
  blocks: List<MarkdownBlock>,
  style: MarkdownStyle,
  spacing: BlockSpacing,
  listDepth: Int,
  textColor: Color? = null,
) {
  val gap = if (spacing == BlockSpacing.Loose) LemonadeTheme.spaces.spacing300 else LemonadeTheme.spaces.spacing100
  Column(verticalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxWidth()) {
    blocks.forEach { block -> Block(block, style, listDepth, textColor) }
  }
}

@Composable
private fun Block(
  block: MarkdownBlock,
  style: MarkdownStyle,
  listDepth: Int,
  textColor: Color?,
) {
  when (block) {
    is MarkdownBlock.Heading -> Heading(block, style, textColor)
    is MarkdownBlock.Paragraph -> Inlines(block.content, style, style.body.withColor(textColor))
    is MarkdownBlock.Code -> CodeBlock(block, style)
    is MarkdownBlock.Quote -> Quote(block, style, listDepth)
    is MarkdownBlock.ListBlock -> ListBlock(block, style, listDepth, textColor)
    is MarkdownBlock.Table -> Table(block, style)
    is MarkdownBlock.Html -> LemonadeUi.Text(
      text = block.text.trimEnd(),
      textStyle = style.code.copy(color = style.secondary),
    )
    MarkdownBlock.ThematicBreak -> LemonadeUi.HorizontalDivider(modifier = Modifier.padding(vertical = LemonadeTheme.spaces.spacing200))
  }
}

@Composable
private fun Heading(
  block: MarkdownBlock.Heading,
  style: MarkdownStyle,
  textColor: Color?,
) {
  val level = block.level.coerceIn(1, style.headings.size)
  Column(modifier = Modifier.padding(top = if (level <= 2) LemonadeTheme.spaces.spacing200 else 0.dp)) {
    Inlines(block.content, style, style.headings[level - 1].withColor(textColor))
    // GitHub rules off the two biggest levels.
    if (level <= 2) {
      LemonadeUi.HorizontalDivider(modifier = Modifier.padding(top = LemonadeTheme.spaces.spacing200))
    }
  }
}

@Composable
private fun Inlines(
  inlines: List<MarkdownInline>,
  style: MarkdownStyle,
  textStyle: TextStyle,
  modifier: Modifier = Modifier,
) {
  val text = remember(inlines, style) { annotated(inlines, style) }
  LemonadeUi.Text(text = text, textStyle = textStyle, modifier = modifier)
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

@Composable
private fun Quote(
  block: MarkdownBlock.Quote,
  style: MarkdownStyle,
  listDepth: Int,
) {
  Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
    Box(modifier = Modifier.width(3.dp).fillMaxHeight().clip(LemonadeTheme.shapes.radius100).background(LemonadeTheme.colors.border.borderNeutralHigh))
    Box(modifier = Modifier.padding(start = LemonadeTheme.spaces.spacing300)) {
      Blocks(block.blocks, style, BlockSpacing.Loose, listDepth, textColor = style.secondary)
    }
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
  Column(verticalArrangement = Arrangement.spacedBy(if (block.loose) spaces.spacing300 else spaces.spacing100)) {
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
          Blocks(item.blocks, style, spacing, listDepth + 1, textColor)
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
  val lineHeight = with(LocalDensity.current) { style.body.lineHeight.toDp() }
  Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = lineHeight)) {
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
  val header = remember(block.header, style) { block.header.map { annotated(it, style) } }
  val cells = remember(rows, style) { rows.map { row -> row.map { annotated(it, style) } } }
  val headerStyle = style.body.copy(fontWeight = FontWeight.SemiBold)
  Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
    PreviewTableGrid(
      rows = cells.size + 1,
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
      if (row == 0) {
        LemonadeUi.Text(text = header[column], textStyle = headerStyle)
      } else {
        LemonadeUi.Text(text = cells[row - 1].getOrElse(column) { AnnotatedString("") }, textStyle = style.body)
      }
    }
  }
}

private fun TextStyle.withColor(color: Color?): TextStyle = if (color == null) this else copy(color = color)

/** Formatting as spans. Only web and mail links open; the rest are coloured but stay text. */
private fun annotated(
  inlines: List<MarkdownInline>,
  style: MarkdownStyle,
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
        // Pictures aren't loaded from the repository; the alt text stands in for them.
        is MarkdownInline.Image -> withStyle(SpanStyle(color = style.secondary, fontStyle = FontStyle.Italic)) {
          append(style.imageLabel(inline.alt.ifBlank { inline.source.substringAfterLast('/') }))
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
