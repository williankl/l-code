package dev.lemonadecode.updates.impl

import dev.lemonadecode.domain.update.AppVersion

/** A release as GitHub lists it. */
internal data class PublishedRelease(
  val tag: String,
  val draft: Boolean,
  val prerelease: Boolean,
  val pageUrl: String,
  val assets: List<ReleaseAsset>,
)

/** A file attached to a release. [complete] is false while it is still uploading. */
internal data class ReleaseAsset(
  val name: String,
  val url: String,
  val size: Long,
  val complete: Boolean = true,
)

/** The release to update to, with the file to download and the checksums to check it against. */
internal data class UpdateCandidate(
  val version: AppVersion,
  val pageUrl: String,
  val asset: ReleaseAsset,
  val checksums: ReleaseAsset,
)

internal object ReleaseChooser {
  /** The checksums file every release carries, written by `sha256sum`. */
  const val ChecksumsFile: String = "SHA256SUMS"

  /**
   * The newest published release above [current] that has both the file [assetName] names for it
   * and the checksums; null when there is none. Drafts and pre-releases don't count.
   */
  fun newest(releases: List<PublishedRelease>, current: AppVersion, assetName: (AppVersion) -> String?): UpdateCandidate? = releases
    .asSequence()
    .filter { !it.draft && !it.prerelease }
    .mapNotNull { release ->
      val version = AppVersion.parse(release.tag)?.takeIf { it > current } ?: return@mapNotNull null
      val name = assetName(version) ?: return@mapNotNull null
      val uploaded = release.assets.filter { it.complete }
      val asset = uploaded.firstOrNull { it.name == name } ?: return@mapNotNull null
      val checksums = uploaded.firstOrNull { it.name == ChecksumsFile } ?: return@mapNotNull null
      UpdateCandidate(version, release.pageUrl, asset, checksums)
    }
    .maxByOrNull { it.version }

  /** [file]'s SHA-256 in a `sha256sum` listing, in lowercase; null when it isn't listed. */
  fun checksumOf(listing: String, file: String): String? = listing.lineSequence()
    .mapNotNull { line ->
      val parts = line.trim().split(Whitespace, limit = 2)
      val hash = parts.first().lowercase()
      val listed = parts.getOrNull(1)?.removePrefix("*")
      hash.takeIf { listed == file && it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }
    .firstOrNull()

  private val Whitespace = Regex("\\s+")
}
