package dev.lemonadecode.workspace.ui.preview

import androidx.compose.ui.graphics.ImageBitmap

/** An SVG drawn into a bitmap [scale] times its own [width] × [height]. */
internal class RasterizedSvg(val image: ImageBitmap, val width: Float, val height: Float)

/**
 * Draws an SVG that has text in it, which Skia's SVG renderer in Compose leaves out, at [scale]
 * times its own size. Null when it can't be read. Never loads anything from outside the SVG.
 */
internal expect fun rasterizeSvg(bytes: ByteArray, scale: Float): RasterizedSvg?

/** Whether an SVG has text that only [rasterizeSvg] draws. */
internal fun svgHasText(bytes: ByteArray): Boolean = SvgText.containsMatchIn(bytes.decodeToString())

private val SvgText = Regex("<(?:[A-Za-z_][\\w.-]*:)?text[\\s>]")
