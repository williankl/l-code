package dev.lemonadecode.workspace.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.preview_image_info
import dev.lemonadecode.workspace.strings.preview_loading
import dev.lemonadecode.workspace.strings.preview_unreadable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * Frames are decoded at most this big: past the preview's own size at 2x, sharpness costs more than
 * it shows.
 */
private const val MaxFrameWidth = 1600
private const val MaxFrameHeight = 960

private sealed interface VideoLoad {
  data object Loading : VideoLoad

  class Failed(val reason: String) : VideoLoad

  class Ready(val source: MotionSource) : VideoLoad
}

/**
 * A video file: its first frame until it's played, then the video, looping and without sound, with
 * controls to pause and scrub. The first video of a run takes a moment while FFmpeg unpacks.
 */
@Composable
internal fun VideoPreview(bytes: ByteArray) {
  val load by produceState<VideoLoad>(VideoLoad.Loading, bytes) {
    value = withContext(Dispatchers.Default) {
      try {
        VideoLoad.Ready(openVideo(bytes, MaxFrameWidth, MaxFrameHeight))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        VideoLoad.Failed(e.message ?: e::class.simpleName.orEmpty())
      }
    }
  }
  when (val state = load) {
    VideoLoad.Loading -> PreviewMessage(stringResource(Res.string.preview_loading), loading = true)
    is VideoLoad.Failed -> PreviewMessage(stringResource(Res.string.preview_unreadable, state.reason))
    is VideoLoad.Ready -> VideoPlayer(state.source, bytes.size.toLong())
  }
}

@Composable
private fun VideoPlayer(
  source: MotionSource,
  sizeBytes: Long,
) {
  val state = rememberMotionState(source, autoplay = false)
  val duration = source.durationMicros
  val details = if (duration > 0) "${formatMediaTime(duration)} · ${formatBytes(sizeBytes)}" else formatBytes(sizeBytes)
  Picture(
    widthPx = source.width.toFloat(),
    heightPx = source.height.toFloat(),
    info = stringResource(Res.string.preview_image_info, source.width, source.height, details),
    pixelatedWhenSmall = false,
    checkerboard = false,
    controls = {
      val position = formatMediaTime(state.positionMicros)
      MotionControls(state, position = if (duration > 0) "$position / ${formatMediaTime(duration)}" else position)
    },
  ) { modifier, _ ->
    MotionFrameView(state, modifier)
  }
}
