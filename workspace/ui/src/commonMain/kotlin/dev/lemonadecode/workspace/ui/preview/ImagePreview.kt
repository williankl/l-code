package dev.lemonadecode.workspace.ui.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.Text
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.preview_frames
import dev.lemonadecode.workspace.strings.preview_image_info
import dev.lemonadecode.workspace.strings.preview_loading
import dev.lemonadecode.workspace.strings.preview_seconds
import dev.lemonadecode.workspace.strings.preview_unreadable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.decodeToSvgPainter
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private val MaxPictureHeight = 480.dp

/** Pictures smaller than this are scaled up without smoothing, so icons stay crisp. */
private val MinPictureSize = 96.dp

/** Playback controls under a small picture still get room for their bar. */
private val MinControlsWidth = 240.dp

private sealed interface Decoded<out T> {
  data object Loading : Decoded<Nothing>

  data class Ready<T>(val value: T) : Decoded<T>

  data class Failed(val reason: String) : Decoded<Nothing>
}

/** A picture file read for its preview: one image, or an animation that plays. */
private sealed interface Raster {
  class Still(val image: ImageBitmap) : Raster

  class Moving(val animation: ImageAnimation) : Raster
}

/** PNG, JPEG, GIF, WebP, BMP and ICO. Animated GIFs and WebPs play, with controls under them. */
@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun ImagePreview(bytes: ByteArray) {
  val decoded by produceState<Decoded<Raster>>(Decoded.Loading, bytes) {
    val animation = withContext(Dispatchers.Default) { decodeAnimation(bytes) }
    if (animation != null) {
      value = Decoded.Ready(Raster.Moving(animation))
      awaitDispose { animation.close() }
    } else {
      value = withContext(Dispatchers.Default) {
        runCatching { bytes.decodeToImageBitmap() }
          .fold({ Decoded.Ready(Raster.Still(it)) }, { Decoded.Failed(it.message ?: it::class.simpleName.orEmpty()) })
      }
    }
  }
  when (val state = decoded) {
    Decoded.Loading -> PreviewMessage(stringResource(Res.string.preview_loading), loading = true)
    is Decoded.Failed -> PreviewMessage(stringResource(Res.string.preview_unreadable, state.reason))
    is Decoded.Ready -> when (val raster = state.value) {
      is Raster.Moving -> AnimatedPicture(raster.animation, bytes.size.toLong())
      is Raster.Still -> StillPicture(raster.image, bytes.size.toLong())
    }
  }
}

@Composable
private fun StillPicture(
  image: ImageBitmap,
  sizeBytes: Long,
) {
  Picture(
    widthPx = image.width.toFloat(),
    heightPx = image.height.toFloat(),
    info = stringResource(Res.string.preview_image_info, image.width, image.height, formatBytes(sizeBytes)),
  ) { modifier, pixelated ->
    Image(
      bitmap = image,
      contentDescription = null,
      contentScale = ContentScale.Fit,
      filterQuality = if (pixelated) FilterQuality.None else FilterQuality.Medium,
      modifier = modifier,
    )
  }
}

/** An animated GIF or WebP, playing from the start like a browser plays it, with controls to pause and scrub. */
@Composable
private fun AnimatedPicture(
  animation: ImageAnimation,
  sizeBytes: Long,
) {
  val playback = remember(animation) { AnimationPlayback(animation) }
  val state = rememberMotionState(playback, autoplay = true, plays = animation.plays)
  val details = listOf(
    pluralStringResource(Res.plurals.preview_frames, animation.frameCount, formatCount(animation.frameCount)),
    stringResource(Res.string.preview_seconds, formatSeconds(playback.durationMicros / 1_000)),
    formatBytes(sizeBytes),
  ).joinToString(" · ")
  Picture(
    widthPx = animation.width.toFloat(),
    heightPx = animation.height.toFloat(),
    info = stringResource(Res.string.preview_image_info, animation.width, animation.height, details),
    controls = {
      MotionControls(state, position = "${playback.frameAt(state.positionMicros) + 1} / ${animation.frameCount}")
    },
  ) { modifier, pixelated ->
    MotionFrameView(state, modifier, filterQuality = if (pixelated) FilterQuality.None else FilterQuality.Medium)
  }
}

/**
 * [animation] playing on its own in the space [modifier] gives it, without controls; for pictures
 * inside documents. It loops as the file says. Closing [animation] is up to the caller, once this
 * has left the screen.
 */
@Composable
internal fun AnimatedImage(
  animation: ImageAnimation,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Fit,
  playing: Boolean = true,
) {
  val playback = remember(animation) { AnimationPlayback(animation) }
  val state = rememberMotionState(playback, autoplay = playing, plays = animation.plays)
  LaunchedEffect(state, playing) { if (playing) state.play() else state.pause() }
  MotionFrameView(state, modifier, contentScale)
}

@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun SvgPreview(bytes: ByteArray) {
  val density = LocalDensity.current
  val decoded by produceState<Decoded<Painter>>(Decoded.Loading, bytes, density) {
    value = withContext(Dispatchers.Default) {
      runCatching { bytes.decodeToSvgPainter(Density(1f)) }
        .fold({ Decoded.Ready(it) }, { Decoded.Failed(it.message ?: it::class.simpleName.orEmpty()) })
    }
  }
  when (val state = decoded) {
    Decoded.Loading -> PreviewMessage(stringResource(Res.string.preview_loading), loading = true)
    is Decoded.Failed -> PreviewMessage(stringResource(Res.string.preview_unreadable, state.reason))
    is Decoded.Ready -> {
      val painter = state.value
      // Decoded at density 1, so the intrinsic size is the SVG's own width and height.
      val intrinsic = painter.intrinsicSize.takeIf { it.isSpecified && it.width > 0 && it.height > 0 } ?: Size(256f, 256f)
      Picture(
        widthPx = intrinsic.width * density.density,
        heightPx = intrinsic.height * density.density,
        info = stringResource(
          Res.string.preview_image_info,
          intrinsic.width.roundToInt(),
          intrinsic.height.roundToInt(),
          formatBytes(bytes.size.toLong()),
        ),
        pixelatedWhenSmall = false,
      ) { modifier, _ ->
        Image(painter = painter, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
      }
    }
  }
}

/**
 * A picture at its own size, shrunk to fit the space, over a checkerboard so transparency shows
 * (or a plain backdrop for pictures that are never see-through), with any [controls] under it, as
 * wide as the picture or a little wider, and a caption under those.
 */
@Composable
internal fun Picture(
  widthPx: Float,
  heightPx: Float,
  info: String,
  pixelatedWhenSmall: Boolean = true,
  checkerboard: Boolean = true,
  controls: (@Composable () -> Unit)? = null,
  content: @Composable (Modifier, pixelated: Boolean) -> Unit,
) {
  val density = LocalDensity.current
  val spacing = LemonadeTheme.spaces.spacing200
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(spacing),
    modifier = Modifier.fillMaxWidth(),
  ) {
    BoxWithConstraints(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
      val natural = with(density) { Size(widthPx.toDp().value, heightPx.toDp().value) }
      val small = natural.width < MinPictureSize.value && natural.height < MinPictureSize.value
      val grow = if (small) MinPictureSize.value / maxOf(natural.width, natural.height) else 1f
      val fit = minOf(1f, maxWidth.value / (natural.width * grow), MaxPictureHeight.value / (natural.height * grow))
      val width: Dp = min((natural.width * grow * fit).dp, maxWidth)
      val height: Dp = (natural.height * grow * fit).dp
      val backdrop = if (checkerboard) Modifier.checkerboard() else Modifier.background(LemonadeTheme.colors.background.bgDefault)
      val controlsWidth = min(max(width, MinControlsWidth), maxWidth)
      Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(spacing)) {
        Box(modifier = Modifier.size(width, height).clip(LemonadeTheme.shapes.radius150).then(backdrop)) {
          content(Modifier.size(width, height), small && pixelatedWhenSmall)
        }
        controls?.let { row ->
          Box(modifier = Modifier.width(controlsWidth)) { row() }
        }
      }
    }
    LemonadeUi.Text(
      text = info,
      textStyle = LemonadeTheme.typography.bodyXSmallRegular,
      color = LemonadeTheme.colors.content.contentSecondary,
    )
  }
}

@Composable
private fun Modifier.checkerboard(): Modifier {
  val light = LemonadeTheme.colors.background.bgElevated
  val dark = LemonadeTheme.colors.background.bgDefault
  return drawBehind {
    val cell = 8.dp.toPx()
    drawRect(dark)
    var y = 0f
    var row = 0
    while (y < size.height) {
      var x = if (row % 2 == 0) 0f else cell
      while (x < size.width) {
        drawRect(color = light, topLeft = Offset(x, y), size = Size(cell, cell))
        x += cell * 2
      }
      y += cell
      row++
    }
  }
}
