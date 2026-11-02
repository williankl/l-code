package dev.lemonadecode.updates.api

import dev.lemonadecode.domain.update.AppInstallation
import dev.lemonadecode.domain.update.UpdateChecks
import dev.lemonadecode.domain.update.UpdateState
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps Lemonade Code up to date when it was installed from a release file (DMG, MSI, .deb or
 * tar.gz). Installations from Homebrew or the Snap Store, and development builds, never see an
 * update here: those update their own way.
 */
public interface UpdateService {
  public val state: StateFlow<UpdateState>

  /** The version running now and how it gets new ones. */
  public val installation: AppInstallation

  public val checks: StateFlow<UpdateChecks>

  /** Looks for a newer published release with a build for this installation. Offline, nothing changes. */
  public suspend fun check()

  /**
   * Downloads the available update and checks it against the release's checksums. Progress shows
   * in [state], which ends at [dev.lemonadecode.domain.update.UpdateProgress.Downloaded].
   */
  public fun install()

  /**
   * Quits once the update is downloaded, leaving a helper that puts the new version in place and
   * opens it. Sessions are saved on the way out and come back after.
   */
  public fun restart()
}
