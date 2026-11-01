package dev.lemonadecode.workspace.ui.preview

/** An indexed triangle mesh: x, y, z per vertex in [positions], three vertex indices per triangle in [indices]. */
internal class Mesh(
  val positions: FloatArray,
  val indices: IntArray,
) {
  val vertexCount: Int get() = positions.size / 3
  val triangleCount: Int get() = indices.size / 3
}

/** A file that can't be read as a mesh. The message is short and shown to the user. */
internal class MeshFormatException(message: String) : Exception(message)

/**
 * Reads a 3D model file. [extension] is lowercase without the dot: `stl`, `obj`, `ply`, `gltf` or `glb`.
 * Throws [MeshFormatException] when the file can't be read or holds no triangles.
 */
internal fun parseMesh(bytes: ByteArray, extension: String): Mesh {
  val mesh = try {
    when (extension) {
      "stl" -> parseStl(bytes)
      "obj" -> parseObj(bytes)
      "ply" -> parsePly(bytes)
      "gltf", "glb" -> parseGltf(bytes)
      else -> throw MeshFormatException("unsupported format .$extension")
    }
  } catch (e: MeshFormatException) {
    throw e
  } catch (_: IndexOutOfBoundsException) {
    throw MeshFormatException("the file ends early")
  } catch (e: IllegalArgumentException) {
    throw MeshFormatException(e.message ?: "malformed file")
  }
  if (mesh.triangleCount == 0) throw MeshFormatException("no triangles")
  val vertexCount = mesh.vertexCount
  for (index in mesh.indices) {
    if (index < 0 || index >= vertexCount) throw MeshFormatException("a face points at a vertex that doesn’t exist")
  }
  return mesh
}

/** A FloatArray that grows as values are added. */
internal class FloatBuilder(capacity: Int = 1024) {
  private var data = FloatArray(maxOf(capacity, 16))
  var size: Int = 0
    private set

  fun add(value: Float) {
    if (size == data.size) data = data.copyOf(data.size * 2)
    data[size++] = value
  }

  fun add(x: Float, y: Float, z: Float) {
    add(x)
    add(y)
    add(z)
  }

  operator fun get(index: Int): Float = data[index]

  fun clear() {
    size = 0
  }

  fun toArray(): FloatArray = data.copyOf(size)
}

/** An IntArray that grows as values are added. */
internal class IntBuilder(capacity: Int = 1024) {
  private var data = IntArray(maxOf(capacity, 16))
  var size: Int = 0
    private set

  fun add(value: Int) {
    if (size == data.size) data = data.copyOf(data.size * 2)
    data[size++] = value
  }

  fun add(a: Int, b: Int, c: Int) {
    add(a)
    add(b)
    add(c)
  }

  operator fun get(index: Int): Int = data[index]

  fun clear() {
    size = 0
  }

  fun toArray(): IntArray = data.copyOf(size)
}

/** Adds the triangles of a convex polygon as a fan around its first corner. */
internal fun IntBuilder.addFan(corners: IntBuilder) {
  for (i in 1 until corners.size - 1) add(corners[0], corners[i], corners[i + 1])
}

internal fun ByteArray.uint16Le(offset: Int): Int = (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.int32Le(offset: Int): Int = (this[offset].toInt() and 0xFF) or
  ((this[offset + 1].toInt() and 0xFF) shl 8) or
  ((this[offset + 2].toInt() and 0xFF) shl 16) or
  ((this[offset + 3].toInt() and 0xFF) shl 24)

internal fun ByteArray.uint32Le(offset: Int): Long = int32Le(offset).toLong() and 0xFFFFFFFFL

internal fun ByteArray.floatLe(offset: Int): Float = Float.fromBits(int32Le(offset))
