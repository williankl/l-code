package dev.loupe.workspace.ui.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.unit.min
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.Text
import dev.loupe.workspace.strings.Res
import dev.loupe.workspace.strings.preview_image_info
import dev.loupe.workspace.strings.preview_loading
import dev.loupe.workspace.strings.preview_unreadable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.decodeToSvgPainter
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private val MaxPictureHeight = 480.dp

/** Pictures smaller than this are scaled up without smoothing, so icons stay crisp. */
private val MinPictureSize = 96.dp

private sealed interface Decoded<out T> {
  data object Loading : Decoded<Nothing>

  data class Ready<T>(val value: T) : Decoded<T>

  data class Failed(val reason: String) : Decoded<Nothing>
}

/** PNG, JPEG, GIF (first frame), WebP, BMP and ICO. */
@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun ImagePreview(bytes: ByteArray) {
  val decoded by produceState<Decoded<ImageBitmap>>(Decoded.Loading, bytes) {
    value = withContext(Dispatchers.Default) {
      runCatching { bytes.decodeToImageBitmap() }
        .fold({ Decoded.Ready(it) }, { Decoded.Failed(it.message ?: it::class.simpleName.orEmpty()) })
    }
  }
  when (val state = decoded) {
    Decoded.Loading -> PreviewMessage(stringResource(Res.string.preview_loading), loading = true)
    is Decoded.Failed -> PreviewMessage(stringResource(Res.string.preview_unreadable, state.reason))
    is Decoded.Ready -> {
      val image = state.value
      Picture(
        widthPx = image.width.toFloat(),
        heightPx = image.height.toFloat(),
        info = stringResource(Res.string.preview_image_info, image.width, image.height, formatBytes(bytes.size.toLong())),
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
  }
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
 * A picture at its own size, shrunk to fit the space, over a checkerboard so transparency shows,
 * with a caption under it.
 */
@Composable
private fun Picture(
  widthPx: Float,
  heightPx: Float,
  info: String,
  pixelatedWhenSmall: Boolean = true,
  content: @Composable (Modifier, pixelated: Boolean) -> Unit,
) {
  val density = LocalDensity.current
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing200),
    modifier = Modifier.fillMaxWidth(),
  ) {
    BoxWithConstraints(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
      val natural = with(density) { Size(widthPx.toDp().value, heightPx.toDp().value) }
      val small = natural.width < MinPictureSize.value && natural.height < MinPictureSize.value
      val grow = if (small) MinPictureSize.value / maxOf(natural.width, natural.height) else 1f
      val fit = minOf(1f, maxWidth.value / (natural.width * grow), MaxPictureHeight.value / (natural.height * grow))
      val width: Dp = min((natural.width * grow * fit).dp, maxWidth)
      val height: Dp = (natural.height * grow * fit).dp
      Box(modifier = Modifier.size(width, height).clip(LemonadeTheme.shapes.radius150).checkerboard()) {
        content(Modifier.size(width, height), small && pixelatedWhenSmall)
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
