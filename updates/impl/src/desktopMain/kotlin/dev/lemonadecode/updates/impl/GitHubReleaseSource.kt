package dev.lemonadecode.updates.impl

import dev.lemonadecode.domain.update.UpdateFailure
import dev.lemonadecode.platform.architecture.LemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.core.safeSuspendRunCatching
import dev.lemonadecode.platform.core.safeSuspendRunCatchingOn
import dev.lemonadecode.platform.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The releases of the app's GitHub repository, read without signing in, so the repository has to
 * be public. Uses `HttpURLConnection` rather than `java.net.http`, which the bundled Java runtime
 * leaves out.
 */
@ContributesBinding(AppScope::class)
@Inject
internal class GitHubReleaseSource(
  private val app: InstalledApp,
  private val dispatchers: LemonadeCodeCoroutineDispatchers,
) : ReleaseSource {
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun releases(): LemonadeCodeResult<List<PublishedRelease>, Throwable> = safeSuspendRunCatchingOn(dispatchers.io) {
    val body = open(ReleasesUrl, accept = "application/vnd.github+json").use { it.readBytes().decodeToString() }
    json.decodeFromString(ListSerializer(GitHubRelease.serializer()), body).map { it.toDomain() }
  }

  override suspend fun text(asset: ReleaseAsset): LemonadeCodeResult<String, Throwable> = safeSuspendRunCatchingOn(dispatchers.io) {
    open(asset.url).use { it.readBytes().decodeToString() }
  }

  override suspend fun download(
    asset: ReleaseAsset,
    sha256: String,
    onProgress: (Float?) -> Unit,
  ): LemonadeCodeResult<String, UpdateFailure> = withContext(dispatchers.io) {
    val folder = Files.createTempDirectory("lemonade-code-update")
    val file = folder.resolve(asset.name)
    val digest = MessageDigest.getInstance("SHA-256")
    val saved = safeSuspendRunCatching {
      val connection = connect(asset.url, accept = "application/octet-stream")
      val total = connection.contentLengthLong.takeIf { it > 0 } ?: asset.size.takeIf { it > 0 }
      onProgress(total?.let { 0f })
      connection.inputStream.use { input ->
        Files.newOutputStream(file).use { output ->
          val buffer = ByteArray(BufferSize)
          var received = 0L
          var percent = 0
          while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            received += count
            // Once per percent, not once per buffer.
            if (total != null && received * 100 / total > percent) {
              percent = (received * 100 / total).toInt()
              onProgress((received.toFloat() / total).coerceAtMost(1f))
            }
          }
        }
      }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    when {
      saved is LemonadeCodeResult.Failure -> {
        System.err.println("Lemonade Code: couldn't download ${asset.url}: ${saved.error.message}")
        folder.toFile().deleteRecursively()
        LemonadeCodeResult.failure(UpdateFailure.Download)
      }
      actual != sha256 -> {
        System.err.println("Lemonade Code: ${asset.name} has SHA-256 $actual, the release lists $sha256")
        folder.toFile().deleteRecursively()
        LemonadeCodeResult.failure(UpdateFailure.Checksum)
      }
      else -> LemonadeCodeResult.success(file.toString())
    }
  }

  private fun open(url: String, accept: String = "application/octet-stream"): InputStream = connect(url, accept).inputStream

  /** GETs [url], following redirects (release files are served from another host), and fails on anything but 2xx. */
  private fun connect(url: String, accept: String): HttpURLConnection {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = TimeoutMillis
    connection.readTimeout = TimeoutMillis
    connection.instanceFollowRedirects = true
    connection.setRequestProperty("Accept", accept)
    connection.setRequestProperty("User-Agent", "Lemonade-Code/${app.version ?: "dev"}")
    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
    val status = connection.responseCode
    if (status !in 200..299) {
      connection.disconnect()
      throw IOException("GET $url answered $status")
    }
    return connection
  }

  private companion object {
    /** Where releases are published; see RELEASING.md. */
    const val Repository = "williankl/loupe"
    const val ReleasesUrl = "https://api.github.com/repos/$Repository/releases?per_page=30"
    const val TimeoutMillis = 30_000
    const val BufferSize = 64 * 1024
  }
}

@Serializable
private data class GitHubRelease(
  @SerialName("tag_name") val tag: String,
  val draft: Boolean = false,
  val prerelease: Boolean = false,
  @SerialName("html_url") val pageUrl: String = "",
  val assets: List<GitHubAsset> = emptyList(),
) {
  fun toDomain(): PublishedRelease = PublishedRelease(
    tag = tag,
    draft = draft,
    prerelease = prerelease,
    pageUrl = pageUrl,
    assets = assets.map { ReleaseAsset(name = it.name, url = it.url, size = it.size, complete = it.state == "uploaded") },
  )
}

@Serializable
private data class GitHubAsset(
  val name: String,
  @SerialName("browser_download_url") val url: String,
  val size: Long = 0,
  val state: String = "uploaded",
)
