package dev.loupe.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.teya.lemonade.Badge
import com.teya.lemonade.Chip
import com.teya.lemonade.IconButton
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.SymbolContainer
import com.teya.lemonade.Tag
import com.teya.lemonade.Text
import com.teya.lemonade.core.LemonadeBadgeSize
import com.teya.lemonade.core.LemonadeButtonSize
import com.teya.lemonade.core.LemonadeButtonType
import com.teya.lemonade.core.LemonadeButtonVariant
import com.teya.lemonade.core.LemonadeIcons
import com.teya.lemonade.core.SymbolContainerShape
import com.teya.lemonade.core.SymbolContainerSize
import com.teya.lemonade.core.SymbolContainerVoice
import com.teya.lemonade.core.TagVoice
import dev.loupe.platform.designsystem.bottomHairline
import dev.loupe.platform.localization.resolve
import dev.loupe.workspace.strings.Res
import dev.loupe.workspace.strings.diff_changes
import dev.loupe.workspace.strings.diff_refresh
import dev.loupe.workspace.strings.diff_vs
import dev.loupe.workspace.strings.file_added
import dev.loupe.workspace.strings.file_collapse
import dev.loupe.workspace.strings.file_comment_count
import dev.loupe.workspace.strings.file_deleted
import dev.loupe.workspace.strings.file_expand
import dev.loupe.workspace.strings.file_full
import dev.loupe.workspace.strings.file_modified
import dev.loupe.workspace.strings.file_preview
import dev.loupe.workspace.strings.file_renamed
import dev.loupe.workspace.ui.preview.FilePreviewBody
import dev.loupe.workspace.uimodel.DiffFileModel
import dev.loupe.workspace.uimodel.DiffReviewModel
import dev.loupe.workspace.uimodel.FileChangeKind
import dev.loupe.workspace.uimodel.WorkspaceEvent
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
    DiffToolbar(review = review, onEvent = onEvent)
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

@Composable
private fun DiffToolbar(
  review: DiffReviewModel,
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
      LemonadeUi.Tag(label = review.sourceLabel.resolve(), voice = TagVoice.Neutral)
      LemonadeUi.Text(text = stringResource(Res.string.diff_vs), textStyle = LemonadeTheme.typography.bodySmallRegular, color = secondary)
      LemonadeUi.Tag(label = review.targetLabel, voice = TagVoice.Neutral)
    }
    LemonadeUi.Text(
      text = review.summary.resolve(),
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
