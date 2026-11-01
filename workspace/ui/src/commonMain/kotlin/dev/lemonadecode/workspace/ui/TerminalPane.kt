package dev.lemonadecode.workspace.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.Spinner
import com.teya.lemonade.SymbolContainer
import com.teya.lemonade.Text
import com.teya.lemonade.core.LemonadeAssetSize
import com.teya.lemonade.core.LemonadeIcons
import com.teya.lemonade.core.SymbolContainerSize
import com.teya.lemonade.core.SymbolContainerVoice
import dev.lemonadecode.platform.designsystem.Hairline
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.platform.designsystem.rememberCharWidth
import dev.lemonadecode.platform.localization.resolve
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.terminal_running
import dev.lemonadecode.workspace.uimodel.TerminalLineModel
import dev.lemonadecode.workspace.uimodel.TerminalTranscriptModel
import dev.lemonadecode.workspace.uimodel.ToolStatTone
import dev.lemonadecode.workspace.uimodel.WorkspaceContentModel
import org.jetbrains.compose.resources.stringResource

/**
 * A transcript drawn in the terminal's style: anchored to the bottom, newest line in view. Used
 * where there is no live terminal, like previews and the design showcase.
 */
@Composable
public fun TerminalTranscript(
  transcript: TerminalTranscriptModel,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  val lines = transcript.lines
  LaunchedEffect(lines.size) {
    if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
  }
  val style = LemonadeCodeTheme.typography.code
  val ch = rememberCharWidth(style)
  val spaces = LemonadeTheme.spaces
  SelectionContainer(modifier = modifier) {
    LazyColumn(
      state = listState,
      verticalArrangement = Arrangement.Bottom,
      contentPadding = PaddingValues(
        start = spaces.spacing600,
        end = spaces.spacing600,
        top = spaces.spacing400,
        bottom = spaces.spacing600,
      ),
    ) {
      itemsIndexed(lines) { _, line ->
        TerminalLine(line = line, style = style, ch = ch)
      }
    }
  }
}

@Composable
private fun TerminalLine(
  line: TerminalLineModel,
  style: TextStyle,
  ch: Dp,
) {
  val colors = LemonadeTheme.colors
  val code = LemonadeCodeTheme.code
  val spaces = LemonadeTheme.spaces
  when (line) {
    is TerminalLineModel.Rule -> Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spaces.spacing300),
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = spaces.spacing400, bottom = spaces.spacing100),
    ) {
      Hairline(width = 16.dp, height = 1.dp, modifier = Modifier.weight(1f))
      LemonadeUi.Text(
        text = line.text,
        textStyle = style.copy(color = colors.content.contentSecondary, textAlign = TextAlign.Center),
      )
      Hairline(width = 16.dp, height = 1.dp, modifier = Modifier.weight(1f))
    }

    is TerminalLineModel.WorkingDirectory -> FlowRow(horizontalArrangement = Arrangement.spacedBy(ch)) {
      Mono(text = line.path, style = style, color = code.path, maxLines = 1)
      Mono(text = line.branch, style = style, color = code.branch, maxLines = 1)
    }

    is TerminalLineModel.Command -> Row(horizontalArrangement = Arrangement.spacedBy(ch)) {
      Mono(text = "$", style = style, color = colors.content.contentTertiary)
      Mono(text = line.text, style = style, color = colors.content.contentPrimary)
    }

    is TerminalLineModel.Output -> Mono(text = line.text, style = style, color = colors.content.contentSecondary)

    is TerminalLineModel.UserMessage -> Row(
      modifier = Modifier
        .padding(top = spaces.spacing300)
        .fillMaxWidth()
        .background(colors.background.bgElevated, LemonadeTheme.shapes.radius300)
        .padding(horizontal = spaces.spacing300, vertical = spaces.spacing200),
    ) {
      Mono(
        text = "›",
        style = style,
        color = colors.content.contentBrand,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.width(ch * 2),
      )
      Column(
        verticalArrangement = Arrangement.spacedBy(spaces.spacing200),
        modifier = Modifier.weight(1f),
      ) {
        Mono(text = line.text, style = style, color = colors.content.contentPrimary)
        line.items.forEach { item ->
          Row {
            Mono(
              text = item.number,
              style = style,
              color = colors.content.contentSecondary,
              modifier = Modifier.width(ch * 3),
            )
            Column(modifier = Modifier.weight(1f)) {
              Mono(text = item.location, style = style, color = code.path)
              Mono(text = item.text, style = style, color = colors.content.contentPrimary)
            }
          }
        }
      }
    }

    is TerminalLineModel.AgentMessage -> Row(
      modifier = Modifier.padding(top = spaces.spacing300, bottom = spaces.spacing100),
    ) {
      Mono(text = "◆", style = style, color = colors.content.contentBrand, modifier = Modifier.width(ch * 2))
      Mono(
        text = line.text,
        style = style,
        color = colors.content.contentPrimary,
        modifier = Modifier.weight(1f),
      )
    }

    is TerminalLineModel.ToolCall -> FlowRow(
      itemVerticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(start = ch * 2),
    ) {
      Mono(
        text = line.verb,
        style = style,
        color = colors.content.contentSecondary,
        maxLines = 1,
        modifier = Modifier.width(ch * 8),
      )
      Mono(
        text = line.target,
        style = style,
        color = colors.content.contentPrimary,
        maxLines = 1,
        modifier = Modifier.widthIn(min = ch * 32),
      )
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ch),
      ) {
        line.stats.forEach { stat ->
          Mono(
            text = stat.text,
            style = style,
            maxLines = 1,
            color = when (stat.tone) {
              ToolStatTone.Positive -> colors.content.contentPositive
              ToolStatTone.Critical -> colors.content.contentCritical
              ToolStatTone.Neutral -> colors.content.contentSecondary
            },
          )
        }
        if (line.running) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spaces.spacing200),
          ) {
            LemonadeUi.Spinner(size = LemonadeAssetSize.XSmall, tint = colors.content.contentBrand)
            Mono(text = stringResource(Res.string.terminal_running), style = style, color = colors.content.contentBrand, maxLines = 1)
          }
        }
      }
    }

    is TerminalLineModel.InputPrompt -> Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(top = spaces.spacing300),
    ) {
      Mono(
        text = line.symbol,
        style = style,
        color = colors.content.contentBrand,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.width(ch * 2),
      )
      BlinkingCursor(width = ch)
    }
  }
}

@Composable
private fun BlinkingCursor(width: Dp) {
  val transition = rememberInfiniteTransition()
  val phase by transition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(durationMillis = 1100, easing = LinearEasing), RepeatMode.Restart),
  )
  Box(
    modifier = Modifier
      .size(width = width, height = 18.dp)
      .alpha(if (phase < 0.5f) 1f else 0f)
      .background(LemonadeTheme.colors.background.bgBrand),
  )
}

@Composable
internal fun MessageState(
  content: WorkspaceContentModel.Message,
  modifier: Modifier = Modifier,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing300, Alignment.CenterVertically),
    modifier = modifier.padding(LemonadeTheme.spaces.spacing800),
  ) {
    if (content.loading) {
      LemonadeUi.Spinner(size = LemonadeAssetSize.Large, tint = LemonadeTheme.colors.content.contentSecondary)
    } else {
      LemonadeUi.SymbolContainer(
        icon = LemonadeIcons.FileText,
        contentDescription = null,
        voice = SymbolContainerVoice.Neutral,
        size = SymbolContainerSize.XLarge,
      )
    }
    LemonadeUi.Text(text = content.title.resolve(), textStyle = LemonadeTheme.typography.headingXXSmall)
    content.detail?.let { detail ->
      LemonadeUi.Text(
        text = detail.resolve(),
        textStyle = LemonadeTheme.typography.bodySmallRegular,
        color = LemonadeTheme.colors.content.contentSecondary,
      )
    }
  }
}
