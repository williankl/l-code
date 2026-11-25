package dev.lemonadecode.mobile.impl

import dev.lemonadecode.domain.mobile.DeviceId
import dev.lemonadecode.domain.mobile.RecordingFailure
import dev.lemonadecode.domain.mobile.RecordingResolution
import dev.lemonadecode.domain.mobile.RecordingStop
import dev.lemonadecode.domain.mobile.ScreenRecording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ScreenRecorderTest {
  private val pixel = DeviceId("38021FDH2000AB")
  private val videos = FakeVideoFiles()

  private val parameterSets = nal(7, 0x42) + nal(8, 0xCE)

  /** The first picture of a stream: its parameter sets, then a key frame. */
  private val streamStart = parameterSets + picture(idr = true)

  private val files = FakeDeviceFiles()

  private fun TestScope.recorder(scope: CoroutineScope = backgroundScope) = ScreenRecorder(
    scope = scope,
    files = files,
    videos = videos,
    io = StandardTestDispatcher(testScheduler),
    timeSource = testScheduler.timeSource,
    clock = { 1_793_600_000_000 + testScheduler.currentTime },
  )

  /** [accessUnit] comes [after] the last thing that happened. */
  private fun TestScope.send(recorder: ScreenRecorder, accessUnit: ByteArray, after: Long = 0, width: Int = 576, height: Int = 1280) {
    advanceTimeBy(after.milliseconds)
    recorder.onPicture(pixel, width, height, accessUnit)
    runCurrent()
  }

  @Test
  fun `a recording opens on a stream's first picture, and each picture shows from when it came until the next`() = runTest {
    val recorder = recorder()
    // Not recorded yet: nothing is kept.
    send(recorder, streamStart)
    assertTrue(recorder.start(pixel, "Willian's Pixel"))
    assertEquals(ScreenRecording.Recording(1_793_600_000_000), recorder.recordings.value[pixel])
    assertFalse(recorder.start(pixel, "Willian's Pixel"), "it's being recorded already")

    // The stream in hand started before: its pictures can't be decoded on their own.
    send(recorder, picture(), after = 100)
    send(recorder, streamStart, after = 400)
    send(recorder, picture(), after = 100)
    // The screen held still for 3 s.
    send(recorder, picture(), after = 3_000)
    advanceTimeBy(1_500.milliseconds)
    val ending = recorder.stop(pixel)

    val saved = ScreenRecording.Saved(listOf("/home/me/Videos/Lemonade Code/Willian's Pixel-1.mp4"), durationMillis = 5_100)
    assertEquals(saved, ending)
    assertEquals(saved, recorder.recordings.value[pixel])
    val file = videos.opened.single()
    assertEquals(576 to 1280, file.width to file.height)
    assertContentEquals(parameterSets, file.parameterSets)
    assertEquals(listOf(Triple(7, 0L, true), Triple(1, 100L, false), Triple(1, 3_100L, false)), file.pictures)
    assertEquals(4_600L, file.finishedAt)
    assertNull(recorder.stop(pixel), "it's stopped already")
  }

  @Test
  fun `a stream that starts again carries on in the same file, and one at another size after a turn in a new one`() = runTest {
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8"))
    send(recorder, streamStart)
    send(recorder, picture(), after = 100)
    // screenrecord stopped after its few minutes and started again.
    send(recorder, streamStart, after = 200)
    // The screen turned: the stream started again on its side.
    send(recorder, streamStart, after = 300, width = 1280, height = 576)
    send(recorder, picture(), after = 100, width = 1280, height = 576)
    advanceTimeBy(200.milliseconds)
    val ending = recorder.stop(pixel)

    assertEquals(
      ScreenRecording.Saved(listOf("/home/me/Videos/Lemonade Code/Pixel 8-1.mp4", "/home/me/Videos/Lemonade Code/Pixel 8-2.mp4"), durationMillis = 900),
      ending,
    )
    val (portrait, landscape) = videos.opened
    assertEquals(listOf(Triple(7, 0L, true), Triple(1, 100L, false), Triple(7, 300L, true)), portrait.pictures)
    assertEquals(600L, portrait.finishedAt, "the portrait file ends as the screen turned")
    assertEquals(1280 to 576, landscape.width to landscape.height)
    assertEquals(listOf(Triple(7, 0L, true), Triple(1, 100L, false)), landscape.pictures)
    assertEquals(300L, landscape.finishedAt)
  }

  @Test
  fun `pictures that come together each get a moment of their own`() = runTest {
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8"))
    send(recorder, streamStart)
    send(recorder, picture())
    send(recorder, picture())
    val _ = recorder.stop(pixel)
    assertEquals(listOf(0L, 1L, 2L), videos.opened.single().pictures.map { it.second })
    assertEquals(3L, videos.opened.single().finishedAt)
  }

  @Test
  fun `a device that sends no video fails the recording after a while`() = runTest {
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8"))
    // Pictures of a stream that started earlier don't count.
    send(recorder, picture(), after = 1_000)
    advanceTimeBy(FirstVideoWait)
    runCurrent()

    val failed = ScreenRecording.Failed(RecordingFailure.NoVideo)
    assertEquals(failed, recorder.recordings.value[pixel])
    assertEquals(emptySet(), recorder.recording)
    assertEquals(emptyList(), videos.opened)
    assertNull(recorder.stop(pixel))
  }

  @Test
  fun `stopping before any video came saves nothing`() = runTest {
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8"))
    advanceTimeBy(1.seconds)
    assertEquals(ScreenRecording.Failed(RecordingFailure.NoVideo), recorder.stop(pixel))
    assertEquals(emptyList(), videos.opened)
  }

  @Test
  fun `a file that can't be written fails the recording, saying why`() = runTest {
    videos.failure = "No space left on device"
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8"))
    send(recorder, streamStart)

    assertEquals(ScreenRecording.Failed(RecordingFailure.Unwritable("No space left on device")), recorder.recordings.value[pixel])
    assertEquals(emptySet(), recorder.recording)
    assertNull(recorder.stop(pixel), "it ended by itself")
  }

  @Test
  fun `a file that fails as it's finished still answers the stop, with the file as far as it was written`() = runTest {
    videos.finishFailure = "Couldn't finish: the disk was ejected"
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8"))
    send(recorder, streamStart)
    send(recorder, picture(), after = 100)

    val ending = withTimeout(5.seconds) { recorder.stop(pixel) }
    val failed = ScreenRecording.Failed(
      RecordingFailure.Unwritable("Couldn't finish: the disk was ejected"),
      files = listOf("/home/me/Videos/Lemonade Code/Pixel 8-1.mp4"),
    )
    assertEquals(failed, ending)
    assertEquals(failed, recorder.recordings.value[pixel])
    assertEquals(emptySet(), recorder.recording)
  }

  @Test
  fun `a full disk fails the recording at once, keeping the file it was writing`() = runTest {
    videos.writeFailureAt = 3
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8"))
    send(recorder, streamStart)
    send(recorder, picture(), after = 100)
    send(recorder, picture(), after = 100)

    val failed = ScreenRecording.Failed(RecordingFailure.Unwritable("No space left on device"), listOf("/home/me/Videos/Lemonade Code/Pixel 8-1.mp4"))
    assertEquals(failed, recorder.recordings.value[pixel])
    assertEquals(emptySet(), recorder.recording, "nothing more is taken")
    assertEquals(2, videos.opened.single().pictures.size)
  }

  @Test
  fun `when the file after a turn can't be written, the whole one before it is still reported`() = runTest {
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8"))
    send(recorder, streamStart)
    send(recorder, picture(), after = 100)
    videos.failure = "No space left on device"
    send(recorder, streamStart, after = 300, width = 1280, height = 576)

    val failed = ScreenRecording.Failed(RecordingFailure.Unwritable("No space left on device"), listOf("/home/me/Videos/Lemonade Code/Pixel 8-1.mp4"))
    assertEquals(failed, recorder.recordings.value[pixel])
    assertEquals(400L, videos.opened.single().finishedAt)
  }

  @Test
  fun `a picture handed over as the stream went quiet, then found cut short, is taken back`() = runTest {
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8"))
    send(recorder, streamStart)
    send(recorder, picture(), after = 100)
    // Handed over when the stream went quiet, but the stream carried on in the middle of it.
    send(recorder, picture(), after = 100)
    recorder.onCutShort(pixel)
    runCurrent()
    // The stream starts again, whole.
    send(recorder, streamStart, after = 300)
    advanceTimeBy(100.milliseconds)
    val _ = recorder.stop(pixel)

    val file = videos.opened.single()
    assertEquals(listOf(Triple(7, 0L, true), Triple(1, 100L, false), Triple(7, 500L, true)), file.pictures)
    assertEquals(600L, file.finishedAt)
  }

  @Test
  fun `a stream's first picture cut short leaves nothing behind, and the file starts at the next one`() = runTest {
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8"))
    send(recorder, streamStart)
    recorder.onCutShort(pixel)
    runCurrent()
    send(recorder, streamStart, after = 300)
    // The screen turned: the next stream's first picture is cut short too, then it turned back.
    send(recorder, streamStart, after = 300, width = 1280, height = 576)
    recorder.onCutShort(pixel)
    runCurrent()
    send(recorder, streamStart, after = 300)
    advanceTimeBy(100.milliseconds)
    val ending = recorder.stop(pixel)

    val (first, empty, last) = videos.opened
    assertEquals(listOf(Triple(7, 0L, true)), first.pictures, "its time starts at the picture kept")
    assertEquals(300L, first.finishedAt)
    assertTrue(empty.deleted, "nothing was kept in the landscape file")
    assertEquals(listOf(Triple(7, 0L, true)), last.pictures)
    assertEquals(100L, last.finishedAt)
    assertEquals(listOf(first.path, last.path), (ending as ScreenRecording.Saved).files)
  }

  @Test
  fun `a device that went away keeps what was recorded, and an ended recording can be dismissed`() = runTest {
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8"))
    send(recorder, streamStart)
    advanceTimeBy(2.seconds)
    val ending = recorder.stop(pixel, RecordingStop.DeviceGone)
    assertEquals(ScreenRecording.Saved(listOf("/home/me/Videos/Lemonade Code/Pixel 8-1.mp4"), durationMillis = 2_000, stop = RecordingStop.DeviceGone), ending)

    recorder.dismiss(pixel)
    assertEquals(emptyMap(), recorder.recordings.value)
    assertTrue(recorder.start(pixel, "Pixel 8"))
    recorder.dismiss(pixel)
    assertEquals(setOf(pixel), recorder.recordings.value.keys, "one under way isn't dismissed")
  }

  @Test
  fun `a recording started while the last one is still being saved goes on saying it records`() = runTest {
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8"))
    send(recorder, streamStart)
    val stopping = async(start = CoroutineStart.UNDISPATCHED) { recorder.stop(pixel) }
    assertTrue(recorder.start(pixel, "Pixel 8"))
    runCurrent()

    assertEquals(listOf("/home/me/Videos/Lemonade Code/Pixel 8-1.mp4"), (stopping.await() as ScreenRecording.Saved).files)
    assertEquals(ScreenRecording.Recording(1_793_600_000_000), recorder.recordings.value[pixel])
    assertEquals(setOf(pixel), recorder.recording)
  }

  @Test
  fun `a recording at full resolution says so while it records, and its stream goes back when it ends`() = runTest {
    val recorder = recorder()
    val full = mutableListOf<Set<DeviceId>>()
    backgroundScope.launch { recorder.atFullResolution.collect { full += it } }
    runCurrent()
    assertTrue(recorder.start(pixel, "Pixel 8", RecordingResolution.Full))
    runCurrent()
    assertEquals(RecordingResolution.Full, recorder.resolutionOf(pixel))
    assertEquals(ScreenRecording.Recording(1_793_600_000_000, RecordingResolution.Full), recorder.recordings.value[pixel])
    send(recorder, streamStart, width = 1080, height = 2400)
    val _ = recorder.stop(pixel)
    runCurrent()
    assertEquals(null, recorder.resolutionOf(pixel))
    assertEquals(listOf(emptySet(), setOf(pixel), emptySet()), full)
    assertEquals(1080 to 2400, videos.opened.single().let { it.width to it.height })
  }

  @Test
  fun `a device that sends nothing at its full size has refused it`() = runTest {
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8", RecordingResolution.Full))
    advanceTimeBy(FirstVideoWait)
    runCurrent()
    assertEquals(ScreenRecording.Failed(RecordingFailure.FullResolutionRefused), recorder.recordings.value[pixel])
  }

  @Test
  fun `a full-size stream that won't start again after recording a while is trouble with the stream, not a refusal`() = runTest {
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8", RecordingResolution.Full))
    send(recorder, streamStart, width = 1080, height = 2400)
    // Minutes later the screen turns, and the landscape stream at its own size doesn't start.
    advanceTimeBy(2.minutes)
    recorder.onRefused(pixel)
    recorder.onStreamEnded(pixel)
    runCurrent()
    assertIs<ScreenRecording.Recording>(recorder.recordings.value[pixel])
    advanceTimeBy(StallLimit)
    runCurrent()
    assertEquals(ScreenRecording.Failed(RecordingFailure.VideoLost, listOf("/home/me/Videos/Lemonade Code/Pixel 8-1.mp4")), recorder.recordings.value[pixel])
  }

  @Test
  fun `a still screen sends nothing for minutes and the recording goes on`() = runTest {
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8"))
    send(recorder, streamStart)
    // screenrecord starts again every few minutes, within a second.
    advanceTimeBy(3.minutes)
    recorder.onStreamEnded(pixel)
    send(recorder, streamStart, after = 600)
    advanceTimeBy(5.minutes)
    runCurrent()
    assertIs<ScreenRecording.Recording>(recorder.recordings.value[pixel])
  }

  @Test
  fun `a stream that stays down stops the recording, keeping what it recorded`() = runTest {
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8"))
    send(recorder, streamStart)
    send(recorder, picture(), after = 100)
    advanceTimeBy(100.milliseconds)
    recorder.onStreamEnded(pixel)
    advanceTimeBy(StallLimit - 1.milliseconds)
    runCurrent()
    assertIs<ScreenRecording.Recording>(recorder.recordings.value[pixel], "a stream down for a moment is fine")
    advanceTimeBy(1.milliseconds)
    runCurrent()

    val failed = ScreenRecording.Failed(RecordingFailure.VideoLost, listOf("/home/me/Videos/Lemonade Code/Pixel 8-1.mp4"))
    assertEquals(failed, recorder.recordings.value[pixel])
    assertEquals(emptySet(), recorder.recording)
  }

  @Test
  fun `the mirror giving up on video stops the recording, keeping what it recorded`() = runTest {
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8"))
    send(recorder, streamStart)
    advanceTimeBy(2.seconds)
    recorder.onVideoLost(pixel)
    runCurrent()
    assertEquals(ScreenRecording.Failed(RecordingFailure.VideoLost, listOf("/home/me/Videos/Lemonade Code/Pixel 8-1.mp4")), recorder.recordings.value[pixel])
    assertEquals(2_000L, videos.opened.single().finishedAt)
  }

  @Test
  fun `pictures out of order stop the recording rather than scramble it, keeping what came before`() = runTest {
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8"))
    send(recorder, streamStart)
    send(recorder, picture(), after = 100)
    // A B-frame: shown before a picture that comes after it.
    send(recorder, nal(1, 0xA0, 0x84, 0x21), after = 100)

    assertEquals(ScreenRecording.Failed(RecordingFailure.UnsupportedVideo, listOf("/home/me/Videos/Lemonade Code/Pixel 8-1.mp4")), recorder.recordings.value[pixel])
    assertEquals(2, videos.opened.single().pictures.size, "the B-frame isn't written")
  }

  @Test
  fun `the disk running low is said while the recording goes on, and no longer once there's room`() = runTest {
    files.freeSpace = 1_500_000_000L
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8"))
    send(recorder, streamStart)
    assertEquals(ScreenRecording.Recording(1_793_600_000_000, spaceLeft = 1_500_000_000L), recorder.recordings.value[pixel])
    files.freeSpace = 40_000_000_000L
    advanceTimeBy(SpaceCheck)
    runCurrent()
    assertEquals(ScreenRecording.Recording(1_793_600_000_000), recorder.recordings.value[pixel])
    // Space that can't be told isn't low.
    files.freeSpace = null
    advanceTimeBy(SpaceCheck)
    runCurrent()
    assertEquals(ScreenRecording.Recording(1_793_600_000_000), recorder.recordings.value[pixel])
  }

  @Test
  fun `a write that fails before any picture reached the disk reports no file, and leaves none`() = runTest {
    videos.nothingReachesDisk = true
    videos.writeFailureAt = 2
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8"))
    send(recorder, streamStart)
    send(recorder, picture(), after = 100)

    assertEquals(ScreenRecording.Failed(RecordingFailure.Unwritable("No space left on device")), recorder.recordings.value[pixel])
    assertTrue(videos.opened.single().deleted)
  }

  @Test
  fun `stopped before a whole picture came, it says so rather than that no video came`() = runTest {
    val recorder = recorder()
    assertTrue(recorder.start(pixel, "Pixel 8"))
    send(recorder, streamStart)
    recorder.onCutShort(pixel)
    runCurrent()
    assertEquals(ScreenRecording.Failed(RecordingFailure.NoWholePicture), recorder.stop(pixel))
    assertTrue(videos.opened.single().deleted)
  }

  @Test
  fun `when the app closes, every stop waiting is let go, the recording is forgotten and an empty file deleted`() = runTest {
    val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
    val recorder = recorder(scope)
    assertTrue(recorder.start(pixel, "Pixel 8"))
    send(recorder, streamStart)
    recorder.onCutShort(pixel)
    runCurrent()
    val stopping = async(start = CoroutineStart.UNDISPATCHED) { runCatching { recorder.stop(pixel) } }
    scope.cancel()
    runCurrent()

    assertTrue(stopping.await().exceptionOrNull() is CancellationException, "the stop isn't left waiting")
    assertEquals(emptySet(), recorder.recording)
    assertTrue(videos.opened.single().deleted)
    // A stop asked for afterwards doesn't wait for anything.
    withTimeout(1.seconds) { val _ = recorder.stop(pixel) }
  }

  @Test
  fun `slice types are read from slice headers, past emulation prevention bytes`() {
    assertEquals(listOf(7), sliceTypes(picture(idr = true)))
    assertEquals(listOf(1), sliceTypes(nal(1, 0xA0, 0x84)))
    assertEquals(listOf(6), sliceTypes(nal(1, 0x9C, 0x84)))
    assertEquals(emptyList(), sliceTypes(nal(7, 0x42)))
    // first_mb_in_slice 65535, its zeros split by a 03 that isn't data; then slice_type 0 (P).
    assertEquals(listOf(0), sliceTypes(byteArrayOf(0, 0, 1, 0x41, 0, 0, 3, 0x80.toByte(), 0x00, 0x40)))
  }

  @Test
  fun `a stream's parameter sets and key frames are found in its access units`() {
    assertContentEquals(parameterSets, parameterSetsOf(streamStart))
    assertNull(parameterSetsOf(picture(idr = true)))
    assertTrue(startsStream(streamStart))
    assertFalse(startsStream(parameterSets + picture()))
    assertTrue(isKeyFrame(picture(idr = true)))
    assertFalse(isKeyFrame(picture()))
    // A three-byte start code is found as well as a four-byte one.
    assertEquals(listOf(7, 8, 5), nalTypes(byteArrayOf(0, 0, 1, 0x67, 0x42) + nal(8, 0xCE) + picture(idr = true)))
  }
}
