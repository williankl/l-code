package dev.lemonadecode.domain.review

/** What a changed file can be drawn as besides its text diff. */
public enum class PreviewKind {
  /** PNG, JPEG, GIF, WebP, BMP, ICO. */
  Image,

  /** SVG, drawn as a picture. */
  Vector,

  /** STL, OBJ, PLY, glTF and GLB, drawn as a mesh that can be turned around. */
  Model,

  /** Markdown, drawn formatted. */
  Markdown,

  /** CSV and TSV, drawn as a table. */
  Table,
  ;

  public companion object {
    private val byExtension: Map<String, PreviewKind> = buildMap {
      listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico").forEach { put(it, Image) }
      put("svg", Vector)
      listOf("stl", "obj", "ply", "gltf", "glb").forEach { put(it, Model) }
      listOf("md", "markdown", "mdown", "mkd").forEach { put(it, Markdown) }
      listOf("csv", "tsv").forEach { put(it, Table) }
    }

    /** The preview for [path], judged by its extension; null when Lemonade Code can't draw it. */
    public fun of(path: String): PreviewKind? {
      val name = path.substringAfterLast('/')
      val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
      return byExtension[extension]
    }
  }
}

/** The two sides of a change. */
public enum class FileVersion {
  /** The file at the merge base, before the change. */
  Base,

  /** The file on disk now. */
  WorkingTree,
}

/** One side of a file, read for a preview. */
public sealed interface FileContent {
  /** The file doesn't exist on this side: added files have no base, deleted files no working copy. */
  public data object Missing : FileContent

  public data class TooLarge(val sizeBytes: Long) : FileContent

  /** The file's bytes. Compared by identity: callers keep the same instance while it is unchanged. */
  public class Bytes(public val bytes: ByteArray) : FileContent {
    public val sizeBytes: Long get() = bytes.size.toLong()
  }
}
