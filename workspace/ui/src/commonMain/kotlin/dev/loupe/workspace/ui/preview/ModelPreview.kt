package dev.loupe.workspace.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.teya.lemonade.IconButton
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.Spinner
import com.teya.lemonade.Text
import com.teya.lemonade.core.LemonadeAssetSize
import com.teya.lemonade.core.LemonadeButtonSize
import com.teya.lemonade.core.LemonadeButtonType
import com.teya.lemonade.core.LemonadeButtonVariant
import com.teya.lemonade.core.LemonadeIcons
import dev.loupe.workspace.strings.Res
import dev.loupe.workspace.strings.preview_empty
import dev.loupe.workspace.strings.preview_loading
import dev.loupe.workspace.strings.preview_model_hint
import dev.loupe.workspace.strings.preview_model_partial
import dev.loupe.workspace.strings.preview_model_triangles
import dev.loupe.workspace.strings.preview_reset_view
import dev.loupe.workspace.strings.preview_unreadable
import dev.loupe.workspace.strings.preview_zoom_in
import dev.loupe.workspace.strings.preview_zoom_out
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val ViewportHeight = 360.dp

/** Bigger viewports render at this many pixels and are scaled up; past it, sharpness costs more than it shows. */
private const val MaxRenderPixels = 1_600_000

private const val TurnPerDp = (0.6 * PI / 180).toFloat()
private const val ButtonZoom = 1.25f
private const val WheelZoom = 1.15f

private sealed interface ModelLoad {
  data object Loading : ModelLoad

  data object Empty : ModelLoad

  class Failed(val reason: String) : ModelLoad

  class Ready(val scene: MeshScene) : ModelLoad
}

/**
 * One side of a 3D model file ([extension] is `stl`, `obj`, `ply`, `gltf` or `glb`), drawn in software:
 * drag to turn it, ⌘ or Ctrl + wheel to zoom (the plain wheel keeps scrolling the page), double-click
 * to reset. Fills the width it's given at a fixed height; parsing runs off the main thread.
 */
@Composable
internal fun ModelPreview(bytes: ByteArray, extension: String, modifier: Modifier = Modifier) {
  val load by produceState<ModelLoad>(ModelLoad.Loading, bytes, extension) {
    value = if (bytes.isEmpty()) ModelLoad.Empty else withContext(Dispatchers.Default) { loadModel(bytes, extension) }
  }
  when (val state = load) {
    ModelLoad.Loading -> ModelMessage(stringResource(Res.string.preview_loading), modifier, loading = true)
    ModelLoad.Empty -> ModelMessage(stringResource(Res.string.preview_empty), modifier)
    is ModelLoad.Failed -> ModelMessage(stringResource(Res.string.preview_unreadable, state.reason), modifier)
    is ModelLoad.Ready -> ModelViewer(state.scene, modifier)
  }
}

private fun loadModel(bytes: ByteArray, extension: String): ModelLoad = try {
  ModelLoad.Ready(prepareScene(parseMesh(bytes, extension), zUp = extension == "stl"))
} catch (e: MeshFormatException) {
  ModelLoad.Failed(e.message.orEmpty())
}

/** What one frame needs. The palette is compared by identity; it's remembered per theme. */
private data class FrameRequest(val viewport: IntSize, val view: ModelView, val palette: IntArray)

@Composable
private fun ModelViewer(scene: MeshScene, modifier: Modifier) {
  val renderer = remember(scene) { MeshRenderer(scene) }
  var view by remember(scene) { mutableStateOf(ModelView()) }
  var viewport by remember { mutableStateOf(IntSize.Zero) }
  var frame by remember(scene) { mutableStateOf<ImageBitmap?>(null) }
  val palette by rememberUpdatedState(shadePalette())

  // Frames render off the main thread, one at a time; while one renders, view changes pile up into one.
  LaunchedEffect(renderer) {
    var retired: ImageBitmap? = null
    snapshotFlow { FrameRequest(viewport, view, palette) }
      .conflate()
      .collect { request ->
        if (request.viewport.width <= 0 || request.viewport.height <= 0) return@collect
        val image = withContext(Dispatchers.Default) { renderer.renderImage(request) }
        // The frame before the one on screen can't be drawn again.
        retired?.let(::releaseImage)
        retired = frame
        frame = image
      }
  }

  val colors = LemonadeTheme.colors
  val spaces = LemonadeTheme.spaces
  Column(
    verticalArrangement = Arrangement.spacedBy(spaces.spacing200),
    modifier = modifier.fillMaxWidth(),
  ) {
    Box(
      modifier = Modifier
        .viewport()
        .onSizeChanged { viewport = it }
        .pointerInput(renderer) {
          detectDragGestures { change, drag ->
            change.consume()
            view = view.turned(drag.x / density * TurnPerDp, drag.y / density * TurnPerDp)
          }
        }
        .pointerInput(renderer) { detectTapGestures(onDoubleTap = { view = ModelView() }) }
        .pointerInput(renderer) {
          // Only ⌘ or Ctrl + wheel is ours; anything else is left for the list around us to scroll.
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              val modifiers = event.keyboardModifiers
              if (event.type != PointerEventType.Scroll || !(modifiers.isCtrlPressed || modifiers.isMetaPressed)) continue
              val change = event.changes.firstOrNull() ?: continue
              val steps = change.scrollDelta.y.coerceIn(-5f, 5f)
              if (steps == 0f) continue
              val around = change.position - Offset(size.width / 2f, size.height / 2f)
              view = view.zoomed(WheelZoom.pow(-steps), around.x, around.y)
              event.changes.forEach { it.consume() }
            }
          }
        }
        .drawBehind {
          val image = frame ?: return@drawBehind
          drawImage(
            image = image,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Low,
          )
        },
    ) {
      Row(modifier = Modifier.align(Alignment.TopEnd).padding(spaces.spacing100)) {
        ViewButton(LemonadeIcons.Minus, Res.string.preview_zoom_out) { view = view.zoomed(1 / ButtonZoom) }
        ViewButton(LemonadeIcons.Plus, Res.string.preview_zoom_in) { view = view.zoomed(ButtonZoom) }
        ViewButton(LemonadeIcons.ArrowRotateCcw, Res.string.preview_reset_view) { view = ModelView() }
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(spaces.spacing300)) {
      LemonadeUi.Text(
        text = stringResource(Res.string.preview_model_hint),
        textStyle = LemonadeTheme.typography.bodyXSmallRegular,
        color = colors.content.contentTertiary,
        modifier = Modifier.weight(1f),
      )
      LemonadeUi.Text(
        text = triangleCount(scene),
        textStyle = LemonadeTheme.typography.bodyXSmallRegular,
        color = colors.content.contentSecondary,
      )
    }
  }
}

private fun MeshRenderer.renderImage(request: FrameRequest): ImageBitmap {
  val (width, height) = request.viewport
  val shrink = min(1f, sqrt(MaxRenderPixels / (width.toFloat() * height)))
  val renderWidth = max(1, (width * shrink).toInt())
  val renderHeight = max(1, (height * shrink).toInt())
  val pixels = render(renderWidth, renderHeight, request.view, request.palette, panScale = renderWidth / width.toFloat())
  return imageFromPixels(pixels, renderWidth, renderHeight)
}

@Composable
private fun triangleCount(scene: MeshScene): String = if (scene.drawnTriangles < scene.totalTriangles) {
  stringResource(Res.string.preview_model_partial, formatCount(scene.drawnTriangles), formatCount(scene.totalTriangles))
} else {
  pluralStringResource(Res.plurals.preview_model_triangles, scene.totalTriangles, formatCount(scene.totalTriangles))
}

/** Light from 0 to 255 as colours: the info hue from deep shadow to a lit highlight, in either theme. */
@Composable
private fun shadePalette(): IntArray {
  val base = LemonadeTheme.colors.content.contentInfo
  val dark = LemonadeTheme.colors.isDark
  return remember(base, dark) {
    val shadow = lerp(base, Color.Black, if (dark) 0.72f else 0.55f)
    val lit = lerp(base, Color.White, if (dark) 0.12f else 0.3f)
    IntArray(256) { lerp(shadow, lit, it / 255f).toArgb() }
  }
}

@Composable
private fun Modifier.viewport(): Modifier = fillMaxWidth()
  .height(ViewportHeight)
  .clip(RoundedCornerShape(LemonadeTheme.radius.radius300))
  .background(LemonadeTheme.colors.background.bgSubtle)

@Composable
private fun ViewButton(icon: LemonadeIcons, label: StringResource, onClick: () -> Unit) {
  LemonadeUi.IconButton(
    icon = icon,
    contentDescription = stringResource(label),
    onClick = onClick,
    variant = LemonadeButtonVariant.Neutral,
    type = LemonadeButtonType.Ghost,
    size = LemonadeButtonSize.XSmall,
  )
}

@Composable
private fun ModelMessage(text: String, modifier: Modifier, loading: Boolean = false) {
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier.viewport().padding(LemonadeTheme.spaces.spacing400),
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing200),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (loading) LemonadeUi.Spinner(size = LemonadeAssetSize.Small, tint = LemonadeTheme.colors.content.contentSecondary)
      LemonadeUi.Text(
        text = text,
        textStyle = LemonadeTheme.typography.bodySmallRegular,
        color = LemonadeTheme.colors.content.contentSecondary,
        textAlign = TextAlign.Center,
      )
    }
  }
}
