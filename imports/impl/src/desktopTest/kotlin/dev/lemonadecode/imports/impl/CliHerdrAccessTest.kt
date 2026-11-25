package dev.lemonadecode.imports.impl

import dev.lemonadecode.domain.session.SourceScan
import dev.lemonadecode.platform.architecture.testing.FakeLemonadeCodeCoroutineDispatchers
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.process.BinaryProcessOutput
import dev.lemonadecode.platform.process.HomeDirectory
import dev.lemonadecode.platform.process.ProcessOutput
import dev.lemonadecode.platform.process.ProcessRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration

class CliHerdrAccessTest {
  private val folder = Files.createTempDirectory("lemonade-code-herdr").toFile()
  private val home = object : HomeDirectory {
    override val path: String = folder.path
  }
  private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

  /** Runs programs for real, as the app's own runner does. */
  private val processes = object : ProcessRunner {
    override suspend fun run(command: List<String>, workingDirectory: String, timeout: Duration): LemonadeCodeResult<ProcessOutput, Throwable> {
      val process = ProcessBuilder(command).directory(File(workingDirectory)).start()
      val stdout = process.inputStream.readBytes().decodeToString()
      val stderr = process.errorStream.readBytes().decodeToString()
      return LemonadeCodeResult.success(ProcessOutput(process.waitFor(), stdout, stderr))
    }

    override suspend fun runForBytes(command: List<String>, workingDirectory: String, timeout: Duration): LemonadeCodeResult<BinaryProcessOutput, Throwable> = error("not used")
  }

  private fun access(vararg candidates: String) = CliHerdrAccess(processes, home, FakeLemonadeCodeCoroutineDispatchers(Dispatchers.IO)) { candidates.toList() }

  /** A stand-in `herdr` that answers only `session list --json`, with a session kept in [folder]. */
  private fun fakeHerdr(): String {
    val sessionDir = File(folder, "state").apply { mkdirs() }
    File(sessionDir, "session.json").writeText(HerdrFixtures.workLayout)
    val list = HerdrFixtures.sessionList.replace(HerdrFixtures.SessionDir, sessionDir.path)
    val script = File(folder, "herdr")
    script.writeText(
      """
      |#!/bin/sh
      |if [ "${'$'}*" = "session list --json" ]; then
      |  cat <<'EOF'
      |$list
      |EOF
      |else
      |  echo "unexpected: ${'$'}*" >&2
      |  exit 2
      |fi
      """.trimMargin(),
    )
    check(script.setExecutable(true))
    return script.path
  }

  @Test
  fun `the first herdr found lists its sessions, and their layouts are read`() = runBlocking {
    if (windows) return@runBlocking
    val herdr = fakeHerdr()
    val source = HerdrSessionSource(access(File(folder, "missing/herdr").path, herdr), home)
    val found = assertIs<SourceScan.Found>(source.read())
    assertEquals(listOf("infra"), found.sessions.map { it.name })
  }

  @Test
  fun `no herdr anywhere is not installed`() = runBlocking {
    assertEquals(HerdrOutput.NotInstalled, access(File(folder, "missing/herdr").path).listSessions())
  }

  @Test
  fun `herdr failing says what it printed`() = runBlocking {
    if (windows) return@runBlocking
    val failing = File(folder, "herdr-failing").apply {
      writeText("#!/bin/sh\necho 'error: no such command' >&2\nexit 2\n")
      check(setExecutable(true))
    }
    assertEquals(HerdrOutput.Failed("error: no such command"), access(failing.path).listSessions())
  }

  @Test
  fun `herdr is looked for on the PATH, then where installers put it`() {
    assertEquals(
      listOf(
        "/usr/bin/herdr",
        "/home/me/bin/herdr",
        "/home/me/.local/bin/herdr",
        "/home/me/.cargo/bin/herdr",
        "/opt/homebrew/bin/herdr",
        "/usr/local/bin/herdr",
        "/home/me/.nix-profile/bin/herdr",
        "/run/current-system/sw/bin/herdr",
        "/home/me/.local/share/mise/shims/herdr",
      ),
      herdrCandidates(mapOf("PATH" to "/usr/bin:/home/me/bin/"), "/home/me", windows = false),
    )
    assertEquals(
      listOf("C:\\Tools\\herdr.exe", "C:\\Users\\me\\bin\\herdr.exe"),
      herdrCandidates(mapOf("Path" to "C:\\Tools;\"C:\\Users\\me\\bin\""), "C:\\Users\\me", windows = true),
    )
  }

  @Test
  fun `a conversation can be resumed where Claude Code kept its transcript`() {
    val projects = File(folder, "projects")
    val transcript = claudeTranscript(projects, "0b6f3c2e-4a51", "/home/me/code/store.front")
    assertEquals(File(projects, "-home-me-code-store-front/0b6f3c2e-4a51.jsonl"), transcript)
  }
}
