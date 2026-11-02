package dev.lemonadecode.mobile.impl

internal enum class HostOs {
  Linux,
  Mac,
  Windows,
  ;

  internal companion object {
    /** From `os.name`. */
    fun of(name: String): HostOs = when {
      name.startsWith("Mac") || name.startsWith("Darwin") -> Mac
      name.startsWith("Windows") -> Windows
      else -> Linux
    }
  }
}

/** Where adb and libimobiledevice's `idevice_id` may be, in the order they are tried. */
internal data class ToolCandidates(val adb: List<String>, val idevice: List<String>)

/**
 * The places to look for the tools. An app opened from a launcher or Finder gets a short PATH, so
 * besides the PATH this tries the Android SDK (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, then where
 * Android Studio puts it), Homebrew's folders, and the PATH the user's login shell sets up.
 */
internal fun toolCandidates(os: HostOs, environment: Map<String, String>, home: String, loginShellPath: String?): ToolCandidates {
  val windows = os == HostOs.Windows
  fun variable(name: String) = environment.entries.firstOrNull { it.key.equals(name, ignoreCase = windows) }?.value?.takeIf { it.isNotBlank() }
  fun folders(path: String?) = path.orEmpty().split(if (windows) ';' else ':')
    .map { it.trim().removeSurrounding("\"") }
    .filter { it.isNotEmpty() }
  val path = folders(variable("PATH"))
  val login = if (windows) emptyList() else folders(loginShellPath)
  val defaultSdk = when (os) {
    HostOs.Linux -> joinPath(os, home, "Android/Sdk")
    HostOs.Mac -> joinPath(os, home, "Library/Android/sdk")
    HostOs.Windows -> joinPath(os, variable("LOCALAPPDATA") ?: joinPath(os, home, "AppData\\Local"), "Android\\Sdk")
  }
  val sdks = listOfNotNull(variable("ANDROID_HOME"), variable("ANDROID_SDK_ROOT"), defaultSdk).map { joinPath(os, it, "platform-tools") }
  val packageManagers = if (windows) emptyList() else listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
  val adbFolders = (path + sdks + packageManagers + login).distinct()
  val ideviceFolders = (path + packageManagers + login).distinct()
  return ToolCandidates(
    adb = adbFolders.map { joinPath(os, it, executable(os, "adb")) },
    idevice = ideviceFolders.map { joinPath(os, it, executable(os, "idevice_id")) },
  )
}

/** [name] as a program file: `adb.exe` on Windows. */
internal fun executable(os: HostOs, name: String): String = if (os == HostOs.Windows) "$name.exe" else name

internal fun joinPath(os: HostOs, folder: String, name: String): String {
  val separator = if (os == HostOs.Windows) '\\' else '/'
  return folder.trimEnd('/', '\\') + separator + name.replace(if (separator == '/') '\\' else '/', separator)
}

/** The folder holding [file]. */
internal fun folderOf(file: String): String = file.substring(0, maxOf(file.lastIndexOf('/'), file.lastIndexOf('\\')).coerceAtLeast(0))

/** What the login shell printed between two [LoginPathMarker]s; null when it printed nothing usable. */
internal fun parseLoginShellPath(output: String): String? {
  val start = output.indexOf(LoginPathMarker).takeIf { it >= 0 } ?: return null
  val end = output.indexOf(LoginPathMarker, start + LoginPathMarker.length).takeIf { it >= 0 } ?: return null
  return output.substring(start + LoginPathMarker.length, end).trim().takeIf { it.isNotEmpty() }
}

/**
 * Brackets the PATH the login shell prints, so whatever its startup files print around it is
 * ignored. `@` ends a variable name in sh, bash, zsh and fish alike.
 */
internal const val LoginPathMarker: String = "@@lemonade-code-path@@"
