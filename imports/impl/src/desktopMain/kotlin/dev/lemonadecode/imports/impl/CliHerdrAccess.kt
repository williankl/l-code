package dev.lemonadecode.imports.impl

import dev.lemonadecode.platform.architecture.LemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.platform.process.HomeDirectory
import dev.lemonadecode.platform.process.ProcessRunner
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * The installed `herdr` program, asked only `herdr session list --json`, which lists its sessions
 * and touches none of them, and its files, read and never written.
 */
@ContributesBinding(AppScope::class)
internal class CliHerdrAccess(
  private val processes: ProcessRunner,
  private val home: HomeDirectory,
  private val dispatchers: LemonadeCodeCoroutineDispatchers,
  /** Where `herdr` may be, in the order tried. */
  private val candidates: () -> List<String>,
) : HerdrAccess {
  @Inject
  constructor(processes: ProcessRunner, home: HomeDirectory, dispatchers: LemonadeCodeCoroutineDispatchers) :
    this(processes, home, dispatchers, { herdrCandidates(System.getenv(), home.path, IsWindows) })

  override suspend fun listSessions(): HerdrOutput {
    val program = withContext(dispatchers.io) { candidates().firstOrNull { File(it).let { file -> file.isFile && file.canExecute() } } }
      ?: return HerdrOutput.NotInstalled
    return when (val ran = processes.run(listOf(program, "session", "list", "--json"), workingDirectory = home.path, timeout = ListTimeout)) {
      is LemonadeCodeResult.Success -> {
        val output = ran.value
        if (output.isSuccess) {
          HerdrOutput.Printed(output.stdout)
        } else {
          HerdrOutput.Failed(output.stderr.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() })
        }
      }
      is LemonadeCodeResult.Failure -> HerdrOutput.Failed(ran.error.message)
    }
  }

  override suspend fun readText(path: String): String? = withContext(dispatchers.io) {
    File(path).takeIf { it.isFile }?.readText()
  }

  private companion object {
    val ListTimeout = 15.seconds
    val IsWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")
  }
}

/**
 * Where `herdr` may be: on the PATH, then where its installer, Cargo, Homebrew, Nix and mise put
 * it, since an app opened from the Dock or a launcher gets a short PATH.
 */
internal fun herdrCandidates(environment: Map<String, String>, home: String, windows: Boolean): List<String> {
  val path = environment.entries.firstOrNull { it.key.equals("PATH", ignoreCase = windows) }?.value.orEmpty()
  val onPath = path.split(if (windows) ';' else ':').map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }
  val usual = if (windows) {
    emptyList()
  } else {
    listOf(
      "$home/.local/bin",
      "$home/.cargo/bin",
      "/opt/homebrew/bin",
      "/usr/local/bin",
      "$home/.nix-profile/bin",
      "/run/current-system/sw/bin",
      "$home/.local/share/mise/shims",
      "/usr/bin",
    )
  }
  val separator = if (windows) "\\" else "/"
  val program = if (windows) "herdr.exe" else "herdr"
  return (onPath + usual).map { it.trimEnd('/', '\\') }.distinct().map { it + separator + program }
}
