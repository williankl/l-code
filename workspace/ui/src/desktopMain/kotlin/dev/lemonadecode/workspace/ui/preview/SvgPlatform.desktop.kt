package dev.lemonadecode.workspace.ui.preview

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import com.github.weisj.jsvg.parser.resources.ResourcePolicy
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.swing.JComponent
import kotlin.math.ceil

/** The longest side of a drawn SVG, so a huge `width` can't ask for a huge bitmap. */
private const val MaxSidePx = 4096f

internal actual fun rasterizeSvg(bytes: ByteArray, scale: Float): RasterizedSvg? {
  val context = LoaderContext.builder().externalResourcePolicy(ResourcePolicy.DENY_EXTERNAL).build()
  val document = SVGLoader().load(ByteArrayInputStream(bytes), null, context) ?: return null
  val size = document.size()
  // Browsers give an SVG without its own size 300 × 150.
  val width = size.width.takeIf { it > 0f && it.isFinite() } ?: 300f
  val height = size.height.takeIf { it > 0f && it.isFinite() } ?: 150f
  val drawScale = minOf(scale, MaxSidePx / maxOf(width, height))
  val image = BufferedImage(ceil(width * drawScale).toInt().coerceAtLeast(1), ceil(height * drawScale).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
  val graphics = image.createGraphics()
  try {
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    graphics.scale(drawScale.toDouble(), drawScale.toDouble())
    document.render(null as JComponent?, graphics)
  } finally {
    graphics.dispose()
  }
  return RasterizedSvg(image.toComposeImageBitmap(), width, height)
}
