package dev.lemonadecode.updates.impl

import com.sun.net.httpserver.HttpServer
import dev.lemonadecode.domain.update.AppVersion
import dev.lemonadecode.domain.update.UpdateFailure
import dev.lemonadecode.platform.architecture.defaultCoroutineDispatchers
import dev.lemonadecode.platform.core.LemonadeCodeResult
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Downloads from a local server standing in for GitHub's release file host. */
class GitHubReleaseSourceTest {
  private val bytes = ByteArray(300_000) { (it % 251).toByte() }
  private val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/file") { exchange ->
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    createContext("/SHA256SUMS") { exchange ->
      val listing = "$sha256  lemonade-code-1.3.0-linux-x64.tar.gz\n".toByteArray()
      exchange.sendResponseHeaders(200, listing.size.toLong())
      exchange.responseBody.use { it.write(listing) }
    }
    // GitHub answers a release file's URL with a redirect to where it's stored.
    createContext("/download") { exchange ->
      exchange.responseHeaders.add("Location", "/file")
      exchange.sendResponseHeaders(302, -1)
      exchange.close()
    }
    start()
  }
  private val base = "http://127.0.0.1:${server.address.port}"
  private val source = GitHubReleaseSource(
    Installation(AppVersion(1, 2, 0), InstallKind.LinuxFolder("/apps/lemonade-code", "/apps/lemonade-code/bin/Lemonade Code")),
    defaultCoroutineDispatchers(),
  )

  @AfterTest
  fun stop() = server.stop(0)

  @Test
  fun `a download that matches its checksum is kept, following the redirect`() = runBlocking<Unit> {
    val progress = mutableListOf<Float?>()
    val saved = source.download(asset("$base/download"), sha256) { progress += it }
    val file = File(assertIs<LemonadeCodeResult.Success<String>>(saved).value)
    assertEquals("lemonade-code-1.3.0-linux-x64.tar.gz", file.name)
    assertContentEquals(bytes, file.readBytes())
    assertEquals(0f, progress.first())
    assertEquals(1f, progress.last())
    file.parentFile.deleteRecursively()
  }

  @Test
  fun `a download that doesn't match is thrown away`() = runBlocking<Unit> {
    val before = tempUpdateFolders()
    val saved = source.download(asset("$base/file"), "0".repeat(64)) {}
    assertEquals(LemonadeCodeResult.failure(UpdateFailure.Checksum), saved)
    assertEquals(before, tempUpdateFolders())
  }

  @Test
  fun `a missing file is a failed download`() = runBlocking<Unit> {
    assertEquals(LemonadeCodeResult.failure(UpdateFailure.Download), source.download(asset("$base/nothing-here"), sha256) {})
  }

  @Test
  fun `the checksums file is read as text`() = runBlocking<Unit> {
    val listing = source.text(asset("$base/SHA256SUMS"))
    assertEquals(LemonadeCodeResult.success("$sha256  lemonade-code-1.3.0-linux-x64.tar.gz\n"), listing)
  }

  private fun asset(url: String) = ReleaseAsset("lemonade-code-1.3.0-linux-x64.tar.gz", url, size = bytes.size.toLong())

  private fun tempUpdateFolders(): Set<String> = File(System.getProperty("java.io.tmpdir")).list()?.filter { it.startsWith("lemonade-code-update") }?.toSet().orEmpty()
}
