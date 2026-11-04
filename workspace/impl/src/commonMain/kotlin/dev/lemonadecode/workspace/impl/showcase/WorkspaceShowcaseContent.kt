package dev.lemonadecode.workspace.impl.showcase

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.lemonadecode.platform.localization.ProvideAppLanguage
import dev.lemonadecode.workspace.ui.DevicesWindowContent
import dev.lemonadecode.workspace.ui.TerminalTranscript
import dev.lemonadecode.workspace.ui.WorkspaceContent
import dev.lemonadecode.workspace.ui.preview.ShowcaseAudio
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent

/**
 * Draws [showcase] with transcripts in place of live terminals, and sound previews read at once,
 * in the language its settings pick. Theme is up to the caller.
 */
@Composable
public fun WorkspaceShowcaseContent(
  showcase: WorkspaceShowcase,
  modifier: Modifier = Modifier,
  onEvent: (WorkspaceEvent) -> Unit = {},
) {
  val model = remember(showcase, onEvent) { showcase.model(onEvent) }
  ProvideAppLanguage(model.settings.language.tag) {
    ShowcaseAudio {
      when (showcase.part) {
        ShowcasePart.Window -> WorkspaceContent(
          model = model,
          terminal = { sessionId, slotModifier -> TerminalTranscript(showcase.transcript(sessionId), slotModifier) },
          modifier = modifier,
        )
        ShowcasePart.Devices -> model.devices?.let { panel -> DevicesWindowContent(panel = panel, onEvent = model.eventSink, modifier = modifier) }
      }
    }
  }
}
