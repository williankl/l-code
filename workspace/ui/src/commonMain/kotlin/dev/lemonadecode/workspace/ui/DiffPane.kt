package dev.lemonadecode.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.teya.lemonade.Badge
import com.teya.lemonade.Button
import com.teya.lemonade.Chip
import com.teya.lemonade.Icon
import com.teya.lemonade.IconButton
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.SymbolContainer
import com.teya.lemonade.Tag
import com.teya.lemonade.Text
import com.teya.lemonade.core.LemonadeAssetSize
import com.teya.lemonade.core.LemonadeBadgeSize
import com.teya.lemonade.core.LemonadeButtonSize
import com.teya.lemonade.core.LemonadeButtonType
import com.teya.lemonade.core.LemonadeButtonVariant
import com.teya.lemonade.core.LemonadeIcons
import com.teya.lemonade.core.SymbolContainerShape
import com.teya.lemonade.core.SymbolContainerSize
import com.teya.lemonade.core.SymbolContainerVoice
import com.teya.lemonade.core.TagVoice
import dev.lemonadecode.platform.designsystem.AnchoredPopover
import dev.lemonadecode.platform.designsystem.TooltipPlacement
import dev.lemonadecode.platform.designsystem.bottomHairline
import dev.lemonadecode.platform.localization.resolve
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.diff_changes
import dev.lemonadecode.workspace.strings.diff_compare_with
import dev.lemonadecode.workspace.strings.diff_refresh
import dev.lemonadecode.workspace.strings.diff_vs
import dev.lemonadecode.workspace.strings.file_added
import dev.lemonadecode.workspace.strings.file_collapse
import dev.lemonadecode.workspace.strings.file_comment_count
import dev.lemonadecode.workspace.strings.file_deleted
import dev.lemonadecode.workspace.strings.file_expand
import dev.lemonadecode.workspace.strings.file_full
import dev.lemonadecode.workspace.strings.file_modified
import dev.lemonadecode.workspace.strings.file_preview
import dev.lemonadecode.workspace.strings.file_renamed
import dev.lemonadecode.workspace.ui.preview.FilePreviewBody
import dev.lemonadecode.workspace.uimodel.DiffBaseModel
import dev.lemonadecode.workspace.uimodel.DiffFileModel
import dev.lemonadecode.workspace.uimodel.DiffReviewModel
import dev.lemonadecode.workspace.uimodel.DiffToolbarModel
import dev.lemonadecode.workspace.uimodel.FileChangeKind
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DiffPane(
  review: DiffReviewModel,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val spaces = LemonadeTheme.spaces
  Column(modifier = modifier) {
    DiffToolbar(toolbar = review.toolbar, onEvent = onEvent)
    LazyColumn(
      contentPadding = PaddingValues(start = spaces.spacing400, end = spaces.spacing400, bottom = spaces.spacing400),
      modifier = Modifier.weight(1f).fillMaxWidth(),
    ) {
      review.files.forEachIndexed { index, file ->
        if (index > 0) {
          item(key = "gap:${file.id}") { Spacer(modifier = Modifier.height(spaces.spacing300)) }
        }
        stickyHeader(key = "head:${file.id}") {
          DiffFileHeader(file = file, onEvent = onEvent)
        }
        if (file.expanded) {
          item(key = "body:${file.id}") {
            val preview = file.preview
            if (preview != null && preview.showing) {
              FilePreviewBody(file = file, preview = preview, onEvent = onEvent)
            } else {
              DiffFileBody(file = file, onEvent = onEvent)
            }
          }
        }
      }
    }
    SendBar(sendBar = review.sendBar, onEvent = onEvent)
  }
}

/** "Changes · working tree vs <branch>", the branch a picker; the summary once there is a diff. */
@Composable
internal fun DiffToolbar(
  toolbar: DiffToolbarModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val spaces = LemonadeTheme.spaces
  val secondary = LemonadeTheme.colors.content.contentSecondary
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spaces.spacing300),
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = spaces.spacing600, end = spaces.spacing400, top = spaces.spacing300, bottom = spaces.spacing300),
  ) {
    LemonadeUi.Text(text = stringResource(Res.string.diff_changes), textStyle = LemonadeTheme.typography.headingXXSmall)
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spaces.spacing200),
    ) {
      LemonadeUi.Tag(label = toolbar.sourceLabel.resolve(), voice = TagVoice.Neutral)
      LemonadeUi.Text(text = stringResource(Res.string.diff_vs), textStyle = LemonadeTheme.typography.bodySmallRegular, color = secondary)
      DiffBasePicker(base = toolbar.base, onEvent = onEvent)
    }
    LemonadeUi.Text(
      text = toolbar.summary?.resolve().orEmpty(),
      textStyle = LemonadeTheme.typography.bodySmallRegular,
      color = secondary,
      maxLines = 1,
      modifier = Modifier.weight(1f),
    )
    LemonadeUi.IconButton(
      icon = LemonadeIcons.ArrowRotateCw,
      contentDescription = stringResource(Res.string.diff_refresh),
      onClick = { onEvent(WorkspaceEvent.RefreshDiff) },
      variant = LemonadeButtonVariant.Neutral,
      type = LemonadeButtonType.Ghost,
      size = LemonadeButtonSize.XSmall,
    )
  }
}

/** The branch the diff compares with; opens the list of the others. */
@Composable
private fun DiffBasePicker(
  base: DiffBaseModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  Box {
    LemonadeUi.Button(
      label = base.selected ?: "…",
      onClick = { onEvent(WorkspaceEvent.ToggleDiffBasePicker) },
      trailingIcon = LemonadeIcons.ChevronDown,
      variant = LemonadeButtonVariant.Neutral,
      type = if (base.pickerOpen) LemonadeButtonType.Solid else LemonadeButtonType.Subtle,
      size = LemonadeButtonSize.Small,
      enabled = base.options.isNotEmpty(),
    )
    if (base.pickerOpen) {
      AnchoredPopover(
        onDismissRequest = { onEvent(WorkspaceEvent.DismissDiffBasePicker) },
        width = BasePickerWidth,
        placement = TooltipPlacement.BelowStart,
      ) {
        LemonadeUi.Text(
          text = stringResource(Res.string.diff_compare_with).uppercase(),
          textStyle = LemonadeTheme.typography.bodyXSmallOverline,
          color = LemonadeTheme.colors.content.contentSecondary,
        )
        Column(
          modifier = Modifier
            .heightIn(max = BasePickerMaxHeight)
            .verticalScroll(rememberScrollState()),
        ) {
          base.options.forEach { branch ->
            BaseOption(branch = branch, selected = branch == base.selected, onClick = { onEvent(WorkspaceEvent.CompareWith(branch)) })
          }
        }
      }
    }
  }
}

/** One branch in the picker, ticked when it is the one compared with. */
@Composable
private fun BaseOption(
  branch: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val hovered by interactionSource.collectIsHoveredAsState()
  val colors = LemonadeTheme.colors
  val spaces = LemonadeTheme.spaces
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spaces.spacing200),
    modifier = Modifier
      .fillMaxWidth()
      .clip(LemonadeTheme.shapes.radius300)
      .background(if (hovered) colors.background.bgElevated else Color.Transparent)
      .hoverable(interactionSource)
      .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
      .padding(horizontal = spaces.spacing300, vertical = spaces.spacing200),
  ) {
    Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
      if (selected) {
        LemonadeUi.Icon(icon = LemonadeIcons.Check, contentDescription = null, size = LemonadeAssetSize.Small, tint = colors.content.contentPrimary)
      }
    }
    LemonadeUi.Text(
      text = branch,
      textStyle = if (selected) LemonadeTheme.typography.bodySmallSemiBold else LemonadeTheme.typography.bodySmallMedium,
      color = colors.content.contentPrimary,
      maxLines = 1,
    )
  }
}

private val BasePickerWidth = 280.dp
private val BasePickerMaxHeight = 320.dp

@Composable
private fun DiffFileHeader(
  file: DiffFileModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val spaces = LemonadeTheme.spaces
  val colors = LemonadeTheme.colors
  val radius = LemonadeTheme.radius.radius500
  val shape = if (file.expanded) RoundedCornerShape(topStart = radius, topEnd = radius) else RoundedCornerShape(radius)
  val changeName = when (file.change) {
    FileChangeKind.Added -> stringResource(Res.string.file_added)
    FileChangeKind.Modified -> stringResource(Res.string.file_modified)
    FileChangeKind.Deleted -> stringResource(Res.string.file_deleted)
    FileChangeKind.Renamed -> stringResource(Res.string.file_renamed)
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spaces.spacing200),
    modifier = Modifier
      .fillMaxWidth()
      .background(colors.background.bgSubtle, shape)
      .then(if (file.expanded) Modifier.bottomHairline(colors.border.borderNeutralLow) else Modifier)
      .padding(start = spaces.spacing200, end = spaces.spacing300, top = spaces.spacing200, bottom = spaces.spacing200),
  ) {
    LemonadeUi.IconButton(
      icon = if (file.expanded) LemonadeIcons.ChevronDown else LemonadeIcons.ChevronRight,
      contentDescription = stringResource(if (file.expanded) Res.string.file_collapse else Res.string.file_expand),
      onClick = { onEvent(WorkspaceEvent.ToggleFileExpanded(file.id)) },
      variant = LemonadeButtonVariant.Neutral,
      type = LemonadeButtonType.Ghost,
      size = LemonadeButtonSize.XSmall,
    )
    LemonadeUi.SymbolContainer(
      text = changeName.take(1).uppercase(),
      voice = when (file.change) {
        FileChangeKind.Added -> SymbolContainerVoice.Positive
        FileChangeKind.Modified -> SymbolContainerVoice.Info
        FileChangeKind.Deleted -> SymbolContainerVoice.Critical
        FileChangeKind.Renamed -> SymbolContainerVoice.Warning
      },
      size = SymbolContainerSize.XSmall,
      shape = SymbolContainerShape.Rounded,
      modifier = Modifier.semantics { contentDescription = changeName },
    )
    LemonadeUi.Text(
      text = buildAnnotatedString {
        withStyle(SpanStyle(color = colors.content.contentSecondary)) { append(file.directory) }
        withStyle(SpanStyle(color = colors.content.contentPrimary, fontWeight = FontWeight.SemiBold)) {
          append(file.fileName)
        }
      },
      textStyle = LemonadeTheme.typography.bodySmallRegular,
      maxLines = 1,
    )
    LemonadeUi.Tag(label = file.language, voice = TagVoice.Neutral)
    if (file.commentCount > 0) {
      val commentCountLabel = pluralStringResource(Res.plurals.file_comment_count, file.commentCount, file.commentCount)
      LemonadeUi.Badge(
        text = file.commentCount.toString(),
        size = LemonadeBadgeSize.Small,
        modifier = Modifier.semantics { contentDescription = commentCountLabel },
      )
    }
    Spacer(modifier = Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(spaces.spacing200)) {
      if (file.additions > 0) {
        LemonadeUi.Text(
          text = "+${file.additions}",
          textStyle = LemonadeTheme.typography.bodySmallSemiBold,
          color = colors.content.contentPositive,
        )
      }
      if (file.deletions > 0) {
        LemonadeUi.Text(
          text = "−${file.deletions}",
          textStyle = LemonadeTheme.typography.bodySmallSemiBold,
          color = colors.content.contentCritical,
        )
      }
    }
    file.preview?.let { preview ->
      LemonadeUi.Chip(
        label = stringResource(Res.string.file_preview),
        selected = preview.showing,
        leadingIcon = LemonadeIcons.EyeOpen,
        onChipClicked = { onEvent(WorkspaceEvent.ToggleFilePreview(file.id)) },
      )
    }
    if (file.canShowFullFile) {
      LemonadeUi.Chip(
        label = stringResource(Res.string.file_full),
        selected = file.showingFullFile,
        leadingIcon = LemonadeIcons.FileText,
        onChipClicked = { onEvent(WorkspaceEvent.ToggleFullFile(file.id)) },
      )
    }
  }
}
