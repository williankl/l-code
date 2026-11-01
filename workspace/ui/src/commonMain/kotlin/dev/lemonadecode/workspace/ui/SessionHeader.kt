package dev.lemonadecode.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.teya.lemonade.Badge
import com.teya.lemonade.Button
import com.teya.lemonade.IconButton
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.SegmentedControl
import com.teya.lemonade.Tag
import com.teya.lemonade.Text
import com.teya.lemonade.core.LemonadeBadgeSize
import com.teya.lemonade.core.LemonadeButtonSize
import com.teya.lemonade.core.LemonadeButtonType
import com.teya.lemonade.core.LemonadeButtonVariant
import com.teya.lemonade.core.LemonadeIcons
import com.teya.lemonade.core.LemonadeSegmentedControlSize
import com.teya.lemonade.core.TabButtonProperties
import com.teya.lemonade.core.TagVoice
import dev.lemonadecode.platform.designsystem.AnchoredPopover
import dev.lemonadecode.platform.designsystem.DotLabel
import dev.lemonadecode.platform.designsystem.HoverTooltip
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.platform.designsystem.SlotSegmentedControl
import dev.lemonadecode.platform.designsystem.TooltipPlacement
import dev.lemonadecode.platform.designsystem.bottomHairline
import dev.lemonadecode.platform.designsystem.rememberCharWidth
import dev.lemonadecode.platform.localization.resolve
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.action_cancel
import dev.lemonadecode.workspace.strings.action_create_sub_session
import dev.lemonadecode.workspace.strings.action_new_sub_session
import dev.lemonadecode.workspace.strings.location_new_worktree
import dev.lemonadecode.workspace.strings.location_same_folder
import dev.lemonadecode.workspace.strings.sub_session_from
import dev.lemonadecode.workspace.strings.sub_session_location
import dev.lemonadecode.workspace.strings.sub_session_title
import dev.lemonadecode.workspace.strings.view_diff
import dev.lemonadecode.workspace.strings.view_terminal
import dev.lemonadecode.workspace.uimodel.HeaderTagIcon
import dev.lemonadecode.workspace.uimodel.NewSubSessionModel
import dev.lemonadecode.workspace.uimodel.SessionHeaderModel
import dev.lemonadecode.workspace.uimodel.SubSessionLocation
import dev.lemonadecode.workspace.uimodel.ViewSwitcherModel
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import dev.lemonadecode.workspace.uimodel.WorkspaceView
import org.jetbrains.compose.resources.stringResource

private val ViewSwitcherWidth = 420.dp

@Composable
internal fun SessionHeaderBar(
  header: SessionHeaderModel,
  viewSwitcher: ViewSwitcherModel,
  newSubSession: NewSubSessionModel,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val spaces = LemonadeTheme.spaces
  FlowRow(
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalArrangement = Arrangement.spacedBy(spaces.spacing300),
    itemVerticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .fillMaxWidth()
      .bottomHairline(LemonadeTheme.colors.border.borderNeutralLow)
      .padding(start = spaces.spacing600, end = spaces.spacing400, top = spaces.spacing300, bottom = spaces.spacing300),
  ) {
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(spaces.spacing200),
      verticalArrangement = Arrangement.spacedBy(spaces.spacing200),
      itemVerticalAlignment = Alignment.CenterVertically,
    ) {
      header.parentName?.let { parent ->
        LemonadeUi.Button(
          label = parent,
          onClick = { onEvent(WorkspaceEvent.OpenParentSession) },
          leadingIcon = LemonadeIcons.ArrowLeft,
          variant = LemonadeButtonVariant.Neutral,
          type = LemonadeButtonType.Ghost,
          size = LemonadeButtonSize.XSmall,
        )
        LemonadeUi.Text(
          text = "/",
          textStyle = LemonadeTheme.typography.headingXSmall,
          color = LemonadeTheme.colors.content.contentTertiary,
        )
      }
      LemonadeUi.Text(text = header.name, textStyle = LemonadeTheme.typography.headingXSmall, maxLines = 1)
      header.tags.forEach { tag ->
        LemonadeUi.Tag(
          label = tag.label.resolve(),
          voice = TagVoice.Neutral,
          icon = when (tag.icon) {
            HeaderTagIcon.None -> null
            HeaderTagIcon.SubSession -> LemonadeIcons.ArrowSplit
            HeaderTagIcon.Terminal -> LemonadeIcons.Computer
          },
        )
      }
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spaces.spacing200),
    ) {
      if (header.canStartSubSession) {
        Box {
          HoverTooltip(
            text = stringResource(Res.string.action_new_sub_session),
            placement = TooltipPlacement.BelowEnd,
            enabled = !newSubSession.open,
          ) {
            LemonadeUi.IconButton(
              icon = LemonadeIcons.ArrowSplit,
              contentDescription = stringResource(Res.string.action_new_sub_session),
              onClick = { onEvent(WorkspaceEvent.ToggleNewSubSessionMenu) },
              variant = LemonadeButtonVariant.Neutral,
              type = if (newSubSession.open) LemonadeButtonType.Solid else LemonadeButtonType.Subtle,
              size = LemonadeButtonSize.Small,
            )
          }
          if (newSubSession.open) {
            NewSubSessionPopover(menu = newSubSession, onEvent = onEvent)
          }
        }
      }
      ViewSwitcher(
        switcher = viewSwitcher,
        onSelect = { onEvent(WorkspaceEvent.SelectView(it)) },
        modifier = Modifier.width(ViewSwitcherWidth),
      )
    }
  }
}

@Composable
private fun ViewSwitcher(
  switcher: ViewSwitcherModel,
  onSelect: (WorkspaceView) -> Unit,
  modifier: Modifier = Modifier,
) {
  val views = WorkspaceView.entries
  val colors = LemonadeTheme.colors
  SlotSegmentedControl(
    tabCount = views.size,
    selectedIndex = views.indexOf(switcher.selected),
    onSelect = { onSelect(views[it]) },
    modifier = modifier,
  ) { index, selected ->
    val labelColor = if (selected) colors.content.contentPrimary else colors.content.contentSecondary
    when (views[index]) {
      WorkspaceView.Terminal -> {
        LemonadeUi.Text(text = stringResource(Res.string.view_terminal), textStyle = LemonadeTheme.typography.bodySmallMedium, color = labelColor)
        switcher.sessionStatus.style()?.let { status -> DotLabel(text = status.word, dotColor = status.dot, textColor = status.text) }
      }
      WorkspaceView.Diff -> {
        LemonadeUi.Text(
          text = stringResource(Res.string.view_diff),
          textStyle = LemonadeTheme.typography.bodySmallMedium,
          color = labelColor,
          maxLines = 1,
        )
        switcher.diffStat?.let { stat ->
          Row(horizontalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing100)) {
            LemonadeUi.Text(
              text = "+${stat.additions}",
              textStyle = LemonadeTheme.typography.bodyXSmallSemiBold,
              color = colors.content.contentPositive,
            )
            LemonadeUi.Text(
              text = "−${stat.deletions}",
              textStyle = LemonadeTheme.typography.bodyXSmallSemiBold,
              color = colors.content.contentCritical,
            )
          }
        }
        if (switcher.unsentCommentCount > 0) {
          LemonadeUi.Badge(text = switcher.unsentCommentCount.toString(), size = LemonadeBadgeSize.Small)
        }
      }
    }
  }
}

private val LocationTabs = listOf(
  SubSessionLocation.SameFolder to Res.string.location_same_folder,
  SubSessionLocation.NewWorktree to Res.string.location_new_worktree,
)

@Composable
private fun NewSubSessionPopover(
  menu: NewSubSessionModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val spaces = LemonadeTheme.spaces
  val colors = LemonadeTheme.colors
  val mono = LemonadeCodeTheme.typography.codeSmall
  val code = LemonadeCodeTheme.code
  AnchoredPopover(onDismissRequest = { onEvent(WorkspaceEvent.DismissNewSubSessionMenu) }) {
    Column {
      LemonadeUi.Text(text = stringResource(Res.string.sub_session_title), textStyle = LemonadeTheme.typography.headingXXSmall)
      LemonadeUi.Text(
        text = stringResource(Res.string.sub_session_from, menu.fromSessionName),
        textStyle = LemonadeTheme.typography.bodySmallRegular,
        color = colors.content.contentSecondary,
      )
    }
    Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing200)) {
      LemonadeUi.Text(text = stringResource(Res.string.sub_session_location), textStyle = LemonadeTheme.typography.bodySmallSemiBold)
      LemonadeUi.SegmentedControl(
        properties = LocationTabs.map { (_, label) -> TabButtonProperties.label(stringResource(label)) },
        selectedTab = LocationTabs.indexOfFirst { (location, _) -> location == menu.location },
        onTabSelected = { index -> onEvent(WorkspaceEvent.SetSubSessionLocation(LocationTabs[index].first)) },
        size = LemonadeSegmentedControlSize.Medium,
        modifier = Modifier.fillMaxWidth(),
      )
      Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
          .fillMaxWidth()
          .background(colors.background.bgSubtle, LemonadeTheme.shapes.radius300)
          .padding(horizontal = spaces.spacing300, vertical = spaces.spacing200),
      ) {
        Mono(text = menu.path, style = mono, color = code.path)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(rememberCharWidth(mono))) {
          Mono(text = menu.branch, style = mono, color = code.branch)
          Mono(text = menu.branchNote.resolve(), style = mono, color = colors.content.contentSecondary)
        }
      }
      LemonadeUi.Text(
        text = menu.help.resolve(),
        textStyle = LemonadeTheme.typography.bodyXSmallRegular,
        color = colors.content.contentSecondary,
      )
    }
    Row(
      horizontalArrangement = Arrangement.spacedBy(spaces.spacing200, Alignment.End),
      modifier = Modifier.fillMaxWidth(),
    ) {
      LemonadeUi.Button(
        label = stringResource(Res.string.action_cancel),
        onClick = { onEvent(WorkspaceEvent.DismissNewSubSessionMenu) },
        variant = LemonadeButtonVariant.Neutral,
        type = LemonadeButtonType.Subtle,
        size = LemonadeButtonSize.Small,
      )
      LemonadeUi.Button(
        label = stringResource(Res.string.action_create_sub_session),
        onClick = { onEvent(WorkspaceEvent.CreateSubSession) },
        variant = LemonadeButtonVariant.Primary,
        type = LemonadeButtonType.Solid,
        size = LemonadeButtonSize.Small,
      )
    }
  }
}
