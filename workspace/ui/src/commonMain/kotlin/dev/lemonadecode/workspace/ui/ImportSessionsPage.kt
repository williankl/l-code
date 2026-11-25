package dev.lemonadecode.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.teya.lemonade.Button
import com.teya.lemonade.Checkbox
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.Link
import com.teya.lemonade.Spinner
import com.teya.lemonade.Text
import com.teya.lemonade.core.CheckboxStatus
import com.teya.lemonade.core.LemonadeAssetSize
import com.teya.lemonade.core.LemonadeButtonSize
import com.teya.lemonade.core.LemonadeButtonType
import com.teya.lemonade.core.LemonadeButtonVariant
import com.teya.lemonade.core.LemonadeIcons
import com.teya.lemonade.core.LemonadeLinkSize
import dev.lemonadecode.platform.designsystem.DotLabel
import dev.lemonadecode.platform.localization.resolve
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.import_busy
import dev.lemonadecode.workspace.strings.import_button
import dev.lemonadecode.workspace.strings.import_intro
import dev.lemonadecode.workspace.strings.import_looking
import dev.lemonadecode.workspace.strings.import_select_all
import dev.lemonadecode.workspace.strings.import_select_none
import dev.lemonadecode.workspace.strings.settings_import
import dev.lemonadecode.workspace.uimodel.ImportRowModel
import dev.lemonadecode.workspace.uimodel.ImportSessionsModel
import dev.lemonadecode.workspace.uimodel.ImportSourceModel
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The list scrolls past this, so a long one leaves the Import button on screen. */
private val ImportListMaxHeight = 440.dp

/**
 * Settings › Import sessions: each tool Lemonade Code can import from, with the sessions it has to
 * tick, or why there are none, and a button to bring the ticked ones over.
 */
@Composable
internal fun ImportSessionsPage(
  imports: ImportSessionsModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val colors = LemonadeTheme.colors
  val spaces = LemonadeTheme.spaces
  LemonadeUi.Text(
    text = stringResource(Res.string.import_intro),
    textStyle = LemonadeTheme.typography.bodySmallRegular,
    color = colors.content.contentSecondary,
  )
  if (imports.loading) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spaces.spacing200)) {
      LemonadeUi.Spinner(size = LemonadeAssetSize.XSmall, tint = colors.content.contentSecondary)
      LemonadeUi.Text(text = stringResource(Res.string.import_looking), textStyle = LemonadeTheme.typography.bodyXSmallMedium, color = colors.content.contentSecondary)
    }
  } else {
    Column(
      verticalArrangement = Arrangement.spacedBy(spaces.spacing300),
      modifier = Modifier.heightIn(max = ImportListMaxHeight).verticalScroll(rememberScrollState()),
    ) {
      imports.sources.forEach { source -> ImportSource(source = source, onEvent = onEvent) }
    }
  }
  LemonadeUi.Button(
    label = when {
      imports.importing -> stringResource(Res.string.import_busy)
      imports.tickedCount > 0 -> pluralStringResource(Res.plurals.import_button, imports.tickedCount, imports.tickedCount)
      else -> stringResource(Res.string.settings_import)
    },
    onClick = { onEvent(WorkspaceEvent.ImportSessions) },
    leadingIcon = LemonadeIcons.LogIn,
    variant = LemonadeButtonVariant.Primary,
    type = LemonadeButtonType.Solid,
    size = LemonadeButtonSize.Small,
    enabled = imports.canImport,
    modifier = Modifier.fillMaxWidth(),
  )
}

/** One tool: its name, what it has or why it has nothing, and its sessions. */
@Composable
private fun ImportSource(
  source: ImportSourceModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val colors = LemonadeTheme.colors
  val spaces = LemonadeTheme.spaces
  Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing100)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spaces.spacing200)) {
      Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing50), modifier = Modifier.weight(1f)) {
        LemonadeUi.Text(text = source.name, textStyle = LemonadeTheme.typography.bodySmallSemiBold)
        LemonadeUi.Text(
          text = source.status.resolve(),
          textStyle = LemonadeTheme.typography.bodyXSmallRegular,
          color = if (source.problem) colors.content.contentCritical else colors.content.contentSecondary,
        )
        source.detail?.let { detail ->
          LemonadeUi.Text(text = detail, textStyle = LemonadeTheme.typography.bodyXSmallRegular, color = colors.content.contentTertiary)
        }
      }
      if (source.canTick) {
        LemonadeUi.Link(
          text = stringResource(if (source.allTicked) Res.string.import_select_none else Res.string.import_select_all),
          onClick = { onEvent(WorkspaceEvent.TickAllImports(source.id, ticked = !source.allTicked)) },
          size = LemonadeLinkSize.Small,
        )
      }
    }
    source.notes.forEach { note ->
      DotLabel(text = note.resolve(), dotColor = colors.background.bgCaution, textColor = colors.content.contentCaution)
    }
    if (source.rows.isNotEmpty()) {
      Column {
        source.rows.forEach { row -> ImportRow(row = row, onToggle = { onEvent(WorkspaceEvent.ToggleImportSession(row.key)) }) }
      }
    }
  }
}

/** A session to tick: its name, its folder, and what comes with it or why it can't come. */
@Composable
private fun ImportRow(
  row: ImportRowModel,
  onToggle: () -> Unit,
) {
  val colors = LemonadeTheme.colors
  val spaces = LemonadeTheme.spaces
  val interactionSource = remember { MutableInteractionSource() }
  val hovered by interactionSource.collectIsHoveredAsState()
  Row(
    horizontalArrangement = Arrangement.spacedBy(spaces.spacing300),
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = if (row.nested) spaces.spacing600 else 0.dp)
      .clip(LemonadeTheme.shapes.radius300)
      .background(if (hovered && row.enabled) colors.background.bgElevated else Color.Transparent)
      .hoverable(interactionSource)
      .toggleable(value = row.ticked, enabled = row.enabled, role = Role.Checkbox, interactionSource = interactionSource, indication = null, onValueChange = { onToggle() })
      .padding(horizontal = spaces.spacing200, vertical = spaces.spacing200),
  ) {
    LemonadeUi.Checkbox(
      status = if (row.ticked) CheckboxStatus.Checked else CheckboxStatus.Unchecked,
      onCheckboxClicked = onToggle,
      enabled = row.enabled,
      modifier = Modifier.padding(top = 2.dp),
    )
    Column(
      verticalArrangement = Arrangement.spacedBy(2.dp),
      modifier = Modifier.weight(1f).alpha(if (row.enabled) 1f else LemonadeTheme.opacities.state.opacityDisabled),
    ) {
      LemonadeUi.Text(text = row.name, textStyle = LemonadeTheme.typography.bodySmallMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      LemonadeUi.Text(
        text = row.folder,
        textStyle = LemonadeTheme.typography.bodyXSmallRegular,
        color = colors.content.contentSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      row.note?.let { note ->
        LemonadeUi.Text(
          text = note.resolve(),
          textStyle = LemonadeTheme.typography.bodyXSmallMedium,
          color = if (row.noteIsProblem) colors.content.contentCritical else colors.content.contentTertiary,
        )
      }
    }
  }
}
