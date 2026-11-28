package dev.lemonadecode.app

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import dev.lemonadecode.domain.session.ClaudeConversation
import dev.lemonadecode.domain.session.SessionStatus
import dev.lemonadecode.platform.core.getOrNull
import dev.zacsweers.metro.createGraph
import jdk.jfr.consumer.RecordingStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.system.exitProcess

/**
 * What open sessions cost the computer while nothing happens in them, through the real app graph:
 * `./gradlew :apps:desktop:measureSessionCpu -Pscenario=idle -Psessions=10`. Not a test, so never
 * part of `allTests`.
 *
 * Opens that many login shells (`/bin/sh`, with a home folder of its own under `build`) in a git
 * repository, leaves them for a warm-up, then counts for a while the processes the app launches
 * (from JFR's `jdk.ProcessStart` events) and the CPU time it uses, its own and its children's
 * (`getrusage`). Scenarios: `idle`, each shell at its prompt; `foreground`, each running `sleep`;
 * `pipeline`, each running `true | sleep`, whose group outlives its first process; `agent`, each
 * running a stand-in for Claude Code that reports a status, keeps a session file and a transcript the
 * way Claude does, and then sleeps.
 *
 * Arguments: scenario, sessions, warm-up seconds, measured seconds, work folder, results file.
 */
fun main(args: Array<String>) {
  val scenario = args.getOrElse(0) { "idle" }
  val count = args.getOrElse(1) { "1" }.toInt()
  val warmup = args.getOrElse(2) { "15" }.toLong()
  val seconds = args.getOrElse(3) { "60" }.toLong()
  val work = File(args.getOrElse(4) { "build/cpu-measure" })
  val results = args.getOrNull(5)?.let(::File)
  require(scenario in setOf("idle", "foreground", "pipeline", "agent")) { "scenario: idle, foreground, pipeline or agent" }

  val home = File(System.getProperty("user.home"))
  check(home.canonicalPath.startsWith(work.canonicalPath)) { "the home folder must be the measurement's own: $home" }
  home.deleteRecursively()
  val claude = File(home, ".claude/sessions").apply { mkdirs() }.parentFile
  val repo = newRepository(File(work, "repo"))
  val fakeClaude = File(work, "fake-claude.sh").apply { writeText(FakeClaude) }

  val report = runBlocking {
    val graph = createGraph<AppGraph>()
    val service = graph.sessionService
    val ids = (1..count).map { checkNotNull(service.openTerminal(repo.path).getOrNull()) { "the session didn't start" } }
    val terminals = ids.map { checkNotNull(graph.sessionTerminals.terminal(it)) }
    terminals.forEach { terminal -> withTimeout(30_000) { terminal.outputs.first { it > 0 } } }
    delay(1_000)
    terminals.forEach { terminal ->
      when (scenario) {
        "foreground" -> terminal.send("sleep 100000\r")
        // Like `cmd | less` once `cmd` is done: the group in front outlives its first process.
        "pipeline" -> terminal.send("/usr/bin/true | sleep 100000\r")
        "agent" -> {
          val id = UUID.randomUUID().toString()
          val now = System.currentTimeMillis()
          File(claude, ClaudeConversation.transcript(id, repo.path)).apply { parentFile.mkdirs() }
            .writeText("""{"isSidechain":false,"type":"assistant","cwd":"${repo.path}","timestamp":"${Instant.ofEpochMilli(now)}"}""" + "\n")
          terminal.send("sh ${fakeClaude.path} $id ${now - 60_000}\r")
        }
      }
    }
    delay(warmup * 1_000)

    val launches = ConcurrentLinkedQueue<Pair<Instant, String>>()
    val stream = RecordingStream().apply {
      enable("jdk.ProcessStart")
      onEvent("jdk.ProcessStart") { event ->
        launches += event.startTime to event.getString("command").substringBefore(' ').substringAfterLast('/')
      }
      startAsync()
    }
    val load = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage
    val from = Instant.now()
    val ownStart = Rusage.cpuNanos(Rusage.Self)
    val childrenStart = Rusage.cpuNanos(Rusage.Children)
    val started = System.nanoTime()
    delay(seconds * 1_000)
    val ownEnd = Rusage.cpuNanos(Rusage.Self)
    val childrenEnd = Rusage.cpuNanos(Rusage.Children)
    val elapsed = (System.nanoTime() - started) / 1e9
    val until = Instant.now()
    // JFR hands events over about once a second: once the window's last ones are in, only those inside it count.
    delay(2_500)
    stream.close()
    val counted = launches.filter { (at, _) -> !at.isBefore(from) && at.isBefore(until) }.groupingBy { it.second }.eachCount()
    val loadAfter = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage
    // The stand-in agents should all show as working by now, or the scenario isn't the one measured.
    val working = service.sessions.value.count { it.status == SessionStatus.Working }
    ids.forEach(service::close)

    val total = counted.values.sum()
    // With no sessions, the totals: what the app costs on its own.
    val per = count.coerceAtLeast(1)
    val minutes = elapsed / 60
    val own = (ownEnd - ownStart) / 1e9
    val children = (childrenEnd - childrenStart) / 1e9
    fun Double.digits(n: Int) = String.format(Locale.ROOT, "%.${n}f", this)
    buildString {
      append("scenario=$scenario sessions=$count seconds=${elapsed.digits(1)} until=$until")
      append(" launches/session/s=${(total / elapsed / per).digits(3)}")
      append(" cpu-s/session/min=${((own + children) / minutes / per).digits(3)}")
      append(" (own=${(own / minutes / per).digits(3)} children=${(children / minutes / per).digits(3)})")
      append(" launched=${counted.toSortedMap()} working=$working")
      append(" load=${load.digits(1)}->${loadAfter.digits(1)}")
    }
  }
  println(report)
  results?.appendText(report + "\n")
  exitProcess(0)
}

/** CPU time, user plus system, from `getrusage`. */
private object Rusage {
  const val Self = 0
  const val Children = -1
  private val getrusage by lazy { NativeLibrary.getInstance("c").getFunction("getrusage") }

  /** For [who]: this process, or the children it has waited for. `struct rusage` starts with two `struct timeval`s, 16 bytes each. */
  fun cpuNanos(who: Int): Long {
    val usage = Memory(256).apply { clear() }
    check(getrusage.invokeInt(arrayOf(who, usage)) == 0) { "getrusage failed" }
    fun time(offset: Long) = usage.getLong(offset) * 1_000_000_000L + usage.getInt(offset + 8) * 1_000L
    return time(0) + time(16)
  }
}

/** Plays Claude Code: keeps a session file under its pid, reports it's working, and then sleeps as that same process. */
private val FakeClaude = """
  |printf '{"pid":%s,"sessionId":"%s","cwd":"%s","startedAt":%s,"kind":"interactive"}' "$$" "$1" "${'$'}PWD" "$2" > "${'$'}HOME/.claude/sessions/$$.json"
  |printf '\033]7501;state=working:app=claude-code\007'
  |exec sleep 100000
  |
""".trimMargin()

private fun newRepository(repo: File): File {
  repo.deleteRecursively()
  repo.mkdirs()
  fun run(vararg args: String) = check(ProcessBuilder(*args).directory(repo).inheritIO().start().waitFor() == 0) { args.joinToString(" ") }
  run("git", "init", "-q", "-b", "main")
  run("git", "config", "user.email", "measure@lemonade-code.dev")
  run("git", "config", "user.name", "Lemonade Code Measure")
  run("git", "config", "commit.gpgsign", "false")
  File(repo, "app.kt").writeText("fun main() {}\n")
  run("git", "add", ".")
  run("git", "commit", "-q", "-m", "init")
  return repo.canonicalFile
}
