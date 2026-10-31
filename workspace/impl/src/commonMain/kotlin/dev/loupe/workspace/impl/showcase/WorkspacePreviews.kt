package dev.loupe.workspace.impl.showcase

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.loupe.platform.designsystem.LoupeTheme
import dev.loupe.workspace.ui.toThemeMode

@Composable
private fun ShowcasePreview(showcase: WorkspaceShowcase) {
  LoupeTheme(mode = showcase.model().settings.theme.toThemeMode()) {
    Box(modifier = Modifier.size(width = showcase.widthDp.dp, height = 900.dp)) {
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
