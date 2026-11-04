package dev.lemonadecode.workspace.ui.preview

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.workspace.uimodel.DiffFileModel
import dev.lemonadecode.workspace.uimodel.FileChangeKind
import dev.lemonadecode.workspace.uimodel.FilePreviewContentModel
import dev.lemonadecode.workspace.uimodel.FilePreviewModel
import dev.lemonadecode.workspace.uimodel.PreviewKindModel
import dev.lemonadecode.workspace.uimodel.PreviewLayout
import dev.lemonadecode.workspace.uimodel.PreviewSideModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals

/** The sound preview in a real composition: what it shows, and what playing and pausing ask for. */
@OptIn(ExperimentalTestApi::class)
class AudioPreviewUiTest {
  private val engine = RecordingEngine()

  /** Sound [id], [seconds] long, as the stand-in engine reads it. */
  private fun sound(id: Int, seconds: Int = 2): ByteArray = byteArrayOf(id.toByte(), seconds.toByte())

  private fun DesktopComposeUiTest.show(
    engine: AudioEngine = this@AudioPreviewUiTest.engine,
    content: @androidx.compose.runtime.Composable () -> Unit,
  ) = setContent {
    LemonadeCodeTheme { CompositionLocalProvider(LocalAudioEngine provides engine, content = content) }
  }

  private fun DesktopComposeUiTest.awaitDescribed(label: String, count: Int = 1) = waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription(label).fetchSemanticsNodes().size == count }

  private fun DesktopComposeUiTest.awaitText(text: String) = waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

  /** Waits for [calls] to be all that was asked of the engine, and says what was if they never are. */
  private fun DesktopComposeUiTest.awaitCalls(vararg calls: String) {
    val _ = runCatching { waitUntil(timeoutMillis = 5_000) { engine.calls.toList() == calls.toList() } }
    assertEquals(calls.toList(), engine.calls.toList())
  }

  @Test
  fun `a sound shows its waveform and what it is, and plays and pauses`() = runDesktopComposeUiTest(width = 600, height = 400) {
    show { AudioPreview(sound(1)) }
    awaitDescribed("Play")
    onNodeWithContentDescription("Waveform").assertExists()
    onNode(hasText("0:00.0 / 0:02.0")).assertExists()
    onNode(hasText("2.0 s · MP3 · 44.1 kHz · Stereo · 192 kb/s · 2 B")).assertExists()

    onNodeWithContentDescription("Play").performClick()
    awaitDescribed("Pause")
    awaitText("0:00.5 / 0:02.0")
    awaitCalls("play 1 from 0")

    onNodeWithContentDescription("Pause").performClick()
    awaitDescribed("Play")
    awaitCalls("play 1 from 0", "stop 1")
  }

  @Test
  fun `scrubbing while paused only moves, and playing carries on from there`() = runDesktopComposeUiTest(width = 600, height = 400) {
    show { AudioPreview(sound(1)) }
    awaitDescribed("Play")
    onNodeWithContentDescription("Position").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
    awaitText("0:01.0 / 0:02.0")
    assertEquals(emptyList(), engine.calls.toList())

    onNodeWithContentDescription("Play").performClick()
    awaitCalls("play 1 from 1000000")
  }

  @Test
  fun `played to the end, a sound stops there and starts over next time`() = runDesktopComposeUiTest(width = 600, height = 400) {
    val toTheEnd = RecordingEngine(endsAt = 2_000_000)
    show(toTheEnd) { AudioPreview(sound(1)) }
    awaitDescribed("Play")
    onNodeWithContentDescription("Play").performClick()
    awaitText("0:02.0 / 0:02.0")
    awaitDescribed("Play")
    onNodeWithContentDescription("Play").performClick()
    waitUntil(timeoutMillis = 5_000) { toTheEnd.calls.toList() == listOf("play 1 from 0", "play 1 from 0") }
  }

  @Test
  fun `only one side plays at a time`() = runDesktopComposeUiTest(width = 900, height = 400) {
    val file = DiffFileModel(
      id = "sounds/ding.wav", directory = "sounds/", fileName = "ding.wav", path = "sounds/ding.wav", change = FileChangeKind.Modified,
      language = "WAV", additions = 0, deletions = 0, commentCount = 0, expanded = true, showingFullFile = false, canShowFullFile = false,
      rows = emptyList(),
    )
    val preview = FilePreviewModel(
      kind = PreviewKindModel.Audio,
      showing = true,
      layout = PreviewLayout.SideBySide,
      content = FilePreviewContentModel.Ready(PreviewSideModel.Content(sound(1), "wav"), PreviewSideModel.Content(sound(2), "wav")),
    )
    show { FilePreviewBody(file = file, preview = preview, onEvent = {}) }
    awaitDescribed("Play", count = 2)

    onAllNodesWithContentDescription("Play")[0].performClick()
    awaitCalls("play 1 from 0")
    // The before side now shows Pause, so the only Play left is the after side's.
    onNodeWithContentDescription("Play").performClick()
    awaitCalls("play 1 from 0", "stop 1", "play 2 from 0")
    awaitDescribed("Pause", count = 1)
  }

  @Test
  fun `a sound stops when its preview leaves the screen`() = runDesktopComposeUiTest(width = 600, height = 400) {
    var shown by mutableStateOf(true)
    show { if (shown) AudioPreview(sound(1)) }
    awaitDescribed("Play")
    onNodeWithContentDescription("Play").performClick()
    awaitCalls("play 1 from 0")

    shown = false
    awaitCalls("play 1 from 0", "stop 1")
  }

  @Test
  fun `with no sound output, it says it can't play here and the button does nothing`() = runDesktopComposeUiTest(width = 600, height = 400) {
    show(RecordingEngine(output = false)) { AudioPreview(sound(1)) }
    awaitText("Can’t play here: there’s no sound output.")
    onNodeWithContentDescription("Play").assertIsNotEnabled()
    onNode(hasText("2.0 s · MP3 · 44.1 kHz · Stereo · 192 kb/s · 2 B")).assertExists()
  }

  @Test
  fun `when the sound output turns out to be missing, it says so instead of failing`() = runDesktopComposeUiTest(width = 600, height = 400) {
    show(RecordingEngine(failure = AudioUnavailableException("no line"))) { AudioPreview(sound(1)) }
    awaitDescribed("Play")
    onNodeWithContentDescription("Play").performClick()
    awaitText("Can’t play here: there’s no sound output.")
    onNodeWithContentDescription("Play").assertIsNotEnabled()
  }

  @Test
  fun `FFmpeg reads a real WAV file for the player`() = runDesktopComposeUiTest(width = 600, height = 400) {
    val wav = SoundFiles.wav(SoundFiles.tone(1.0, 8_000, 1), 8_000, 1)
    // The platform's engine, but nothing is played: a test shouldn't make a sound.
    setContent { LemonadeCodeTheme { AudioPreview(wav) } }
    waitUntil(timeoutMillis = 30_000) { onAllNodes(hasContentDescription("Play")).fetchSemanticsNodes().isNotEmpty() }
    onNode(hasText("1.0 s · PCM · 8 kHz · 16-bit · Mono · 128 kb/s · ${formatBytes(wav.size.toLong())}")).assertExists()
    onNode(hasText("0:00.0 / 0:01.0")).assertExists()
  }

  @Test
  fun `bytes that aren't a sound say so`() = runDesktopComposeUiTest(width = 600, height = 300) {
    setContent { LemonadeCodeTheme { AudioPreview(ByteArray(64) { it.toByte() }) } }
    waitUntil(timeoutMillis = 30_000) {
      onAllNodes(hasText("Couldn’t draw this file: it isn't a sound FFmpeg can read")).fetchSemanticsNodes().isNotEmpty()
    }
  }

  /**
   * Reads byte 0 as the sound's id and byte 1 as its length in seconds, and writes down what's
   * played. Playing moves half a second in, then waits to be stopped, or ends at [endsAt].
   */
  private class RecordingEngine(
    private val output: Boolean = true,
    private val failure: Exception? = null,
    private val endsAt: Long? = null,
  ) : AudioEngine {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override suspend fun outputAvailable(): Boolean = output

    override suspend fun read(bytes: ByteArray): AudioClip = AudioClip(
      durationMicros = bytes[1] * 1_000_000L,
      sampleRate = 44_100,
      channels = 2,
      codec = "MP3",
      bitsPerSample = null,
      bitrate = 192_000,
      waveform = Waveform(FloatArray(64) { it / 64f }, FloatArray(64) { it / 128f }),
    )

    override fun play(bytes: ByteArray, fromMicros: Long): Flow<Long> = flow {
      calls += "play ${bytes[0]} from $fromMicros"
      failure?.let { throw it }
      emit(fromMicros)
      if (endsAt != null) {
        emit(endsAt)
        return@flow
      }
      emit(fromMicros + 500_000)
      try {
        awaitCancellation()
      } finally {
        calls += "stop ${bytes[0]}"
      }
    }
  }
}
