package dev.lemonadecode.domain.update

/** A Lemonade Code version, `MAJOR.MINOR.PATCH`, as release tags (`v1.2.3`) carry it. */
public data class AppVersion(
  val major: Int,
  val minor: Int,
  val patch: Int,
) : Comparable<AppVersion> {
  override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

  override fun toString(): String = "$major.$minor.$patch"

  public companion object {
    /** `1.2.3` or `v1.2.3`; null for anything else, pre-release suffixes included. */
    public fun parse(text: String): AppVersion? {
      val parts = text.trim().removePrefix("v").split('.')
      if (parts.size != 3 || parts.any { part -> part.isEmpty() || !part.all(Char::isDigit) }) return null
      val (major, minor, patch) = parts.map { it.toIntOrNull() ?: return null }
      return AppVersion(major, minor, patch)
    }
  }
}

/** A published release, newer than the running app, with a build this installation can update from. */
public data class AppRelease(
  val version: AppVersion,
  /** The release's page on GitHub, with its notes. */
  val pageUrl: String,
  /** What's new, as plain text; null when the release says nothing. */
  val notes: String? = null,
)

/** How this installation gets new versions. */
public enum class UpdateChannel {
  /** Installed from a release file: Lemonade Code updates itself. */
  SelfUpdate,

  /** A Homebrew cask: `brew upgrade` updates it. */
  Homebrew,

  /** The Snap Store updates it. */
  Snap,

  /** Run from the repository. */
  Development,

  /** A system or processor no release has a build for. */
  Unsupported,
}

/** The running app: its version (null for a development build) and how it updates. */
public data class AppInstallation(
  val version: AppVersion?,
  val channel: UpdateChannel,
)

/** Whether a check for updates is running, and when the last one finished. */
public data class UpdateChecks(
  val checking: Boolean = false,
  /** Milliseconds since the epoch; null until a check has finished. */
  val lastCheckedMillis: Long? = null,
)

/** Where the update check and the self-update stand. */
public sealed interface UpdateState {
  /**
   * Nothing to offer: up to date, not checked yet, offline, or an installation that updates
   * another way (Homebrew, the Snap Store, a development build).
   */
  public data object None : UpdateState

  public data class Available(
    val current: AppVersion,
    val release: AppRelease,
    val progress: UpdateProgress = UpdateProgress.Ready,
  ) : UpdateState
}

public sealed interface UpdateProgress {
  /** Waiting for the user to start it. */
  public data object Ready : UpdateProgress

  /** [fraction] from 0 to 1; null until the size is known. */
  public data class Downloading(val fraction: Float?) : UpdateProgress

  /** Downloaded and checked: restarting puts the new version in place. */
  public data object Downloaded : UpdateProgress

  /** The app is quitting so the new version can take its place. */
  public data object Restarting : UpdateProgress

  public data class Failed(val reason: UpdateFailure) : UpdateProgress
}

public enum class UpdateFailure {
  /** The download didn't finish, e.g. the connection dropped. */
  Download,

  /** The file didn't match the checksum published with the release. */
  Checksum,

  /** The new version couldn't be put in place, e.g. the app's folder isn't writable. */
  Install,
}
