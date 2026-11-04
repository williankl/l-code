package dev.lemonadecode.mobile.impl

import app.cash.turbine.test
import dev.lemonadecode.domain.mobile.DeviceScreen
import dev.lemonadecode.domain.mobile.ScreenPicture
import dev.lemonadecode.domain.mobile.ScreenUnavailable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScreenStreamTest {
  private fun bgra(): ScreenPicture.Bgra = ScreenPicture.Bgra(ByteArray(4), 1, 1, 4)

  @Test
  fun `a screen is recorded smaller, in multiples of 8, its longer side at most 1280, keeping its shape`() {
    assertEquals(576 to 1280, recordingSize(1080, 2400))
    assertEquals(1280 to 576, recordingSize(2400, 1080))
    assertEquals(1280 to 800, recordingSize(2560, 1600))
    assertEquals(720 to 1280, recordingSize(720, 1280))
    // screenrecord keeps the screen's shape and pads the rest with black: within a pixel, no bars show.
    for ((width, height) in listOf(1080 to 2340, 1440 to 3088, 1344 to 2992, 475 to 1000, 3088 to 1440)) {
      val (recordedWidth, recordedHeight) = recordingSize(width, height)
      assertEquals(0, recordedWidth % 8)
      assertEquals(0, recordedHeight % 8)
      assertTrue(maxOf(recordedWidth, recordedHeight) <= 1280)
      assertEquals(width > height, recordedWidth > recordedHeight)
      val shortSide = minOf(recordedWidth, recordedHeight)
      val exact = maxOf(recordedWidth, recordedHeight) * minOf(width, height).toDouble() / maxOf(width, height)
      assertTrue(abs(shortSide - exact) < 1.0, "$width×$height recorded at $recordedWidth×$recordedHeight")
    }
  }

  @Test
  fun `the screen's size comes from the current display`() {
    val dump = "Display: mDisplayId=0\n    init=2560x1600 320dpi base=1280x800 240dpi cur=1280x800 app=1280x764 rng=800x728-1280x1208\n"
    assertEquals(1280 to 800, parseCurrentScreenSize(dump))
    assertEquals(null, parseCurrentScreenSize("no display here"))
  }

  @Test
  fun `the last picture before the stream goes quiet still shows`() = runTest {
    val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    val decoders = FakeScreenDecoders()
    decodeVideo(chunks.receiveAsFlow(), decoders).test {
      chunks.send(nal(7, 0x42) + nal(8, 0xCE) + picture(idr = true) + picture())
      assertEquals(1, awaitItem().pixels[0].toInt())
      // The second picture's end isn't in the stream; it's decoded once nothing more comes.
      assertEquals(2, awaitItem().pixels[0].toInt())
      chunks.close()
      awaitComplete()
    }
    assertEquals(1, decoders.closed)
  }

  @Test
  fun `a picture cut short by a pause ends the video, for a new recording to start whole`() = runTest {
    val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    val decoders = FakeScreenDecoders()
    val idr = picture(idr = true)
    decodeVideo(chunks.receiveAsFlow(), decoders).test {
      chunks.send(idr.copyOfRange(0, 6))
      // The pause gives the decoder the first part of the picture.
      assertEquals(1, awaitItem().pixels[0].toInt())
      chunks.send(idr.copyOfRange(6, idr.size) + picture())
      awaitComplete()
    }
    assertEquals(1, decoders.units.size)
    assertEquals(1, decoders.closed)
  }

  @Test
  fun `a screen plays as a video, and starts again at its new size when it turns`() = runTest {
    var size: Pair<Int, Int>? = 1080 to 2400
    val recordings = mutableListOf<Pair<Int, Int>>()
    val portrait = Channel<ScreenPicture.Bgra>(Channel.UNLIMITED)
    val landscape = Channel<ScreenPicture.Bgra>(Channel.UNLIMITED)
    mirrorScreens(
      timeSource = testScheduler.timeSource,
      size = { size },
      stream = { width, height ->
        recordings += width to height
        (if (width < height) portrait else landscape).receiveAsFlow()
      },
      capture = { error("no screenshots while recording works") },
    ).test {
      assertEquals(DeviceScreen.Loading, awaitItem())
      portrait.send(bgra())
      val frame = assertIs<DeviceScreen.Frame>(awaitItem())
      assertEquals(1080 to 2400, frame.width to frame.height)
      size = 2400 to 1080
      portrait.send(bgra())
      assertEquals(1080 to 2400, assertIs<DeviceScreen.Frame>(awaitItem()).let { it.width to it.height })
      landscape.send(bgra())
      // The turn is noticed within a second; the recording at the new size takes over.
      assertEquals(2400 to 1080, assertIs<DeviceScreen.Frame>(awaitItem()).let { it.width to it.height })
      cancelAndIgnoreRemainingEvents()
    }
    assertEquals(listOf(1080 to 2400, 2400 to 1080), recordings)
  }

  @Test
  fun `when recording gives nothing three times running, screenshots take over`() = runTest {
    var recordings = 0
    var screenshots = 0
    mirrorScreens(
      timeSource = testScheduler.timeSource,
      size = { 1080 to 2400 },
      stream = { _, _ ->
        recordings++
        emptyFlow()
      },
      capture = {
        screenshots++
        Capture.Picture(PngPicture(png(1080, 2400), 1080, 2400))
      },
    ).test {
      assertEquals(DeviceScreen.Loading, awaitItem())
      val frame = assertIs<DeviceScreen.Frame>(awaitItem())
      assertIs<ScreenPicture.Encoded>(frame.picture)
      assertIs<DeviceScreen.Frame>(awaitItem())
      cancelAndIgnoreRemainingEvents()
    }
    assertEquals(3, recordings)
    assertTrue(screenshots >= 2)
  }

  @Test
  fun `a recording that ends after giving pictures starts again straight away`() = runTest {
    var recordings = 0
    mirrorScreens(
      timeSource = testScheduler.timeSource,
      size = { 1080 to 2400 },
      stream = { _, _ ->
        recordings++
        flow { emit(bgra()) }
      },
      capture = { error("no screenshots while recording works") },
    ).test {
      assertEquals(DeviceScreen.Loading, awaitItem())
      repeat(5) { assertIs<DeviceScreen.Frame>(awaitItem()) }
      cancelAndIgnoreRemainingEvents()
    }
    assertTrue(recordings >= 5)
  }

  @Test
  fun `turning the screen from here is noticed at once, and a recording stopped by a turn isn't a failure`() = runTest {
    var size = 1080 to 2400
    val turned = Channel<Unit>(Channel.UNLIMITED)
    val recordings = mutableListOf<Pair<Int, Int>>()
    mirrorScreens(
      timeSource = testScheduler.timeSource,
      size = { size },
      stream = { width, height ->
        recordings += width to height
        // The portrait one never shows a picture: only the turn ends it.
        if (width < height) Channel<ScreenPicture.Bgra>().receiveAsFlow() else Channel<ScreenPicture.Bgra>(1).apply { trySend(bgra()) }.receiveAsFlow()
      },
      capture = { error("no screenshots while recording works") },
      turned = turned.receiveAsFlow(),
    ).test {
      assertEquals(DeviceScreen.Loading, awaitItem())
      testScheduler.advanceTimeBy(300)
      size = 2400 to 1080
      turned.send(Unit)
      testScheduler.advanceTimeBy(250)
      assertEquals(listOf(1080 to 2400, 2400 to 1080), recordings)
      assertEquals(2400 to 1080, assertIs<DeviceScreen.Frame>(awaitItem()).let { it.width to it.height })
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun `a recording that shows nothing for five seconds fails, and after three, recording is tried again later`() = runTest {
    var recordings = 0
    mirrorScreens(
      timeSource = testScheduler.timeSource,
      size = { 1080 to 2400 },
      stream = { _, _ ->
        recordings++
        Channel<ScreenPicture.Bgra>().receiveAsFlow()
      },
      capture = { Capture.Picture(PngPicture(png(1080, 2400), 1080, 2400)) },
    ).test {
      assertEquals(DeviceScreen.Loading, awaitItem())
      // Three silent recordings, then screenshots.
      assertIs<ScreenPicture.Encoded>(assertIs<DeviceScreen.Frame>(awaitItem()).picture)
      assertEquals(3, recordings)
      testScheduler.advanceTimeBy(RecordingAgainAfter.inWholeMilliseconds + 1_000)
      assertEquals(4, recordings)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun `a device that can't be recorded yet gets screenshots, and what it can't show is said once`() = runTest {
    mirrorScreens(
      timeSource = testScheduler.timeSource,
      size = { null },
      stream = { _, _ -> emptyFlow() },
      capture = { Capture.Unavailable(ScreenUnavailable.CableOnly, "over a cable only") },
    ).test {
      assertEquals(DeviceScreen.Loading, awaitItem())
      assertEquals(DeviceScreen.Unavailable(ScreenUnavailable.CableOnly), awaitItem())
      expectNoEvents()
      cancelAndIgnoreRemainingEvents()
    }
  }
}
