package dev.lemonadecode.workspace.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.platform.localization.asLemonadeCodeString
import dev.lemonadecode.workspace.impl.showcase.WorkspaceShowcase
import dev.lemonadecode.workspace.ui.DeviceWindowContent
import dev.lemonadecode.workspace.ui.TerminalTranscript
import dev.lemonadecode.workspace.ui.WorkspaceContent
import dev.lemonadecode.workspace.uimodel.DevicesContentModel
import dev.lemonadecode.workspace.uimodel.RecordingResolutionModel
import dev.lemonadecode.workspace.uimodel.SavedRecordingModel
import dev.lemonadecode.workspace.uimodel.ScreenRecordingModel
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import dev.lemonadecode.workspace.uimodel.WorkspaceModel
import kotlin.test.Test
import kotlin.test.assertEquals

/** The record button under a mirrored screen, and the line saying where its video went. */
@OptIn(ExperimentalTestApi::class)
class ScreenRecordingUiTest {
  private val events = mutableListOf<WorkspaceEvent>()
  private val opened = mutableListOf<String>()

  /** [showcase] with its Model changed by [edit]; links open into [opened]. */
  private fun show(
    showcase: WorkspaceShowcase,
    edit: (WorkspaceModel) -> WorkspaceModel = { it },
    block: DesktopComposeUiTest.() -> Unit,
  ) = runDesktopComposeUiTest(width = showcase.widthDp, height = showcase.heightDp) {
    val uriHandler = object : UriHandler {
      override fun openUri(uri: String) {
        opened += uri
      }
    }
    setContent {
      LemonadeCodeTheme {
        CompositionLocalProvider(LocalUriHandler provides uriHandler) {
          WorkspaceContent(
            model = edit(showcase.model { events += it }),
            terminal = { id, modifier -> TerminalTranscript(showcase.transcript(id), modifier) },
          )
        }
      }
    }
    waitForIdle()
    block()
  }

  private fun DesktopComposeUiTest.expect(event: WorkspaceEvent, action: () -> Unit) {
    events.clear()
    action()
    waitForIdle()
    assertEquals(listOf(event), events)
  }

  @Test
  fun `the red dot records the screen, and while it records it stops it, with the time beside the screen`() {
    show(WorkspaceShowcase.DeviceSideBySide) {
      expect(WorkspaceEvent.ToggleScreenRecording) { onNodeWithContentDescription("Record the screen").performClick() }
    }
    show(WorkspaceShowcase.DeviceRecording) {
      onNodeWithText("● 0:42", substring = true).assertExists()
      expect(WorkspaceEvent.ToggleScreenRecording) { onNodeWithContentDescription("Stop recording").performClick() }
    }
  }

  @Test
  fun `the size to record at is picked from above the button`() = show(WorkspaceShowcase.DeviceRecordingChoice) {
    onNodeWithText("1080 × 2400").assertExists()
    onNodeWithText("576 × 1280").assertExists()
    expect(WorkspaceEvent.StartScreenRecording(RecordingResolutionModel.Full)) { onNodeWithText("Full resolution").performClick() }
    expect(WorkspaceEvent.StartScreenRecording(RecordingResolutionModel.Mirror)) { onNodeWithText("Mirror size").performClick() }
  }

  @Test
  fun `a disk running low is said under the screen while it records`() = show(WorkspaceShowcase.DeviceLowDiskWarning) {
    onNodeWithText("Disk space is low: 1 GB left. The recording goes on.").assertExists()
    onNodeWithContentDescription("Stop recording").assertExists()
  }

  @Test
  fun `a recording that went wrong says why under the screen, and finds what it kept`() = show(
    WorkspaceShowcase.DeviceSideBySide,
    edit = { model ->
      val page = model.devices?.content as DevicesContentModel.Device
      val failed = ScreenRecordingModel(
        problem = "Pixel 8 stopped sending video, so the recording stopped.".asLemonadeCodeString(),
        saved = SavedRecordingModel("Part of the recording saved".asLemonadeCodeString(), "/Users/me/Movies/Lemonade Code/a.mp4", "file:///Users/me/Movies/Lemonade%20Code/a.mp4"),
      )
      model.copy(devices = model.devices?.copy(content = page.copy(recording = failed)))
    },
  ) {
    onNodeWithText("Pixel 8 stopped sending video, so the recording stopped.").assertExists()
    onNodeWithText("Show in folder").performClick()
    waitForIdle()
    assertEquals(listOf("file:///Users/me/Movies/Lemonade%20Code/a.mp4"), opened)
    expect(WorkspaceEvent.DismissSavedRecording) { onNodeWithContentDescription("Dismiss").performClick() }
  }

  @Test
  fun `a device whose recording's disk runs low says so in the list`() = show(WorkspaceShowcase.RecordingLowDiskList) {
    onNodeWithContentDescription("Stop recording 12:34. Disk space is low: 1 GB left. The recording goes on.").assertExists()
    onNodeWithText("Disk space is low: 1 GB left. The recording of Pixel 8 goes on.").assertExists()
  }

  @Test
  fun `a screen that comes as screenshots can't be recorded`() = show(
    WorkspaceShowcase.DeviceSideBySide,
    edit = { model ->
      val page = model.devices?.content as DevicesContentModel.Device
      val off = ScreenRecordingModel(unavailable = "Recording needs the screen as video.".asLemonadeCodeString())
      model.copy(devices = model.devices?.copy(content = page.copy(recording = off)))
    },
  ) {
    onNodeWithContentDescription("Record the screen").assertIsNotEnabled()
    events.clear()
    onNodeWithContentDescription("Record the screen").performClick()
    waitForIdle()
    assertEquals(emptyList(), events)
  }

  @Test
  fun `a saved recording shows in its folder, and its line can be dismissed`() = show(WorkspaceShowcase.DeviceRecordingSaved) {
    onNodeWithText("0:42 video saved").assertExists()
    onNodeWithText("Show in folder").performClick()
    waitForIdle()
    assertEquals(listOf("file:///Users/me/Movies/Lemonade%20Code/Pixel-8-20261125-101403.mp4"), opened)
    expect(WorkspaceEvent.DismissSavedRecording) { onNodeWithContentDescription("Dismiss").performClick() }
  }

  @Test
  fun `a device's own window records its own device`() = runDesktopComposeUiTest(width = WorkspaceShowcase.DeviceWindow.widthDp, height = WorkspaceShowcase.DeviceWindow.heightDp) {
    val model = WorkspaceShowcase.DeviceWindow.model { events += it }
    setContent {
      LemonadeCodeTheme { DeviceWindowContent(window = model.deviceWindows.first(), onEvent = model.eventSink) }
    }
    waitForIdle()
    expect(WorkspaceEvent.InDeviceWindow("38021FDH2000AB", WorkspaceEvent.ToggleScreenRecording)) { onNodeWithContentDescription("Record the screen").performClick() }
  }
}
