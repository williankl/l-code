package dev.lemonadecode.updates.api

import dev.lemonadecode.domain.update.UpdateState
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps Lemonade Code up to date when it was installed from a release file (DMG, MSI, .deb or
 * tar.gz). Installations from Homebrew or the Snap Store, and development builds, never see an
 * update here: those update their own way.
 */
public interface UpdateService {
  public val state: StateFlow<UpdateState>

  /** Looks for a newer published release with a build for this installation. Offline, nothing changes. */
  public suspend fun check()

  /**
   * Downloads the available update, checks it against the release's checksums and quits, leaving
   * a helper that puts the new version in place and opens it. Progress shows in [state].
   */
  public fun install()
}
