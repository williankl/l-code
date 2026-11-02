package dev.lemonadecode.updates.impl

import dev.lemonadecode.domain.update.AppVersion
import dev.lemonadecode.platform.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File

/** This process, as its launcher describes it. */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
internal class RunningApp :
  InstalledApp by InstallationDetector.detect(
    osName = System.getProperty("os.name").orEmpty(),
    osArch = System.getProperty("os.arch").orEmpty(),
    appPath = System.getProperty("jpackage.app-path"),
    // The build stamps the real version here; jpackage's is 1.x for a 0.x version on macOS.
    appVersion = System.getProperty("lemonadecode.version") ?: System.getProperty("jpackage.app-version"),
    exists = { File(it).exists() },
  )

/**
 * Tells how the app was installed from what its launcher reports. Packaged builds start from a
 * jpackage launcher, which sets `jpackage.app-path` and `jpackage.app-version`; `./gradlew run`
 * sets neither.
 */
internal object InstallationDetector {
  private val HomebrewCasks = listOf("/opt/homebrew/Caskroom/lemonade-code", "/usr/local/Caskroom/lemonade-code")
  private const val DebRoot = "/opt/lemonade-code"
  private const val DebFileList = "/var/lib/dpkg/info/lemonade-code.list"

  fun detect(
    osName: String,
    osArch: String,
    appPath: String?,
    appVersion: String?,
    exists: (String) -> Boolean,
  ): Installation {
    val version = appVersion?.let(AppVersion::parse)
    if (appPath == null || version == null) return Installation(version = null, kind = InstallKind.Development)
    val x64 = osArch == "amd64" || osArch == "x86_64"
    val kind = when {
      osName.startsWith("Mac") -> {
        // e.g. /Applications/Lemonade Code.app/Contents/MacOS/Lemonade Code
        val bundle = appPath.substringBefore(".app/", missingDelimiterValue = "").takeIf { it.isNotEmpty() }?.plus(".app")
        when {
          HomebrewCasks.any(exists) -> InstallKind.Homebrew
          bundle == null -> InstallKind.Unsupported
          osArch == "aarch64" -> InstallKind.MacApp(bundle, arch = "arm64")
          x64 -> InstallKind.MacApp(bundle, arch = "x64")
          else -> InstallKind.Unsupported
        }
      }
      osName.startsWith("Windows") -> if (x64) InstallKind.WindowsMsi(appPath) else InstallKind.Unsupported
      osName.startsWith("Linux") -> {
        // e.g. /opt/lemonade-code/bin/Lemonade Code
        val root = appPath.substringBeforeLast("/bin/", missingDelimiterValue = "")
        when {
          appPath.startsWith("/snap/") -> InstallKind.Snap
          !x64 || root.isEmpty() -> InstallKind.Unsupported
          root == DebRoot && exists(DebFileList) -> InstallKind.Deb(appPath)
          else -> InstallKind.LinuxFolder(root, appPath)
        }
      }
      else -> InstallKind.Unsupported
    }
    return Installation(version, kind)
  }
}
