package dev.loupe.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.Text
import dev.loupe.workspace.strings.Res
import dev.loupe.workspace.strings.status_exited
import dev.loupe.workspace.strings.status_idle
import dev.loupe.workspace.strings.status_needs_input
import dev.loupe.workspace.strings.status_working
import dev.loupe.workspace.uimodel.SessionStatus
import org.jetbrains.compose.resources.stringResource

internal data class StatusStyle(
  val word: String,
  val dot: Color,
  val text: Color,
)

/** How a status looks; null for a plain terminal, which shows no status. */
@Composable
internal fun SessionStatus.style(): StatusStyle? {
  val colors = LemonadeTheme.colors
  return when (this) {
    SessionStatus.Shell -> null
    SessionStatus.Working -> StatusStyle(stringResource(Res.string.status_working), colors.background.bgBrand, colors.content.contentBrand)
    SessionStatus.NeedsInput -> StatusStyle(stringResource(Res.string.status_needs_input), colors.background.bgInfo, colors.content.contentInfo)
    SessionStatus.Idle -> StatusStyle(stringResource(Res.string.status_idle), colors.background.bgNeutral, colors.content.contentSecondary)
    SessionStatus.Exited -> StatusStyle(stringResource(Res.string.status_exited), colors.background.bgNeutralSubtle, colors.content.contentTertiary)
  }
}

internal fun plural(count: Int, singular: String, pluralForm: String = singular + "s"): String = "$count ${if (count == 1) singular else pluralForm}"

/**
 * A clickable surface that tints on hover and holds a stronger tint when selected, the way the
 * session rows and tiles behave.
 */
@Composable
internal fun SelectableSurface(
  selected: Boolean,
  onClick: () -> Unit,
  shape: Shape,
  modifier: Modifier = Modifier,
  content: @Composable BoxScope.() -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val hovered by interactionSource.collectIsHoveredAsState()
  val background = when {
    selected -> LemonadeTheme.colors.background.bgElevatedHigh
    hovered -> LemonadeTheme.colors.background.bgElevated
    else -> Color.Transparent
  }
  Box(
    modifier = modifier
      .clip(shape)
      .background(background)
      .hoverable(interactionSource)
      .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick),
    content = content,
  )
}

/** Monospace text in an explicit colour. */
@Composable
internal fun Mono(
  text: String,
  style: TextStyle,
  color: Color,
  modifier: Modifier = Modifier,
  fontWeight: FontWeight? = null,
  maxLines: Int = Int.MAX_VALUE,
) {
  LemonadeUi.Text(
    text = text,
    modifier = modifier,
    textStyle = style.copy(color = color, fontWeight = fontWeight ?: style.fontWeight),
    maxLines = maxLines,
  )
}
