package dev.lemonadecode.updates.impl

import dev.lemonadecode.domain.update.AppInstallation
import dev.lemonadecode.domain.update.AppRelease
import dev.lemonadecode.domain.update.AppVersion
import dev.lemonadecode.domain.update.UpdateChannel
import dev.lemonadecode.domain.update.UpdateChecks
import dev.lemonadecode.domain.update.UpdateFailure
import dev.lemonadecode.domain.update.UpdateProgress
import dev.lemonadecode.domain.update.UpdateState
import dev.lemonadecode.platform.architecture.AppExit
import dev.lemonadecode.platform.core.LemonadeCodeResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateServiceImplTest {
  private val current = AppVersion(1, 2, 0)
  private val kind = InstallKind.LinuxFolder("/home/you/apps/lemonade-code", "/home/you/apps/lemonade-code/bin/Lemonade Code")
  private val fileName = "lemonade-code-1.3.0-linux-x64.tar.gz"
  private val sha256 = "c".repeat(64)

  private val releases = FakeReleaseSource()
  private val installer = FakeUpdateInstaller()
  private val appExit = FakeAppExit()

  private var now = 1_000L

  private fun TestScope.service(app: InstalledApp = Installation(current, kind)) = UpdateServiceImpl(app, releases, installer, appExit, this) { now }

  @Test
  fun `a newer release with this installation's file is offered`() = runTest {
    val service = service()
    service.check()
    val available = assertIs<UpdateState.Available>(service.state.value)
    assertEquals(current, available.current)
    assertEquals(AppRelease(AppVersion(1, 3, 0), "https://github.com/williankl/loupe/releases/tag/v1.3.0"), available.release)
    assertEquals(UpdateProgress.Ready, available.progress)
  }

  @Test
  fun `installations that update another way never ask GitHub`() = runTest {
    listOf(
      Installation(current, InstallKind.Homebrew),
      Installation(current, InstallKind.Snap),
      Installation(current, InstallKind.Unsupported),
      Installation(null, InstallKind.Development),
    ).forEach { app ->
      val service = service(app)
      service.check()
      assertEquals(UpdateState.None, service.state.value)
    }
    assertEquals(0, releases.listCount)
  }

  @Test
  fun `offline, nothing changes`() = runTest {
    val service = service()
    service.check()
    releases.listed = LemonadeCodeResult.failure(IllegalStateException("offline"))
    service.check()
    assertIs<UpdateState.Available>(service.state.value)
  }

  @Test
  fun `a check says when it ran and that it is running`() = runTest {
    val service = service()
    assertEquals(UpdateChecks(), service.checks.value)
    val gate = CompletableDeferred<Unit>()
    releases.beforeListing = gate
    val checking = launch { service.check() }
    advanceUntilIdle()
    assertEquals(UpdateChecks(checking = true), service.checks.value)
    now = 5_000L
    gate.complete(Unit)
    checking.join()
    assertEquals(UpdateChecks(checking = false, lastCheckedMillis = 5_000L), service.checks.value)
  }

  @Test
  fun `offline, the last check time stays`() = runTest {
    releases.listed = LemonadeCodeResult.failure(IllegalStateException("offline"))
    val service = service()
    service.check()
    assertNull(service.checks.value.lastCheckedMillis)
    assertFalse(service.checks.value.checking)
  }

  @Test
  fun `the installation says how it updates`() = runTest {
    assertEquals(AppInstallation(current, UpdateChannel.SelfUpdate), service().installation)
    assertEquals(UpdateChannel.Homebrew, service(Installation(current, InstallKind.Homebrew)).installation.channel)
    assertEquals(AppInstallation(null, UpdateChannel.Development), service(Installation(null, InstallKind.Development)).installation)
  }

  @Test
  fun `the release notes come as plain text`() = runTest {
    releases.listed = LemonadeCodeResult.success(listOf(release("v1.3.0", body = "## What's new\n\n- **Mobile** plugin\n- See [the docs](https://x.y)\n")))
    val service = service()
    service.check()
    assertEquals("What's new\n• Mobile plugin\n• See the docs", assertIs<UpdateState.Available>(service.state.value).release.notes)
  }

  @Test
  fun `installing downloads the file and checks it against the listed checksum, then waits for the restart`() = runTest {
    val service = service()
    service.check()
    service.install()
    advanceUntilIdle()
    assertEquals(listOf(fileName to sha256), releases.downloads.map { it.first.name to it.second })
    assertEquals(UpdateProgress.Downloaded, assertIs<UpdateState.Available>(service.state.value).progress)
    assertTrue(installer.started.isEmpty())
    assertEquals(0, appExit.exits)

    service.restart()
    advanceUntilIdle()
    assertEquals(listOf<Pair<InstallKind, String>>(kind to "/tmp/lemonade-code-update/$fileName"), installer.started)
    assertEquals(UpdateProgress.Restarting, assertIs<UpdateState.Available>(service.state.value).progress)
    assertEquals(1, appExit.exits)
  }

  @Test
  fun `restarting before the download is done does nothing`() = runTest {
    val service = service()
    service.check()
    service.restart()
    advanceUntilIdle()
    assertEquals(UpdateProgress.Ready, assertIs<UpdateState.Available>(service.state.value).progress)
    assertEquals(0, appExit.exits)
  }

  @Test
  fun `progress shows while downloading`() = runTest {
    val service = service()
    service.check()
    val gate = CompletableDeferred<Unit>()
    releases.beforeFinishing = gate
    service.install()
    advanceUntilIdle()
    assertEquals(UpdateProgress.Downloading(0.5f), assertIs<UpdateState.Available>(service.state.value).progress)
    gate.complete(Unit)
    advanceUntilIdle()
    assertEquals(UpdateProgress.Downloaded, assertIs<UpdateState.Available>(service.state.value).progress)
  }

  @Test
  fun `a file the checksums don't list isn't downloaded`() = runTest {
    releases.checksums = "${"d".repeat(64)}  something-else.tar.gz"
    val service = service()
    service.check()
    service.install()
    advanceUntilIdle()
    assertEquals(UpdateProgress.Failed(UpdateFailure.Checksum), assertIs<UpdateState.Available>(service.state.value).progress)
    assertTrue(releases.downloads.isEmpty())
    assertEquals(0, appExit.exits)
  }

  @Test
  fun `a failed download can be tried again`() = runTest {
    releases.downloadResult = LemonadeCodeResult.failure(UpdateFailure.Download)
    val service = service()
    service.check()
    service.install()
    advanceUntilIdle()
    assertEquals(UpdateProgress.Failed(UpdateFailure.Download), assertIs<UpdateState.Available>(service.state.value).progress)

    releases.downloadResult = LemonadeCodeResult.success("/tmp/lemonade-code-update/$fileName")
    service.install()
    advanceUntilIdle()
    assertEquals(2, releases.downloads.size)
    assertEquals(UpdateProgress.Downloaded, assertIs<UpdateState.Available>(service.state.value).progress)
  }

  @Test
  fun `a folder the app can't write to fails before downloading`() = runTest {
    installer.canInstall = false
    val service = service()
    service.check()
    service.install()
    advanceUntilIdle()
    assertEquals(UpdateProgress.Failed(UpdateFailure.Install), assertIs<UpdateState.Available>(service.state.value).progress)
    assertTrue(releases.downloads.isEmpty())
  }

  @Test
  fun `if the installer can't start, the app stays open`() = runTest {
    installer.result = LemonadeCodeResult.failure(IllegalStateException("tar failed"))
    val service = service()
    service.check()
    service.install()
    advanceUntilIdle()
    service.restart()
    advanceUntilIdle()
    assertEquals(UpdateProgress.Failed(UpdateFailure.Install), assertIs<UpdateState.Available>(service.state.value).progress)
    assertEquals(0, appExit.exits)
  }

  @Test
  fun `a check during a download keeps the release being downloaded`() = runTest {
    val service = service()
    service.check()
    val gate = CompletableDeferred<Unit>()
    releases.beforeFinishing = gate
    service.install()
    advanceUntilIdle()
    releases.listed = LemonadeCodeResult.success(listOf(release("v1.4.0")))
    service.check()
    assertEquals(AppVersion(1, 3, 0), assertIs<UpdateState.Available>(service.state.value).release.version)
    gate.complete(Unit)
  }

  @Test
  fun `a check after the download keeps the release waiting for the restart`() = runTest {
    val service = service()
    service.check()
    service.install()
    advanceUntilIdle()
    releases.listed = LemonadeCodeResult.success(listOf(release("v1.4.0")))
    service.check()
    val available = assertIs<UpdateState.Available>(service.state.value)
    assertEquals(AppVersion(1, 3, 0), available.release.version)
    assertEquals(UpdateProgress.Downloaded, available.progress)
  }

  private fun release(tag: String, body: String? = null): PublishedRelease {
    val version = tag.removePrefix("v")
    return PublishedRelease(
      tag = tag,
      draft = false,
      prerelease = false,
      pageUrl = "https://github.com/williankl/loupe/releases/tag/$tag",
      assets = listOf("lemonade-code-$version-linux-x64.tar.gz", ReleaseChooser.ChecksumsFile).map { ReleaseAsset(it, "https://example.com/$it", 100) },
      body = body,
    )
  }

  private inner class FakeReleaseSource : ReleaseSource {
    var listed: LemonadeCodeResult<List<PublishedRelease>, Throwable> = LemonadeCodeResult.success(listOf(release("v1.3.0"), release("v1.1.0")))
    var checksums = "$sha256  $fileName\n${"e".repeat(64)}  ${ReleaseChooser.ChecksumsFile}\n"
    var downloadResult: LemonadeCodeResult<String, UpdateFailure> = LemonadeCodeResult.success("/tmp/lemonade-code-update/$fileName")

    /** When set, downloads report half done and wait for it. */
    var beforeFinishing: CompletableDeferred<Unit>? = null

    /** When set, listing the releases waits for it. */
    var beforeListing: CompletableDeferred<Unit>? = null
    var listCount = 0
    val downloads = mutableListOf<Pair<ReleaseAsset, String>>()

    override suspend fun releases(): LemonadeCodeResult<List<PublishedRelease>, Throwable> {
      listCount++
      beforeListing?.await()
      return listed
    }

    override suspend fun text(asset: ReleaseAsset): LemonadeCodeResult<String, Throwable> = LemonadeCodeResult.success(checksums)

    override suspend fun download(asset: ReleaseAsset, sha256: String, onProgress: (Float?) -> Unit): LemonadeCodeResult<String, UpdateFailure> {
      downloads += asset to sha256
      onProgress(0.5f)
      beforeFinishing?.await()
      return downloadResult
    }
  }

  private class FakeUpdateInstaller : UpdateInstaller {
    var canInstall = true
    var result: LemonadeCodeResult<Unit, Throwable> = LemonadeCodeResult.success(Unit)
    val started = mutableListOf<Pair<InstallKind, String>>()

    override fun canInstall(kind: InstallKind): Boolean = canInstall

    override suspend fun start(kind: InstallKind, file: String): LemonadeCodeResult<Unit, Throwable> {
      started += kind to file
      return result
    }
  }

  private class FakeAppExit : AppExit {
    var exits = 0

    override fun exit() {
      exits++
    }
  }
}
