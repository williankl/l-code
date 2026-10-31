package dev.loupe.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.teya.lemonade.Icon
import com.teya.lemonade.IconButton
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.Text
import com.teya.lemonade.core.LemonadeAssetSize
import com.teya.lemonade.core.LemonadeButtonSize
import com.teya.lemonade.core.LemonadeButtonType
import com.teya.lemonade.core.LemonadeButtonVariant
import com.teya.lemonade.core.LemonadeIcons
import dev.loupe.platform.designsystem.DotLabel
import dev.loupe.platform.designsystem.Hairline
import dev.loupe.platform.designsystem.HoverTooltip
import dev.loupe.platform.designsystem.StatusDot
import dev.loupe.platform.designsystem.TooltipPlacement
import dev.loupe.platform.designsystem.dashedBorder
import dev.loupe.platform.localization.LoupeString
import dev.loupe.platform.localization.resolve
import dev.loupe.workspace.strings.Res
import dev.loupe.workspace.strings.action_new_terminal
import dev.loupe.workspace.strings.fold_hidden_count
import dev.loupe.workspace.strings.fold_hide
import dev.loupe.workspace.strings.fold_show
import dev.loupe.workspace.strings.sessions_title
import dev.loupe.workspace.uimodel.SessionListRowModel
import dev.loupe.workspace.uimodel.SessionNavigationModel
import dev.loupe.workspace.uimodel.SessionStatus
import dev.loupe.workspace.uimodel.SessionTileModel
import dev.loupe.workspace.uimodel.SessionTreeRowModel
import dev.loupe.workspace.uimodel.TreeFoldModel
import dev.loupe.workspace.uimodel.WorkspaceEvent
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SessionsSidebar(
  navigation: SessionNavigationModel,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val spaces = LemonadeTheme.spaces
  val sessionsLabel = stringResource(Res.string.sessions_title)
  Column(
    verticalArrangement = Arrangement.spacedBy(spaces.spacing100),
    modifier = modifier
      .semantics { contentDescription = sessionsLabel }
      .verticalScroll(rememberScrollState()),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 40.dp)
        .padding(start = spaces.spacing300, end = spaces.spacing100),
    ) {
      LemonadeUi.Text(
        text = sessionsLabel.uppercase(),
        textStyle = LemonadeTheme.typography.bodyXSmallOverline,
        color = LemonadeTheme.colors.content.contentSecondary,
      )
      LemonadeUi.IconButton(
        icon = LemonadeIcons.Plus,
        contentDescription = stringResource(Res.string.action_new_terminal),
        onClick = { onEvent(WorkspaceEvent.NewSession) },
        variant = LemonadeButtonVariant.Neutral,
        type = LemonadeButtonType.Ghost,
        size = LemonadeButtonSize.XSmall,
      )
    }
    when (navigation) {
      is SessionNavigationModel.Flat -> Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing50)) {
        navigation.rows.forEach { row ->
          FlatSessionRow(row = row, onClick = { onEvent(WorkspaceEvent.SelectSession(row.id)) })
        }
      }
      is SessionNavigationModel.Tree -> Column {
        navigation.rows.forEachIndexed { index, row ->
          TreeSessionRow(
            row = row,
            topGap = if (row.depth == 0 && index > 0) spaces.spacing100 else 0.dp,
            onEvent = onEvent,
          )
        }
      }
    }
  }
}

@Composable
private fun FlatSessionRow(
  row: SessionListRowModel,
  onClick: () -> Unit,
) {
  val spaces = LemonadeTheme.spaces
  val elbowColor = LemonadeTheme.colors.border.borderNeutralHigh
  val elbowRadius = LemonadeTheme.radius.radius200
  Row(modifier = Modifier.fillMaxWidth()) {
    if (row.isSubSession) {
      Box(
        modifier = Modifier
          .padding(start = 19.dp, end = 2.dp)
          .offset(y = (-4).dp)
          .size(width = 12.dp, height = 24.dp)
          .drawBehind {
            val stroke = 1.dp.toPx()
            val radius = elbowRadius.toPx()
            val x = stroke / 2
            val y = size.height - stroke / 2
            val path = Path().apply {
              moveTo(x, 0f)
              lineTo(x, y - radius)
              arcTo(Rect(x, y - 2 * radius, x + 2 * radius, y), 180f, -90f, false)
              lineTo(size.width, y)
            }
            drawPath(path = path, color = elbowColor, style = Stroke(width = stroke))
          },
      )
    }
    SelectableSurface(
      selected = row.selected,
      onClick = onClick,
      shape = LemonadeTheme.shapes.radius300,
      modifier = Modifier.weight(1f),
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(spaces.spacing200),
        modifier = Modifier.padding(
          start = spaces.spacing300,
          end = spaces.spacing200,
          top = spaces.spacing200,
          bottom = spaces.spacing200,
        ),
      ) {
        ComputerIcon(selected = row.selected)
        SessionRowText(name = row.name, status = row.status, meta = row.meta, modifier = Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun TreeSessionRow(
  row: SessionTreeRowModel,
  topGap: androidx.compose.ui.unit.Dp,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val spaces = LemonadeTheme.spaces
  val brand = LemonadeTheme.colors.content.contentBrand
  val hairline = LemonadeTheme.colors.border.borderNeutralMedium
  val elbowRadius = LemonadeTheme.radius.radius150
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = topGap)
      .height(IntrinsicSize.Min),
  ) {
    val connector = row.connector
    if (connector != null) {
      Spacer(modifier = Modifier.width(15.dp))
      row.guides.forEach { guide ->
        Box(modifier = Modifier.width(RailColumn).fillMaxHeight()) {
          if (guide.visible) {
            Box(
              modifier = Modifier
                .offset(x = 4.dp)
                .width(1.dp)
                .fillMaxHeight()
                .background(if (guide.highlighted) brand else hairline),
            )
          }
        }
      }
      Box(
        modifier = Modifier
          .width(RailColumn)
          .fillMaxHeight()
          .drawBehind {
            val stroke = 1.dp.toPx()
            val x = 4.dp.toPx() + stroke / 2
            val joinY = RailJoinY.toPx()
            val upper = if (connector.upperHighlighted) brand else hairline
            if (connector.isLastChild) {
              val radius = elbowRadius.toPx()
              val y = joinY - stroke / 2
              val path = Path().apply {
                moveTo(x, 0f)
                lineTo(x, y - radius)
                arcTo(Rect(x, y - 2 * radius, x + 2 * radius, y), 180f, -90f, false)
                lineTo(size.width, y)
              }
              drawPath(path = path, color = upper, style = Stroke(width = stroke))
            } else {
              val lower = if (connector.lowerHighlighted) brand else hairline
              val branch = if (connector.branchHighlighted) brand else hairline
              drawRect(upper, topLeft = Offset(x - stroke / 2, 0f), size = Size(stroke, joinY))
              drawRect(lower, topLeft = Offset(x - stroke / 2, joinY), size = Size(stroke, size.height - joinY))
              drawRect(branch, topLeft = Offset(x + stroke / 2, joinY - stroke), size = Size(size.width - x, stroke))
            }
          },
      )
    }
    SelectableSurface(
      selected = row.selected,
      onClick = { onEvent(WorkspaceEvent.SelectSession(row.id)) },
      shape = LemonadeTheme.shapes.radius300,
      modifier = Modifier.weight(1f),
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(spaces.spacing200),
        modifier = Modifier.padding(
          start = if (row.depth == 0) spaces.spacing300 else 6.dp,
          end = spaces.spacing200,
          top = 6.dp,
          bottom = 6.dp,
        ),
      ) {
        if (row.depth == 0) ComputerIcon(selected = row.selected)
        SessionRowText(
          name = row.name,
          status = row.status,
          meta = row.meta,
          modifier = Modifier.weight(1f),
          trailing = row.fold?.let { fold ->
            { FoldToggle(fold = fold, onClick = { onEvent(WorkspaceEvent.ToggleSessionFold(row.id)) }) }
          },
        )
      }
    }
  }
}

private val RailColumn = 10.dp

/** Where a child's elbow meets it: the vertical centre of the name line. */
private val RailJoinY = 16.dp

@Composable
private fun ComputerIcon(selected: Boolean) {
  LemonadeUi.Icon(
    icon = LemonadeIcons.Computer,
    contentDescription = null,
    size = LemonadeAssetSize.Small,
    tint = if (selected) LemonadeTheme.colors.content.contentPrimary else LemonadeTheme.colors.content.contentSecondary,
    modifier = Modifier.padding(top = 2.dp),
  )
}

@Composable
private fun SessionRowText(
  name: String,
  status: SessionStatus,
  meta: LoupeString,
  modifier: Modifier = Modifier,
  trailing: (@Composable () -> Unit)? = null,
) {
  val style = status.style()
  Column(modifier = modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing200),
    ) {
      LemonadeUi.Text(
        text = name,
        textStyle = LemonadeTheme.typography.bodySmallSemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      if (style != null) DotLabel(text = style.word, dotColor = style.dot, textColor = style.text)
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing100),
      modifier = Modifier.heightIn(min = 18.dp),
    ) {
      LemonadeUi.Text(
        text = meta.resolve(),
        textStyle = LemonadeTheme.typography.bodyXSmallRegular,
        color = LemonadeTheme.colors.content.contentSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      trailing?.invoke()
    }
  }
}

@Composable
private fun FoldToggle(
  fold: TreeFoldModel,
  onClick: () -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val hovered by interactionSource.collectIsHoveredAsState()
  val colors = LemonadeTheme.colors
  val tint = if (hovered) colors.content.contentPrimary else colors.content.contentSecondary
  val label = pluralStringResource(if (fold.folded) Res.plurals.fold_show else Res.plurals.fold_hide, fold.descendantCount, fold.descendantCount)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    modifier = Modifier
      .height(18.dp)
      .clip(LemonadeTheme.shapes.radius150)
      .background(if (hovered) colors.background.bgElevatedHigh else Color.Transparent)
      .hoverable(interactionSource)
      .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
      .semantics { contentDescription = label }
      .padding(horizontal = LemonadeTheme.spaces.spacing100),
  ) {
    if (fold.folded) {
      LemonadeUi.Text(
        text = stringResource(Res.string.fold_hidden_count, fold.descendantCount),
        textStyle = LemonadeTheme.typography.bodyXSmallMedium,
        color = tint,
      )
    }
    LemonadeUi.Icon(
      icon = if (fold.folded) LemonadeIcons.ChevronRight else LemonadeIcons.ChevronDown,
      contentDescription = null,
      size = LemonadeAssetSize.XSmall,
      tint = tint,
    )
  }
}

@Composable
internal fun SessionRail(
  tiles: List<SessionTileModel>,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val sessionsLabel = stringResource(Res.string.sessions_title)
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing200),
    modifier = modifier
      .semantics { contentDescription = sessionsLabel }
      .verticalScroll(rememberScrollState())
      .padding(vertical = LemonadeTheme.spaces.spacing100),
  ) {
    LemonadeUi.IconButton(
      icon = LemonadeIcons.Plus,
      contentDescription = stringResource(Res.string.action_new_terminal),
      onClick = { onEvent(WorkspaceEvent.NewSession) },
      variant = LemonadeButtonVariant.Neutral,
      type = LemonadeButtonType.Ghost,
      size = LemonadeButtonSize.Small,
    )
    Hairline(width = 28.dp, height = 1.dp)
    tiles.forEach { tile ->
      HoverTooltip(text = tile.name, placement = TooltipPlacement.End) {
        SessionTile(tile = tile, onClick = { onEvent(WorkspaceEvent.SelectSession(tile.id)) })
      }
    }
  }
}

@Composable
private fun SessionTile(
  tile: SessionTileModel,
  onClick: () -> Unit,
) {
  val colors = LemonadeTheme.colors
  val shape = LemonadeTheme.shapes.radius300
  val radius = LemonadeTheme.radius.radius300
  val outline = when {
    tile.selected && tile.isSubSession -> Modifier.dashedBorder(2.dp, colors.border.borderSelected, radius)
    tile.selected -> Modifier.border(2.dp, colors.border.borderSelected, shape)
    tile.isSubSession -> Modifier.dashedBorder(1.5.dp, colors.border.borderNeutralHigh, radius)
    else -> Modifier.border(1.dp, colors.border.borderNeutralLow, shape)
  }
  Box {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(44.dp)
        .clip(shape)
        .background(if (tile.selected) colors.background.bgElevatedHigh else colors.background.bgNeutralSubtle)
        .then(outline)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = tile.name },
    ) {
      LemonadeUi.Text(text = tile.initials, textStyle = LemonadeTheme.typography.bodySmallSemiBold)
    }
    tile.status.style()?.let { style ->
      StatusDot(
        color = style.dot,
        size = 12.dp,
        ring = colors.background.bgSubtle,
        modifier = Modifier.align(Alignment.BottomEnd).offset(x = 5.dp, y = 5.dp),
      )
    }
  }
}
