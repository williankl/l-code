package dev.lemonadecode.workspace.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.SegmentedControl
import com.teya.lemonade.Spinner
import com.teya.lemonade.Text
import com.teya.lemonade.core.LemonadeAssetSize
import com.teya.lemonade.core.LemonadeSegmentedControlSize
import com.teya.lemonade.core.TabButtonProperties
import dev.lemonadecode.platform.localization.resolve
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.preview_after
import dev.lemonadecode.workspace.strings.preview_before
import dev.lemonadecode.workspace.strings.preview_empty
import dev.lemonadecode.workspace.strings.preview_loading
import dev.lemonadecode.workspace.strings.preview_side_by_side
import dev.lemonadecode.workspace.strings.preview_too_large
import dev.lemonadecode.workspace.uimodel.DiffFileModel
import dev.lemonadecode.workspace.uimodel.FilePreviewContentModel
import dev.lemonadecode.workspace.uimodel.FilePreviewModel
import dev.lemonadecode.workspace.uimodel.PreviewKindModel
import dev.lemonadecode.workspace.uimodel.PreviewLayout
import dev.lemonadecode.workspace.uimodel.PreviewSideModel
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import org.jetbrains.compose.resources.stringResource

private val Layouts = listOf(PreviewLayout.Before, PreviewLayout.After, PreviewLayout.SideBySide)

/** A file drawn as what it is, in place of its diff rows. */
@Composable
internal fun FilePreviewBody(
  file: DiffFileModel,
  preview: FilePreviewModel,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val radius = LemonadeTheme.radius.radius500
  val spaces = LemonadeTheme.spaces
  Column(
    verticalArrangement = Arrangement.spacedBy(spaces.spacing300),
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(bottomStart = radius, bottomEnd = radius))
      .background(LemonadeTheme.colors.background.bgSubtle)
      .padding(spaces.spacing400),
  ) {
    preview.layout?.let { layout ->
      LayoutSwitcher(layout = layout, onSelect = { onEvent(WorkspaceEvent.SetPreviewLayout(file.id, it)) })
    }
    when (val content = preview.content) {
      FilePreviewContentModel.Loading -> PreviewMessage(stringResource(Res.string.preview_loading), loading = true)
      is FilePreviewContentModel.Failed -> PreviewMessage(content.message.resolve())
      is FilePreviewContentModel.Ready -> when (preview.layout) {
        // Added and deleted files only have one side.
        null -> PreviewSide(content.after ?: content.before, preview.kind)
        PreviewLayout.Before -> PreviewSide(content.before, preview.kind)
        PreviewLayout.After -> PreviewSide(content.after, preview.kind)
        PreviewLayout.SideBySide -> Row(horizontalArrangement = Arrangement.spacedBy(spaces.spacing400)) {
          LabelledSide(stringResource(Res.string.preview_before), content.before, preview.kind, Modifier.weight(1f))
          LabelledSide(stringResource(Res.string.preview_after), content.after, preview.kind, Modifier.weight(1f))
        }
      }
    }
  }
}

@Composable
private fun LayoutSwitcher(
  layout: PreviewLayout,
  onSelect: (PreviewLayout) -> Unit,
) {
  val labels = listOf(
    stringResource(Res.string.preview_before),
    stringResource(Res.string.preview_after),
    stringResource(Res.string.preview_side_by_side),
  )
  LemonadeUi.SegmentedControl(
    labels.map { TabButtonProperties.label(it) },
    Layouts.indexOf(layout),
    { index -> onSelect(Layouts[index]) },
    LemonadeSegmentedControlSize.Small,
  )
}

@Composable
private fun LabelledSide(
  label: String,
  side: PreviewSideModel?,
  kind: PreviewKindModel,
  modifier: Modifier = Modifier,
) {
  Column(verticalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing200), modifier = modifier) {
    LemonadeUi.Text(
      text = label,
      textStyle = LemonadeTheme.typography.bodyXSmallSemiBold,
      color = LemonadeTheme.colors.content.contentSecondary,
    )
    PreviewSide(side, kind)
  }
}

@Composable
private fun PreviewSide(
  side: PreviewSideModel?,
  kind: PreviewKindModel,
) {
  when (side) {
    // The file doesn't exist on this side.
    null -> PreviewMessage("—")
    is PreviewSideModel.TooLarge -> PreviewMessage(stringResource(Res.string.preview_too_large, formatBytes(side.sizeBytes)))
    is PreviewSideModel.Content -> if (side.bytes.isEmpty()) {
      PreviewMessage(stringResource(Res.string.preview_empty))
    } else {
      when (kind) {
        PreviewKindModel.Image -> ImagePreview(side.bytes)
        PreviewKindModel.Vector -> SvgPreview(side.bytes)
        PreviewKindModel.Model -> ModelPreview(side.bytes, side.extension)
        PreviewKindModel.Markdown -> MarkdownPreview(side.bytes)
        PreviewKindModel.Table -> TablePreview(side.bytes, side.extension)
      }
    }
  }
}

/** A short centred line for states with nothing to draw. */
@Composable
internal fun PreviewMessage(
  text: String,
  loading: Boolean = false,
) {
  val secondary = LemonadeTheme.colors.content.contentSecondary
  Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing200),
    ) {
      if (loading) LemonadeUi.Spinner(LemonadeAssetSize.Small, secondary)
      LemonadeUi.Text(text = text, textStyle = LemonadeTheme.typography.bodySmallRegular, color = secondary)
    }
  }
}

/** Sizes the way people read them: 812 B, 14.2 KB, 3.1 MB. */
internal fun formatBytes(bytes: Long): String {
  if (bytes < 1024) return "$bytes B"
  val units = listOf("KB", "MB", "GB")
  var value = bytes / 1024.0
  var unit = 0
  while (value >= 1024 && unit < units.lastIndex) {
    value /= 1024
    unit++
  }
  val tenths = (value * 10).toLong()
  val text = if (tenths % 10 == 0L || value >= 100) "${(value + 0.5).toLong()}" else "${tenths / 10}.${tenths % 10}"
  return "$text ${units[unit]}"
}
