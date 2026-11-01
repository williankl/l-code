package dev.lemonadecode.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.teya.lemonade.Button
import com.teya.lemonade.Icon
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.SymbolContainer
import com.teya.lemonade.Text
import com.teya.lemonade.core.LemonadeButtonSize
import com.teya.lemonade.core.LemonadeButtonType
import com.teya.lemonade.core.LemonadeButtonVariant
import com.teya.lemonade.core.LemonadeIcons
import com.teya.lemonade.core.SymbolContainerSize
import com.teya.lemonade.core.SymbolContainerVoice
import dev.lemonadecode.platform.designsystem.KeyHint
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.platform.designsystem.topHairline
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.action_new_sub_session
import dev.lemonadecode.workspace.strings.action_preview
import dev.lemonadecode.workspace.strings.action_send_to_terminal
import dev.lemonadecode.workspace.strings.send_across_files
import dev.lemonadecode.workspace.strings.send_all_delivered
import dev.lemonadecode.workspace.strings.send_hint
import dev.lemonadecode.workspace.strings.send_no_comments
import dev.lemonadecode.workspace.strings.send_preview_note
import dev.lemonadecode.workspace.strings.send_unsent
import dev.lemonadecode.workspace.uimodel.SendBarModel
import dev.lemonadecode.workspace.uimodel.SendBarSummary
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Bottom of the diff review: the comment tally, the message preview and the send actions. */
@Composable
internal fun SendBar(
  sendBar: SendBarModel,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val spaces = LemonadeTheme.spaces
  val colors = LemonadeTheme.colors
  Column(
    verticalArrangement = Arrangement.spacedBy(spaces.spacing300),
    modifier = modifier
      .fillMaxWidth()
      .background(colors.background.bgDefault)
      .topHairline(colors.border.borderNeutralLow)
      .padding(start = spaces.spacing600, end = spaces.spacing400, top = spaces.spacing300, bottom = spaces.spacing300),
  ) {
    sendBar.preview?.let { message ->
      Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing200)) {
        SelectionContainer(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(colors.background.bgSubtle, LemonadeTheme.shapes.radius300)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spaces.spacing400, vertical = spaces.spacing300),
        ) {
          Mono(text = message, style = LemonadeCodeTheme.typography.code, color = colors.content.contentPrimary)
        }
        LemonadeUi.Text(
          text = stringResource(Res.string.send_preview_note),
          textStyle = LemonadeTheme.typography.bodyXSmallRegular,
          color = colors.content.contentSecondary,
        )
      }
    }
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(spaces.spacing400),
      verticalArrangement = Arrangement.spacedBy(spaces.spacing300),
      itemVerticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth(),
    ) {
      SendSummary(
        summary = sendBar.summary,
        modifier = Modifier.weight(1f).widthIn(min = 260.dp),
      )
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spaces.spacing200),
      ) {
        val previewOpen = sendBar.preview != null
        LemonadeUi.Button(
          label = stringResource(Res.string.action_preview),
          onClick = { onEvent(WorkspaceEvent.TogglePreview) },
          leadingIcon = LemonadeIcons.EyeOpen,
          variant = if (previewOpen) LemonadeButtonVariant.Secondary else LemonadeButtonVariant.Neutral,
          type = if (previewOpen) LemonadeButtonType.Solid else LemonadeButtonType.Subtle,
          size = LemonadeButtonSize.Small,
          enabled = sendBar.canSend,
        )
        LemonadeUi.Button(
          label = stringResource(Res.string.action_new_sub_session),
          onClick = { onEvent(WorkspaceEvent.SendToNewSubSession) },
          leadingIcon = LemonadeIcons.ArrowSplit,
          variant = LemonadeButtonVariant.Secondary,
          type = LemonadeButtonType.Subtle,
          size = LemonadeButtonSize.Small,
          enabled = sendBar.canSend,
        )
        LemonadeUi.Button(
          label = stringResource(Res.string.action_send_to_terminal),
          onClick = { onEvent(WorkspaceEvent.SendToSession) },
          variant = LemonadeButtonVariant.Primary,
          type = LemonadeButtonType.Solid,
          size = LemonadeButtonSize.Small,
          enabled = sendBar.canSend,
          leadingSlot = { buttonColors ->
            Spacer(modifier = Modifier.width(spaces.spacing300))
            LemonadeUi.Icon(
              icon = LemonadeIcons.Send,
              contentDescription = null,
              tint = buttonColors.contentColor,
              modifier = Modifier.borrowSpace(end = spaces.spacing300),
            )
          },
          trailingSlot = { buttonColors ->
            KeyHint(
              keys = "⌘↵",
              color = buttonColors.contentColor,
              borderColor = colors.border.borderAlwaysDarkHigh,
              modifier = Modifier.borrowSpace(start = spaces.spacing200),
            )
            Spacer(modifier = Modifier.width(spaces.spacing300))
          },
        )
      }
    }
  }
}

@Composable
private fun SendSummary(
  summary: SendBarSummary,
  modifier: Modifier = Modifier,
) {
  val delivered = summary is SendBarSummary.AllDelivered
  val (title, subtitle) = when (summary) {
    is SendBarSummary.Unsent ->
      pluralStringResource(Res.plurals.send_unsent, summary.commentCount, summary.commentCount) to
        pluralStringResource(Res.plurals.send_across_files, summary.fileCount, summary.fileCount)
    SendBarSummary.AllDelivered -> stringResource(Res.string.send_all_delivered) to stringResource(Res.string.send_hint)
    SendBarSummary.NoComments -> stringResource(Res.string.send_no_comments) to stringResource(Res.string.send_hint)
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing300),
    modifier = modifier,
  ) {
    LemonadeUi.SymbolContainer(
      icon = if (delivered) LemonadeIcons.CircleCheck else LemonadeIcons.Bubble,
      contentDescription = null,
      voice = if (delivered) SymbolContainerVoice.Positive else SymbolContainerVoice.Neutral,
      size = SymbolContainerSize.Medium,
    )
    Column {
      LemonadeUi.Text(text = title, textStyle = LemonadeTheme.typography.bodyMediumSemiBold)
      LemonadeUi.Text(
        text = subtitle,
        textStyle = LemonadeTheme.typography.bodySmallRegular,
        color = LemonadeTheme.colors.content.contentSecondary,
      )
    }
  }
}

/**
 * Lets a button slot reach into the label's padding, so a slotted icon sits as close to the label
 * as Lemonade's own `leadingIcon` does.
 */
private fun Modifier.borrowSpace(start: Dp = 0.dp, end: Dp = 0.dp): Modifier = layout { measurable, constraints ->
  val placeable = measurable.measure(constraints)
  val startPx = start.roundToPx()
  val endPx = end.roundToPx()
  layout((placeable.width - startPx - endPx).coerceAtLeast(0), placeable.height) {
    placeable.place(-startPx, 0)
  }
}
