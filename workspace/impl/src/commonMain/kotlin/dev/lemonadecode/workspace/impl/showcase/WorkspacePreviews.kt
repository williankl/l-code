package dev.lemonadecode.workspace.impl.showcase

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.workspace.ui.toThemeMode

@Composable
private fun ShowcasePreview(showcase: WorkspaceShowcase) {
  LemonadeCodeTheme(mode = showcase.model().settings.theme.toThemeMode()) {
    Box(modifier = Modifier.size(width = showcase.widthDp.dp, height = showcase.heightDp.dp)) {
      WorkspaceShowcaseContent(showcase)
    }
  }
}

@Preview
@Composable
private fun AgentFinishesPreview() = ShowcasePreview(WorkspaceShowcase.AgentFinishes)

@Preview
@Composable
private fun CommentOnDiffPreview() = ShowcasePreview(WorkspaceShowcase.CommentOnDiff)

@Preview
@Composable
private fun ReviewFullFilePreview() = ShowcasePreview(WorkspaceShowcase.ReviewFullFile)

@Preview
@Composable
private fun SentToSessionPreview() = ShowcasePreview(WorkspaceShowcase.SentToSession)

@Preview
@Composable
private fun NarrowDrawerPreview() = ShowcasePreview(WorkspaceShowcase.NarrowDrawer)

@Preview
@Composable
private fun SessionTreePreview() = ShowcasePreview(WorkspaceShowcase.SessionTree)

@Preview
@Composable
private fun AfterCreatingBothPreview() = ShowcasePreview(WorkspaceShowcase.AfterCreatingBoth)

@Preview
@Composable
private fun LightKeepAwakePreview() = ShowcasePreview(WorkspaceShowcase.LightKeepAwake)

@Preview
@Composable
private fun MultiLineCommentPreview() = ShowcasePreview(WorkspaceShowcase.MultiLineComment)

@Preview
@Composable
private fun SettingsListPreview() = ShowcasePreview(WorkspaceShowcase.SettingsList)

@Preview
@Composable
private fun SettingsLanguagePreview() = ShowcasePreview(WorkspaceShowcase.SettingsLanguage)

@Preview
@Composable
private fun SettingsPluginsPreview() = ShowcasePreview(WorkspaceShowcase.SettingsPlugins)

@Preview
@Composable
private fun SettingsUpdatesPreview() = ShowcasePreview(WorkspaceShowcase.SettingsUpdates)

@Preview
@Composable
private fun DeviceListPreview() = ShowcasePreview(WorkspaceShowcase.DeviceList)

@Preview
@Composable
private fun DeviceSideBySidePreview() = ShowcasePreview(WorkspaceShowcase.DeviceSideBySide)

@Preview
@Composable
private fun DeviceTabsPreview() = ShowcasePreview(WorkspaceShowcase.DeviceTabs)

@Preview
@Composable
private fun PairPlatformPreview() = ShowcasePreview(WorkspaceShowcase.PairPlatform)

@Preview
@Composable
private fun PairMethodPreview() = ShowcasePreview(WorkspaceShowcase.PairMethod)

@Preview
@Composable
private fun PairCodePreview() = ShowcasePreview(WorkspaceShowcase.PairCode)

@Preview
@Composable
private fun PairedPreview() = ShowcasePreview(WorkspaceShowcase.Paired)

@Preview
@Composable
private fun DisplayMainPreview() = ShowcasePreview(WorkspaceShowcase.DisplayMain)

@Preview
@Composable
private fun DisplayDevicesPreview() = ShowcasePreview(WorkspaceShowcase.DisplayDevices)

@Preview
@Composable
private fun DiffFileListPreview() = ShowcasePreview(WorkspaceShowcase.DiffFileList)

@Preview
@Composable
private fun DiffBasePickerPreview() = ShowcasePreview(WorkspaceShowcase.DiffBasePicker)

@Preview
@Composable
private fun DiffSoundsPreview() = ShowcasePreview(WorkspaceShowcase.DiffSounds)
