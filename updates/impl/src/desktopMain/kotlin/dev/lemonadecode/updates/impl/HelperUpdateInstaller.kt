package dev.lemonadecode.updates.impl

import dev.lemonadecode.platform.architecture.LemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.core.getOrThrow
import dev.lemonadecode.platform.core.onFailure
import dev.lemonadecode.platform.core.safeSuspendRunCatchingOn
import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.platform.process.ProcessRunner
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes

/**
 * Installs an update with a small script that outlives the app: it waits for the app to quit,
 * puts the new version in place and opens it again. Anything that can fail early (unpacking,
 * copying) happens here, while the app is still open to say so.
 *
 * - **macOS:** the new bundle is copied out of the DMG next to the old one, then swapped in.
 * - **Linux tar.gz:** the new folder is unpacked next to the old one, then swapped in.
 * - **Linux .deb:** `pkexec dpkg -i`, which asks for the user's password.
 * - **Windows:** `msiexec` installs the MSI over the old version, for the user, without admin rights.
 */
@ContributesBinding(AppScope::class)
@Inject
internal class HelperUpdateInstaller(
  private val processes: ProcessRunner,
  private val dispatchers: LemonadeCodeCoroutineDispatchers,
) : UpdateInstaller {
  override fun canInstall(kind: InstallKind): Boolean = when (kind) {
    is InstallKind.MacApp -> File(kind.bundle).parentFile?.canWrite() == true
    is InstallKind.LinuxFolder -> File(kind.root).canWrite() && File(kind.root).parentFile?.canWrite() == true
    is InstallKind.Deb -> onPath("pkexec") && onPath("dpkg")
    is InstallKind.WindowsMsi -> true
    InstallKind.Development, InstallKind.Homebrew, InstallKind.Snap, InstallKind.Unsupported -> false
  }

  override suspend fun start(kind: InstallKind, file: String): LemonadeCodeResult<Unit, Throwable> = safeSuspendRunCatchingOn(dispatchers.io) {
    val download = File(file)
    val pid = ProcessHandle.current().pid().toString()
    try {
      when (kind) {
        is InstallKind.MacApp -> {
          val staged = stageFromDmg(download, File(kind.bundle))
          runHelper("swap.sh", listOf(pid, kind.bundle, staged.path, "", "open", kind.bundle))
        }
        is InstallKind.LinuxFolder -> {
          val (staged, container) = stageFromTarball(download, File(kind.root), File(kind.launcher))
          runHelper("swap.sh", listOf(pid, kind.root, staged.path, container.path, kind.launcher))
        }
        is InstallKind.Deb -> runHelper("deb.sh", listOf(pid, download.path, download.parent, kind.launcher))
        is InstallKind.WindowsMsi -> runHelper(
          "msi.ps1",
          listOf("-AppPid", pid, "-Msi", download.path, "-Cleanup", download.parent, "-App", kind.launcher),
        )
        InstallKind.Development, InstallKind.Homebrew, InstallKind.Snap, InstallKind.Unsupported -> error("$kind doesn't update itself")
      }
    } catch (error: Throwable) {
      download.parentFile?.deleteRecursively()
      throw error
    }
  }.onFailure { System.err.println("Lemonade Code: couldn't install the update: ${it.message}") }

  /** Copies the app out of [dmg] to a hidden folder next to [bundle]; returns that folder. */
  private suspend fun stageFromDmg(dmg: File, bundle: File): File {
    val mount = Files.createTempDirectory("lemonade-code-dmg").toFile()
    run("hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mount.path, dmg.path)
    try {
      val app = mount.listFiles()?.firstOrNull { it.name.endsWith(".app") } ?: throw IOException("${dmg.name} holds no app")
      val staged = File(bundle.parentFile, ".${bundle.name}.update")
      staged.deleteRecursively()
      run("ditto", app.path, staged.path)
      return staged
    } finally {
      val _ = processes.run(listOf("hdiutil", "detach", mount.path, "-force"), workingDirectory = mount.parent)
      dmg.parentFile?.deleteRecursively()
    }
  }

  /**
   * Unpacks [tarball] into a hidden folder next to [root], on the same disk so the swap is a
   * rename. Returns the new app folder and the hidden folder around it.
   */
  private suspend fun stageFromTarball(tarball: File, root: File, launcher: File): Pair<File, File> {
    val container = Files.createTempDirectory(root.absoluteFile.parentFile.toPath(), ".lemonade-code-update").toFile()
    try {
      run("tar", "-xzf", tarball.path, "-C", container.path)
      val staged = container.listFiles()?.singleOrNull { it.isDirectory } ?: throw IOException("${tarball.name} isn't one app folder")
      val newLauncher = File(staged, launcher.relativeTo(root).path)
      if (!newLauncher.canExecute()) throw IOException("${tarball.name} has no ${launcher.relativeTo(root)}")
      return staged to container
    } catch (error: Throwable) {
      container.deleteRecursively()
      throw error
    } finally {
      tarball.parentFile?.deleteRecursively()
    }
  }

  /**
   * Copies the helper [script] (one of this package's resources) to a temporary file and starts
   * it with [arguments]. It outlives the app; what it prints goes to `lemonade-code-update.log`
   * in the temporary folder.
   */
  private fun runHelper(script: String, arguments: List<String>) {
    val powerShell = script.endsWith(".ps1")
    val file = File.createTempFile("lemonade-code-update", ".${script.substringAfterLast('.')}")
    val text = checkNotNull(javaClass.getResourceAsStream(script)) { "missing $script" }.use { it.readBytes() }
    file.writeBytes(text)
    val interpreter = if (powerShell) {
      listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File")
    } else {
      listOf("/bin/sh")
    }
    val _ = ProcessBuilder(interpreter + file.path + arguments)
      .redirectInput(ProcessBuilder.Redirect.from(File(if (powerShell) "NUL" else "/dev/null")))
      .redirectOutput(ProcessBuilder.Redirect.appendTo(File(file.parentFile, "lemonade-code-update.log")))
      .redirectErrorStream(true)
      .start()
  }

  private suspend fun run(vararg command: String) {
    val output = processes.run(command.toList(), workingDirectory = System.getProperty("java.io.tmpdir"), timeout = 5.minutes).getOrThrow()
    if (!output.isSuccess) throw IOException("${command.first()} failed: ${output.stderr.trim()}")
  }

  private fun onPath(program: String): Boolean = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).any { File(it, program).canExecute() }
}
