package dev.lemonadecode.updates.impl

import dev.lemonadecode.domain.update.AppInstallation
import dev.lemonadecode.domain.update.AppRelease
import dev.lemonadecode.domain.update.UpdateChecks
import dev.lemonadecode.domain.update.UpdateFailure
import dev.lemonadecode.domain.update.UpdateProgress
import dev.lemonadecode.domain.update.UpdateState
import dev.lemonadecode.platform.architecture.AppExit
import dev.lemonadecode.platform.architecture.WallClock
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.core.getOrNull
import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.updates.api.UpdateService
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where a release file is listed and fetched from: GitHub. */
internal interface ReleaseSource {
  suspend fun releases(): LemonadeCodeResult<List<PublishedRelease>, Throwable>

  suspend fun text(asset: ReleaseAsset): LemonadeCodeResult<String, Throwable>

  /**
   * Downloads [asset] into a new temporary folder and returns the file's path, reporting how much
   * has arrived. Fails with [UpdateFailure.Checksum] when the file's SHA-256 isn't [sha256].
   */
  suspend fun download(asset: ReleaseAsset, sha256: String, onProgress: (Float?) -> Unit): LemonadeCodeResult<String, UpdateFailure>
}

/** Puts a downloaded update in place of the running app, through a helper that outlives it. */
internal interface UpdateInstaller {
  /** Whether [kind] can be updated here, asked before downloading: its folder is writable, its tools exist. */
  fun canInstall(kind: InstallKind): Boolean

  /**
   * Gets [file] ready for [kind] and starts the helper, which waits for the app to quit, installs
   * the new version and opens it. The app quits once this succeeds.
   */
  suspend fun start(kind: InstallKind, file: String): LemonadeCodeResult<Unit, Throwable>
}

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
internal class UpdateServiceImpl(
  private val app: InstalledApp,
  private val releases: ReleaseSource,
  private val installer: UpdateInstaller,
  private val appExit: AppExit,
  private val scope: CoroutineScope,
  private val clock: WallClock,
) : UpdateService {
  private val mutableState = MutableStateFlow<UpdateState>(UpdateState.None)
  override val state: StateFlow<UpdateState> = mutableState.asStateFlow()

  override val installation: AppInstallation = AppInstallation(app.version, app.kind.channel)

  private val mutableChecks = MutableStateFlow(UpdateChecks())
  override val checks: StateFlow<UpdateChecks> = mutableChecks.asStateFlow()

  private val lock = Mutex()

  /** The release [state] offers, with its files. */
  private var candidate: UpdateCandidate? = null

  /** The update, once downloaded and checked. */
  private var downloaded: String? = null

  override suspend fun check() {
    val current = app.version ?: return
    if (app.kind.assetName(current) == null) return
    mutableChecks.update { it.copy(checking = true) }
    try {
      val listed = releases.releases().getOrNull() ?: return
      val newest = ReleaseChooser.newest(listed, current, app.kind::assetName)
      lock.withLock {
        mutableChecks.update { it.copy(lastCheckedMillis = clock.nowMillis()) }
        // A download in progress, or one waiting for the restart, keeps the release it started with.
        if (isBusy()) return
        candidate = newest
        mutableState.update { previous ->
          when {
            newest == null -> UpdateState.None
            previous is UpdateState.Available && previous.release.version == newest.version -> previous
            else -> UpdateState.Available(current, AppRelease(newest.version, newest.pageUrl, newest.notes))
          }
        }
      }
    } finally {
      mutableChecks.update { it.copy(checking = false) }
    }
  }

  override fun install() {
    scope.launch { installAvailable() }
  }

  override fun restart() {
    scope.launch {
      val file = lock.withLock {
        val file = downloaded?.takeIf { progress() == UpdateProgress.Downloaded } ?: return@launch
        setProgress(UpdateProgress.Restarting)
        file
      }
      if (installer.start(app.kind, file).isFailure) return@launch fail(UpdateFailure.Install)
      appExit.exit()
    }
  }

  private suspend fun installAvailable() {
    val update = lock.withLock {
      val update = candidate?.takeIf { mutableState.value is UpdateState.Available && !isBusy() } ?: return
      setProgress(UpdateProgress.Downloading(fraction = null))
      update
    }
    if (!installer.canInstall(app.kind)) return fail(UpdateFailure.Install)
    val listing = releases.text(update.checksums).getOrNull() ?: return fail(UpdateFailure.Download)
    val sha256 = ReleaseChooser.checksumOf(listing, update.asset.name) ?: return fail(UpdateFailure.Checksum)
    val file = when (val downloaded = releases.download(update.asset, sha256) { setProgress(UpdateProgress.Downloading(it)) }) {
      is LemonadeCodeResult.Success -> downloaded.value
      is LemonadeCodeResult.Failure -> return fail(downloaded.error)
    }
    lock.withLock {
      downloaded = file
      setProgress(UpdateProgress.Downloaded)
    }
  }

  private fun progress(): UpdateProgress? = (mutableState.value as? UpdateState.Available)?.progress

  private fun isBusy(): Boolean = when (progress()) {
    is UpdateProgress.Downloading, UpdateProgress.Downloaded, UpdateProgress.Restarting -> true
    else -> false
  }

  private fun setProgress(progress: UpdateProgress) = mutableState.update { (it as? UpdateState.Available)?.copy(progress = progress) ?: it }

  private fun fail(reason: UpdateFailure) = setProgress(UpdateProgress.Failed(reason))
}
