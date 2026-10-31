package dev.loupe.workspace.ui.preview

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Above this many triangles a mesh is thinned out evenly, so turning it stays smooth. */
internal const val MaxDrawnTriangles = 200_000

/**
 * A mesh ready to draw: the drawn triangles' vertices, Y up, centred and scaled into a unit sphere, with
 * a unit normal per drawn triangle. [totalTriangles] counts the source mesh, drawn or not.
 */
internal class MeshScene(
  val positions: FloatArray,
  val indices: IntArray,
  val normals: FloatArray,
  val totalTriangles: Int,
) {
  val drawnTriangles: Int get() = indices.size / 3
}

/**
 * Picks the triangles to draw (all of them, or [maxTriangles] spread evenly), drops any with a corner
 * that isn't a finite number, and normalises the rest. STL is Z up, the other formats Y up.
 */
internal fun prepareScene(mesh: Mesh, zUp: Boolean, maxTriangles: Int = MaxDrawnTriangles): MeshScene {
  val total = mesh.triangleCount
  val source = mesh.positions
  fun finite(vertex: Int): Boolean = source[vertex * 3].isFinite() && source[vertex * 3 + 1].isFinite() && source[vertex * 3 + 2].isFinite()

  val take = min(total, maxTriangles)
  val step = total.toDouble() / take
  val picked = IntBuilder(take * 3)
  for (k in 0 until take) {
    val t = (k * step).toInt()
    val a = mesh.indices[t * 3]
    val b = mesh.indices[t * 3 + 1]
    val c = mesh.indices[t * 3 + 2]
    if (finite(a) && finite(b) && finite(c)) picked.add(a, b, c)
  }
  if (picked.size == 0) throw MeshFormatException("no drawable triangles")

  // Keep only the vertices the picked triangles use.
  val remap = IntArray(mesh.vertexCount) { -1 }
  val compact = FloatBuilder(min(picked.size, mesh.vertexCount) * 3)
  val indices = IntArray(picked.size)
  for (i in indices.indices) {
    val vertex = picked[i]
    var mapped = remap[vertex]
    if (mapped < 0) {
      mapped = compact.size / 3
      remap[vertex] = mapped
      val x = source[vertex * 3]
      val y = source[vertex * 3 + 1]
      val z = source[vertex * 3 + 2]
      if (zUp) compact.add(x, z, -y) else compact.add(x, y, z)
    }
    indices[i] = mapped
  }
  val positions = compact.toArray()

  // Centre on the bounding box and fit the farthest vertex on the unit sphere, in doubles so huge
  // coordinates don't overflow.
  val low = DoubleArray(3) { Double.MAX_VALUE }
  val high = DoubleArray(3) { -Double.MAX_VALUE }
  for (i in positions.indices) {
    val axis = i % 3
    low[axis] = min(low[axis], positions[i].toDouble())
    high[axis] = max(high[axis], positions[i].toDouble())
  }
  val center = DoubleArray(3) { (low[it] + high[it]) / 2 }
  var radiusSquared = 0.0
  for (v in 0 until positions.size / 3) {
    var sum = 0.0
    for (axis in 0 until 3) {
      val d = positions[v * 3 + axis] - center[axis]
      sum += d * d
    }
    radiusSquared = max(radiusSquared, sum)
  }
  val scale = if (radiusSquared > 0.0 && radiusSquared.isFinite()) 1.0 / sqrt(radiusSquared) else 1.0
  for (i in positions.indices) positions[i] = ((positions[i] - center[i % 3]) * scale).toFloat()

  val normals = FloatArray(indices.size)
  for (t in 0 until indices.size / 3) {
    val a = indices[t * 3] * 3
    val b = indices[t * 3 + 1] * 3
    val c = indices[t * 3 + 2] * 3
    val ux = positions[b] - positions[a]
    val uy = positions[b + 1] - positions[a + 1]
    val uz = positions[b + 2] - positions[a + 2]
    val vx = positions[c] - positions[a]
    val vy = positions[c + 1] - positions[a + 1]
    val vz = positions[c + 2] - positions[a + 2]
    val nx = uy * vz - uz * vy
    val ny = uz * vx - ux * vz
    val nz = ux * vy - uy * vx
    val length = sqrt(nx * nx + ny * ny + nz * nz)
    if (length > 0f) {
      normals[t * 3] = nx / length
      normals[t * 3 + 1] = ny / length
      normals[t * 3 + 2] = nz / length
    }
  }
  return MeshScene(positions, indices, normals, total)
}

/** Where the camera is: angles in radians, pan in viewport pixels from the centre. */
internal data class ModelView(
  val yaw: Float = DefaultYaw,
  val pitch: Float = DefaultPitch,
  val zoom: Float = 1f,
  val panX: Float = 0f,
  val panY: Float = 0f,
) {
  fun turned(yawBy: Float, pitchBy: Float): ModelView = copy(yaw = yaw + yawBy, pitch = (pitch + pitchBy).coerceIn(-HalfPi, HalfPi))

  /** Zooms by [factor], keeping the point [aroundX], [aroundY] (from the viewport centre) where it is. */
  fun zoomed(factor: Float, aroundX: Float = 0f, aroundY: Float = 0f): ModelView {
    val next = (zoom * factor).coerceIn(MinZoom, MaxZoom)
    val k = next / zoom
    return copy(zoom = next, panX = aroundX - (aroundX - panX) * k, panY = aroundY - (aroundY - panY) * k)
  }

  companion object {
    /** A three-quarter view from a little above. */
    const val DefaultYaw = (-PI / 6).toFloat()
    const val DefaultPitch = (PI / 9).toFloat()
    const val MinZoom = 0.25f
    const val MaxZoom = 25f
    private const val HalfPi = (PI / 2).toFloat()
  }
}

/**
 * Draws a [MeshScene] into ARGB pixels with a depth buffer: mild perspective, flat shading from a key
 * and a fill light, both sides of every face lit so meshes with mixed winding still look solid.
 * Not thread-safe; buffers are reused from one call to the next.
 */
internal class MeshRenderer(private val scene: MeshScene) {
  private val screenX = FloatArray(scene.positions.size / 3)
  private val screenY = FloatArray(screenX.size)

  /** 1 / distance from the camera: larger is nearer, and it interpolates linearly across the screen. */
  private val nearness = FloatArray(screenX.size)
  private var pixels = IntArray(0)
  private var depth = FloatArray(0)

  /**
   * Renders [width] × [height] pixels; [palette] maps light from 0 to 255 to a colour. [panScale] turns
   * the view's viewport pixels into these pixels. The returned array is overwritten by the next call.
   */
  fun render(width: Int, height: Int, view: ModelView, palette: IntArray, panScale: Float = 1f): IntArray {
    val size = width * height
    if (pixels.size != size) {
      pixels = IntArray(size)
      depth = FloatArray(size)
    } else {
      pixels.fill(0)
      depth.fill(0f)
    }
    val cosYaw = cos(view.yaw)
    val sinYaw = sin(view.yaw)
    val cosPitch = cos(view.pitch)
    val sinPitch = sin(view.pitch)
    val scale = min(width, height) * 0.5f * FitMargin * view.zoom / PerspectiveFit
    val centerX = width / 2f + view.panX * panScale
    val centerY = height / 2f + view.panY * panScale

    val positions = scene.positions
    for (v in screenX.indices) {
      val x = positions[v * 3]
      val y = positions[v * 3 + 1]
      val z = positions[v * 3 + 2]
      val x1 = cosYaw * x + sinYaw * z
      val z1 = cosYaw * z - sinYaw * x
      val y2 = cosPitch * y - sinPitch * z1
      val z2 = sinPitch * y + cosPitch * z1
      val w = 1f / (CameraDistance - z2)
      val f = CameraDistance * w * scale
      screenX[v] = centerX + x1 * f
      screenY[v] = centerY - y2 * f
      nearness[v] = w
    }

    val indices = scene.indices
    val normals = scene.normals
    val last = palette.size - 1
    for (t in 0 until indices.size / 3) {
      var nx = cosYaw * normals[t * 3] + sinYaw * normals[t * 3 + 2]
      val nz1 = cosYaw * normals[t * 3 + 2] - sinYaw * normals[t * 3]
      var ny = cosPitch * normals[t * 3 + 1] - sinPitch * nz1
      var nz = sinPitch * normals[t * 3 + 1] + cosPitch * nz1
      if (nz < 0f) {
        nx = -nx
        ny = -ny
        nz = -nz
      }
      val key = max(0f, nx * KeyX + ny * KeyY + nz * KeyZ)
      val fill = max(0f, nx * FillX + ny * FillY + nz * FillZ)
      val light = Ambient + KeyStrength * key + FillStrength * fill
      val color = palette[(light * last).toInt().coerceIn(0, last)]
      fillTriangle(indices[t * 3], indices[t * 3 + 1], indices[t * 3 + 2], color, width, height)
    }
    return pixels
  }

  private fun fillTriangle(a: Int, b: Int, c: Int, color: Int, width: Int, height: Int) {
    val x0 = screenX[a]
    val y0 = screenY[a]
    val d0 = nearness[a]
    var x1 = screenX[b]
    var y1 = screenY[b]
    var d1 = nearness[b]
    var x2 = screenX[c]
    var y2 = screenY[c]
    var d2 = nearness[c]
    var area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
    if (!(area > 0f || area < 0f)) return
    if (area < 0f) {
      // Wind every triangle the same way so the edge tests below read "inside" as positive.
      x1 = x2.also { x2 = x1 }
      y1 = y2.also { y2 = y1 }
      d1 = d2.also { d2 = d1 }
      area = -area
    }
    val minX = min(x0, min(x1, x2))
    val maxX = max(x0, max(x1, x2))
    val minY = min(y0, min(y1, y2))
    val maxY = max(y0, max(y1, y2))
    if (maxX < 0f || minX > width || maxY < 0f || minY > height) return
    val firstRow = max(0, ceil(minY - 0.5f).toInt())
    val lastRow = min(height - 1, floor(maxY - 0.5f).toInt())
    if (firstRow > lastRow) return

    // Edge functions a·x + b·y + c, positive inside; edge i is the one facing corner i.
    val a0 = y1 - y2
    val b0 = x2 - x1
    val c0 = x1 * y2 - x2 * y1
    val a1 = y2 - y0
    val b1 = x0 - x2
    val c1 = x2 * y0 - x0 * y2
    val a2 = y0 - y1
    val b2 = x1 - x0
    val c2 = x0 * y1 - x1 * y0
    // Nearness is a plane over the screen: weight each corner by its edge function.
    val depthA = (a0 * d0 + a1 * d1 + a2 * d2) / area
    val depthB = (b0 * d0 + b1 * d1 + b2 * d2) / area
    val depthC = (c0 * d0 + c1 * d1 + c2 * d2) / area

    for (row in firstRow..lastRow) {
      val y = row + 0.5f
      var left = minX
      var right = maxX
      val r0 = b0 * y + c0
      if (a0 > 0f) {
        left = max(left, -r0 / a0)
      } else if (a0 < 0f) {
        right = min(right, -r0 / a0)
      } else if (r0 < 0f) {
        continue
      }
      val r1 = b1 * y + c1
      if (a1 > 0f) {
        left = max(left, -r1 / a1)
      } else if (a1 < 0f) {
        right = min(right, -r1 / a1)
      } else if (r1 < 0f) {
        continue
      }
      val r2 = b2 * y + c2
      if (a2 > 0f) {
        left = max(left, -r2 / a2)
      } else if (a2 < 0f) {
        right = min(right, -r2 / a2)
      } else if (r2 < 0f) {
        continue
      }
      // Pixel centres inside the span; the slack closes hairline cracks between neighbours.
      val first = max(0, ceil(left - 0.5f - EdgeSlack).toInt())
      val lastColumn = min(width - 1, floor(right - 0.5f + EdgeSlack).toInt())
      if (first > lastColumn) continue
      var near = depthA * (first + 0.5f) + depthB * y + depthC
      var index = row * width + first
      for (column in first..lastColumn) {
        if (near > depth[index]) {
          depth[index] = near
          pixels[index] = color
        }
        near += depthA
        index++
      }
    }
  }

  private companion object {
    /** Camera distance in model radii: close enough for some depth, far enough not to distort. */
    const val CameraDistance = 3.5f

    /** How much perspective can enlarge the unit sphere's outline at [CameraDistance]. */
    val PerspectiveFit = CameraDistance / sqrt(CameraDistance * CameraDistance - 1f)
    const val FitMargin = 0.9f
    const val EdgeSlack = 0.001f

    const val Ambient = 0.22f
    const val KeyStrength = 0.63f
    const val FillStrength = 0.15f

    // Light directions in view space (x right, y up, z towards the viewer), about unit length.
    const val KeyX = -0.45f
    const val KeyY = 0.6f
    const val KeyZ = 0.66f
    const val FillX = 0.72f
    const val FillY = -0.3f
    const val FillZ = 0.62f
  }
}
