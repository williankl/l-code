package dev.lemonadecode.mobile.impl

import dev.lemonadecode.domain.mobile.MobileTools
import dev.lemonadecode.platform.architecture.LemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.platform.process.HomeDirectory
import dev.lemonadecode.platform.process.UserShell
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Looks for the tools in the places [toolCandidates] lists. The login shell is only asked for its
 * PATH when something is missing, and its answer is kept for a few minutes.
 */
@ContributesBinding(AppScope::class)
@Inject
internal class DesktopToolFinder(
  private val shell: UserShell,
  private val home: HomeDirectory,
  private val dispatchers: LemonadeCodeCoroutineDispatchers,
) : MobileToolFinder {
  private val os = HostOs.of(System.getProperty("os.name").orEmpty())

  @Volatile
  private var loginPath: Pair<Long, String?>? = null

  override suspend fun find(): MobileTools = runInterruptible(dispatchers.io) {
    val found = search(loginShellPath = null)
    if (found.adb != null && found.libimobiledevice != null) found else search(cachedLoginShellPath())
  }

  override fun ideviceTool(folder: String, tool: String): String = joinPath(os, folder, executable(os, tool))

  private fun search(loginShellPath: String?): MobileTools {
    val candidates = toolCandidates(os, System.getenv(), home.path, loginShellPath)
    return MobileTools(
      adb = candidates.adb.firstOrNull(::isProgram)?.let { File(it).absolutePath },
      libimobiledevice = candidates.idevice.firstOrNull(::isProgram)?.let { File(it).absoluteFile.parent },
    )
  }

  private fun isProgram(path: String): Boolean = File(path).let { it.isFile && it.canExecute() }

  private fun cachedLoginShellPath(): String? {
    val now = System.nanoTime()
    loginPath?.let { (readAt, path) -> if (now - readAt < LoginPathLifetimeNanos) return path }
    return readLoginShellPath().also { loginPath = now to it }
  }

  /**
   * `<shell> -lc 'echo …$PATH…'`, as a login shell sets it up. Not interactive, so nothing it
   * starts can take over a terminal. The output goes to a file, so a program the profile leaves
   * running can't hold the read open.
   */
  private fun readLoginShellPath(): String? {
    if (os == HostOs.Windows) return null
    val output = try {
      File.createTempFile("lemonade-code-path", ".txt")
    } catch (_: IOException) {
      return null
    }
    return try {
      val process = ProcessBuilder(shell.path, "-lc", "echo \"$LoginPathMarker\$PATH$LoginPathMarker\"")
        .directory(File(home.path.ifEmpty { "/" }))
        .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        .redirectOutput(output)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
      if (!process.waitFor(LoginShellTimeoutSeconds, TimeUnit.SECONDS)) {
        process.descendants().forEach { val _ = it.destroyForcibly() }
        val _ = process.destroyForcibly()
      }
      parseLoginShellPath(output.readText())
    } catch (_: IOException) {
      null
    } finally {
      val _ = output.delete()
    }
  }

  private companion object {
    const val LoginShellTimeoutSeconds = 5L
    const val LoginPathLifetimeNanos = 5L * 60 * 1_000_000_000
  }
}
