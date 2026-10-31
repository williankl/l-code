package dev.loupe.workspace.ui.preview

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.io.encoding.Base64
import kotlin.math.max
import kotlin.math.min

private const val GlbMagic = 0x46546C67 // "glTF"
private const val ChunkJson = 0x4E4F534A // "JSON"
private const val ChunkBin = 0x004E4942 // "BIN\0"

/** Instancing can multiply a small file into more geometry than fits in memory. */
private const val MaxGltfTriangles = 10_000_000L

private val LenientBase64 = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

/** Bytes inside a larger array, so the GLB binary chunk isn't copied. */
private class ByteSlice(val array: ByteArray, val offset: Int, val length: Int)

/**
 * glTF 2.0, either a `.glb` container (JSON chunk plus binary chunk) or `.gltf` JSON whose buffers are
 * embedded as base64 `data:` URIs. Buffers in separate files can't be read from this one file's bytes.
 * Triangle primitives of the default scene are placed by their node transforms.
 */
internal fun parseGltf(bytes: ByteArray): Mesh {
  var binary: ByteSlice? = null
  val json = if (bytes.size >= 12 && bytes.int32Le(0) == GlbMagic) {
    val version = bytes.uint32Le(4)
    if (version != 2L) throw MeshFormatException("glTF $version isn’t supported, only 2.0")
    val length = min(bytes.uint32Le(8), bytes.size.toLong()).toInt()
    var text: String? = null
    var offset = 12
    while (offset + 8 <= length) {
      val chunkLength = bytes.uint32Le(offset)
      val type = bytes.int32Le(offset + 4)
      val start = offset + 8
      if (chunkLength > length - start) throw MeshFormatException("the file ends early")
      val end = start + chunkLength.toInt()
      if (type == ChunkJson && text == null) text = bytes.decodeToString(start, end)
      if (type == ChunkBin && binary == null) binary = ByteSlice(bytes, start, chunkLength.toInt())
      // Chunks are padded to four bytes.
      offset = end + (4 - chunkLength.toInt() % 4) % 4
    }
    text ?: throw MeshFormatException("the GLB file has no JSON")
  } else {
    bytes.decodeToString()
  }
  val root = try {
    Json.parseToJsonElement(json.removePrefix("﻿")) as? JsonObject
  } catch (_: SerializationException) {
    null
  } ?: throw MeshFormatException("invalid glTF JSON")
  return GltfReader(root, binary).read()
}

private class GltfReader(
  private val root: JsonObject,
  private val binary: ByteSlice?,
) {
  private val buffers = root.array("buffers")
  private val bufferViews = root.array("bufferViews")
  private val accessors = root.array("accessors")
  private val meshes = root.array("meshes")
  private val nodes = root.array("nodes")

  private val loadedBuffers = HashMap<Int, ByteSlice>()
  private val positionsByAccessor = HashMap<Int, FloatArray>()
  private val indicesByAccessor = HashMap<Int, IntArray>()

  private val positions = FloatBuilder()
  private val indices = IntBuilder()
  private var triangles = 0L

  fun read(): Mesh {
    val version = root.obj("asset")?.string("version")
    if (version != null && version.startsWith("1")) throw MeshFormatException("glTF $version isn’t supported, only 2.0")
    root.array("extensionsRequired")?.forEach { extension ->
      when ((extension as? JsonPrimitive)?.content) {
        "KHR_draco_mesh_compression" -> throw MeshFormatException("Draco-compressed meshes aren’t supported")
        "EXT_meshopt_compression" -> throw MeshFormatException("meshopt-compressed meshes aren’t supported")
      }
    }
    sceneRoots()?.forEach { visit(it, parent = null, depth = 0) }
    // No scene, or a scene that places nothing: show every mesh where it was modelled.
    if (indices.size == 0) meshes?.indices?.forEach { drawMesh(it, matrix = null) }
    return Mesh(positions.toArray(), indices.toArray())
  }

  /** The default scene's root nodes; without scenes, every node that isn't another's child. */
  private fun sceneRoots(): List<Int>? {
    val scene = root.array("scenes")?.getOrNull(root.int("scene") ?: 0) as? JsonObject
    if (scene != null) return scene.array("nodes")?.mapNotNull { it.asInt() }.orEmpty()
    val all = nodes ?: return null
    val children = all.flatMap { node -> (node as? JsonObject)?.array("children")?.mapNotNull { it.asInt() }.orEmpty() }.toSet()
    return all.indices.filter { it !in children }
  }

  private fun visit(index: Int, parent: FloatArray?, depth: Int) {
    // A node graph with a cycle is invalid; stop rather than loop.
    if (depth > 64) return
    val node = nodes?.getOrNull(index) as? JsonObject ?: throw MeshFormatException("node $index is missing")
    val local = localMatrix(node)
    val world = when {
      parent == null -> local
      local == null -> parent
      else -> multiply(parent, local)
    }
    node.int("mesh")?.let { drawMesh(it, world) }
    node.array("children")?.forEach { child -> child.asInt()?.let { visit(it, world, depth + 1) } }
  }

  /** Appends the triangle primitives of mesh [index], transformed by [matrix] (null for none). */
  private fun drawMesh(index: Int, matrix: FloatArray?) {
    val mesh = meshes?.getOrNull(index) as? JsonObject ?: throw MeshFormatException("mesh $index is missing")
    for (element in mesh.array("primitives") ?: return) {
      val primitive = element as? JsonObject ?: continue
      val mode = primitive.int("mode") ?: Triangles
      if (mode != Triangles && mode != TriangleStrip && mode != TriangleFan) continue
      val positionAccessor = primitive.obj("attributes")?.int("POSITION") ?: continue
      if (primitive.obj("extensions")?.containsKey("KHR_draco_mesh_compression") == true && accessor(positionAccessor).int("bufferView") == null) {
        throw MeshFormatException("Draco-compressed meshes aren’t supported")
      }
      val source = positionsByAccessor.getOrPut(positionAccessor) { readPositions(positionAccessor) }
      val vertexCount = source.size / 3
      val order = primitive.int("indices")?.let { indicesByAccessor.getOrPut(it) { readIndices(it) } }
      val count = order?.size ?: vertexCount
      val primitiveTriangles = if (mode == Triangles) count / 3 else max(0, count - 2)
      if (primitiveTriangles == 0) continue
      triangles += primitiveTriangles
      if (triangles > MaxGltfTriangles) throw MeshFormatException("too many triangles to draw")

      val base = positions.size / 3
      appendTransformed(source, matrix)
      fun corner(i: Int): Int {
        val vertex = order?.get(i) ?: i
        if (vertex < 0 || vertex >= vertexCount) throw MeshFormatException("a primitive points at a vertex that doesn’t exist")
        return base + vertex
      }
      for (t in 0 until primitiveTriangles) {
        when (mode) {
          Triangles -> indices.add(corner(t * 3), corner(t * 3 + 1), corner(t * 3 + 2))
          // Every other strip triangle is flipped to keep the winding.
          TriangleStrip -> if (t % 2 == 0) indices.add(corner(t), corner(t + 1), corner(t + 2)) else indices.add(corner(t + 1), corner(t), corner(t + 2))
          else -> indices.add(corner(0), corner(t + 1), corner(t + 2))
        }
      }
    }
  }

  private fun appendTransformed(source: FloatArray, m: FloatArray?) {
    var i = 0
    while (i < source.size) {
      val x = source[i]
      val y = source[i + 1]
      val z = source[i + 2]
      if (m == null) {
        positions.add(x, y, z)
      } else {
        positions.add(
          m[0] * x + m[4] * y + m[8] * z + m[12],
          m[1] * x + m[5] * y + m[9] * z + m[13],
          m[2] * x + m[6] * y + m[10] * z + m[14],
        )
      }
      i += 3
    }
  }

  private fun accessor(index: Int): JsonObject = accessors?.getOrNull(index) as? JsonObject ?: throw MeshFormatException("accessor $index is missing")

  private fun readPositions(index: Int): FloatArray {
    val accessor = accessor(index)
    if (accessor.string("type") != "VEC3") throw MeshFormatException("positions aren’t 3D vectors")
    val count = accessor.int("count")?.takeIf { it >= 0 } ?: throw MeshFormatException("accessor $index has no count")
    val componentType = accessor.int("componentType") ?: throw MeshFormatException("accessor $index has no component type")
    val normalized = accessor.boolean("normalized") ?: false
    val size = componentSize(componentType)
    val out = FloatArray(count * 3)
    accessor.int("bufferView")?.let { view ->
      val span = span(view, accessor.int("byteOffset") ?: 0, count, size * 3)
      for (i in 0 until count) {
        val at = span.start + i * span.stride
        for (c in 0 until 3) out[i * 3 + c] = span.data.component(at + c * size, componentType, normalized)
      }
    }
    // Sparse accessors replace some elements of the base (or of zeros, without a view).
    accessor.obj("sparse")?.let { sparse ->
      val replaced = sparse.int("count") ?: 0
      val indexInfo = sparse.obj("indices") ?: throw MeshFormatException("a sparse accessor has no indices")
      val valueInfo = sparse.obj("values") ?: throw MeshFormatException("a sparse accessor has no values")
      val indexType = indexInfo.int("componentType") ?: throw MeshFormatException("a sparse accessor has no index type")
      val indexSpan = span(indexInfo.int("bufferView") ?: -1, indexInfo.int("byteOffset") ?: 0, replaced, componentSize(indexType))
      val valueSpan = span(valueInfo.int("bufferView") ?: -1, valueInfo.int("byteOffset") ?: 0, replaced, size * 3)
      for (k in 0 until replaced) {
        val target = indexSpan.data.indexValue(indexSpan.start + k * indexSpan.stride, indexType)
        if (target < 0 || target >= count) throw MeshFormatException("a sparse accessor points past its end")
        val at = valueSpan.start + k * valueSpan.stride
        for (c in 0 until 3) out[target * 3 + c] = valueSpan.data.component(at + c * size, componentType, normalized)
      }
    }
    return out
  }

  private fun readIndices(index: Int): IntArray {
    val accessor = accessor(index)
    val count = accessor.int("count")?.takeIf { it >= 0 } ?: throw MeshFormatException("accessor $index has no count")
    val componentType = accessor.int("componentType") ?: throw MeshFormatException("accessor $index has no component type")
    if (componentType != U8 && componentType != U16 && componentType != U32) {
      throw MeshFormatException("indices must be unsigned integers")
    }
    val view = accessor.int("bufferView") ?: return IntArray(count)
    val span = span(view, accessor.int("byteOffset") ?: 0, count, componentSize(componentType))
    return IntArray(count) { span.data.indexValue(span.start + it * span.stride, componentType) }
  }

  /** Where [count] elements of [elementSize] bytes start in buffer view [viewIndex], checked against its bounds. */
  private fun span(viewIndex: Int, byteOffset: Int, count: Int, elementSize: Int): ElementSpan {
    val view = bufferViews?.getOrNull(viewIndex) as? JsonObject ?: throw MeshFormatException("buffer view $viewIndex is missing")
    val buffer = buffer(view.int("buffer") ?: throw MeshFormatException("buffer view $viewIndex has no buffer"))
    val viewOffset = view.int("byteOffset") ?: 0
    val viewLength = view.int("byteLength") ?: throw MeshFormatException("buffer view $viewIndex has no length")
    val stride = view.int("byteStride")?.takeIf { it > 0 } ?: elementSize
    if (viewOffset < 0 || viewLength < 0 || byteOffset < 0 || viewOffset.toLong() + viewLength > buffer.length) {
      throw MeshFormatException("a buffer view runs past its buffer")
    }
    if (count > 0 && byteOffset + (count - 1).toLong() * stride + elementSize > viewLength) {
      throw MeshFormatException("an accessor runs past its buffer view")
    }
    return ElementSpan(buffer.array, buffer.offset + viewOffset + byteOffset, stride)
  }

  private fun buffer(index: Int): ByteSlice = loadedBuffers.getOrPut(index) {
    val buffer = buffers?.getOrNull(index) as? JsonObject ?: throw MeshFormatException("buffer $index is missing")
    val uri = buffer.string("uri")
    when {
      uri == null -> binary?.takeIf { index == 0 } ?: throw MeshFormatException("buffer $index has no data")
      uri.startsWith("data:") -> decodeDataUri(uri)
      else -> throw MeshFormatException("the geometry is in a separate file, ${uri.substringAfterLast('/')}")
    }
  }

  private fun decodeDataUri(uri: String): ByteSlice {
    val comma = uri.indexOf(',')
    if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) throw MeshFormatException("unsupported data URI")
    val data = try {
      LenientBase64.decode(uri, comma + 1, uri.length)
    } catch (_: IllegalArgumentException) {
      throw MeshFormatException("bad base64 data")
    }
    return ByteSlice(data, 0, data.size)
  }

  private companion object {
    const val Triangles = 4
    const val TriangleStrip = 5
    const val TriangleFan = 6
  }
}

private class ElementSpan(val data: ByteArray, val start: Int, val stride: Int)

// Accessor component types.
private const val I8 = 5120
private const val U8 = 5121
private const val I16 = 5122
private const val U16 = 5123
private const val U32 = 5125
private const val F32 = 5126

private fun componentSize(type: Int): Int = when (type) {
  I8, U8 -> 1
  I16, U16 -> 2
  U32, F32 -> 4
  else -> throw MeshFormatException("unknown component type $type")
}

/** One component as a float; normalized integers map to 0..1 or -1..1 as the spec says. */
private fun ByteArray.component(offset: Int, type: Int, normalized: Boolean): Float = when (type) {
  F32 -> floatLe(offset)
  I8 -> this[offset].toFloat().let { if (normalized) max(it / 127f, -1f) else it }
  U8 -> (this[offset].toInt() and 0xFF).toFloat().let { if (normalized) it / 255f else it }
  I16 -> uint16Le(offset).toShort().toFloat().let { if (normalized) max(it / 32767f, -1f) else it }
  U16 -> uint16Le(offset).toFloat().let { if (normalized) it / 65535f else it }
  U32 -> uint32Le(offset).toFloat()
  else -> throw MeshFormatException("unknown component type $type")
}

private fun ByteArray.indexValue(offset: Int, type: Int): Int = when (type) {
  U8 -> this[offset].toInt() and 0xFF
  U16 -> uint16Le(offset)
  U32 -> int32Le(offset).let { if (it < 0) Int.MAX_VALUE else it }
  else -> throw MeshFormatException("indices must be unsigned integers")
}

/** A node's own transform, column-major; null when it has none. */
private fun localMatrix(node: JsonObject): FloatArray? {
  node.floats("matrix")?.takeIf { it.size == 16 }?.let { return it }
  val t = node.floats("translation")?.takeIf { it.size == 3 }
  val r = node.floats("rotation")?.takeIf { it.size == 4 }
  val s = node.floats("scale")?.takeIf { it.size == 3 }
  if (t == null && r == null && s == null) return null
  val (x, y, z, w) = r ?: floatArrayOf(0f, 0f, 0f, 1f)
  val (sx, sy, sz) = s ?: floatArrayOf(1f, 1f, 1f)
  val (tx, ty, tz) = t ?: floatArrayOf(0f, 0f, 0f)
  // Translation * rotation (unit quaternion) * scale.
  return floatArrayOf(
    (1 - 2 * (y * y + z * z)) * sx, 2 * (x * y + w * z) * sx, 2 * (x * z - w * y) * sx, 0f,
    2 * (x * y - w * z) * sy, (1 - 2 * (x * x + z * z)) * sy, 2 * (y * z + w * x) * sy, 0f,
    2 * (x * z + w * y) * sz, 2 * (y * z - w * x) * sz, (1 - 2 * (x * x + y * y)) * sz, 0f,
    tx, ty, tz, 1f,
  )
}

private fun multiply(a: FloatArray, b: FloatArray): FloatArray = FloatArray(16) { i ->
  val column = i / 4
  val row = i % 4
  var sum = 0f
  for (k in 0 until 4) sum += a[k * 4 + row] * b[column * 4 + k]
  sum
}

private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.int(key: String): Int? = this[key]?.asInt()

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.floats(key: String): FloatArray? = array(key)?.let { values ->
  FloatArray(values.size) { (values[it] as? JsonPrimitive)?.floatOrNull ?: 0f }
}

private fun JsonElement.asInt(): Int? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
