package dev.lemonadecode.workspace.impl

import dev.lemonadecode.domain.review.FileContent
import dev.lemonadecode.domain.review.FileVersion
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.platform.core.getOrNull
import dev.lemonadecode.review.api.ReviewService
import dev.lemonadecode.workspace.uimodel.PreviewAssetLoader
import dev.lemonadecode.workspace.uimodel.PreviewVersion

/** What [sessionId]'s previews refer to, read through the review: files in its checkout, and pictures on the web. */
internal class ReviewPreviewAssets(
  private val reviews: ReviewService,
  private val sessionId: SessionId,
) : PreviewAssetLoader {
  override suspend fun checkoutFile(path: String, version: PreviewVersion): ByteArray? {
    val side = when (version) {
      PreviewVersion.Before -> FileVersion.Base
      PreviewVersion.After -> FileVersion.WorkingTree
    }
    return (reviews.checkoutFile(sessionId, path, side).getOrNull() as? FileContent.Bytes)?.bytes
  }

  override suspend fun webImage(url: String): ByteArray? = (reviews.webImage(url).getOrNull() as? FileContent.Bytes)?.bytes
}
