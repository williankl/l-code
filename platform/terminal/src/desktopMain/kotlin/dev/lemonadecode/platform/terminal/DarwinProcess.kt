package dev.lemonadecode.platform.terminal

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.Pointer

/**
 * What `ps` and `lsof` tell about a process on macOS, asked of the kernel directly, with the very
 * calls they make, instead of starting either. `ps -o pgid=` and `-o tpgid=` print fields of the
 * process's `kinfo_proc`, read through `sysctl`; `lsof -a -d cwd -Fn` lists a process once
 * `proc_pidinfo` gives its task info, then names the folder `proc_pidinfo` gives as its current one.
 *
 * A read answers only when the kernel answered in full: the call succeeded and filled the whole
 * record, for that very process. Otherwise its callers start `ps` or `lsof` as they always did, which
 * also covers what isn't matched here: a folder name that isn't printable ASCII, or has a backslash,
 * which `lsof` prints escaped, depending on the locale. The one failure answered without them is a
 * process the kernel says doesn't exist (`ESRCH`), which `lsof` passes over too; see [DirectoryRead].
 * Every buffer is allocated at the size the kernel is told, so it never writes past one.
 *
 * Record sizes and offsets are those of the macOS SDK's `sys/sysctl.h` and `sys/proc_info.h`,
 * checked alike for arm64 and x86_64; on any other system or architecture nothing here is used.
 */
internal object DarwinProcess {
  /** What `ps` prints for a process: the group it's in (`pgid`), and its terminal's foreground group (`tpgid`). */
  data class Groups(val group: Long, val terminalGroup: Long)

  /** What the kernel tells of a process's current folder, read the way `lsof` reads it. */
  sealed interface DirectoryRead {
    /** The folder, as `lsof` would name it. */
    data class Folder(val path: String) : DirectoryRead

    /**
     * No such process, or one that has ended. The kernel says so (`ESRCH`) when it can't find the
     * pid, before anything that depends on who is asking, and `lsof` makes the same call: asked at
     * the same moment, it gets the same answer, passes over the process and names no folder.
     */
    data object NoProcess : DirectoryRead

    /** Anything else, like another user's process or a name `lsof` writes escaped: only `lsof` can say. */
    data object AskLsof : DirectoryRead
  }

  private class Calls(val sysctl: Function, val pidInfo: Function)

  private val calls: Calls? by lazy {
    runCatching {
      val supported = Platform.isMac() && Platform.ARCH in Architectures && Native.POINTER_SIZE == 8 && Native.SIZE_T_SIZE == 8
      if (!supported) return@runCatching null
      val process = NativeLibrary.getProcess()
      Calls(sysctl = process.getFunction("sysctl"), pidInfo = process.getFunction("proc_pidinfo"))
    }.getOrNull()
  }

  /** The groups of process [pid] as `ps` reads them, from its `kinfo_proc`; null when the kernel doesn't hand that back. */
  fun groups(pid: Long): Groups? = (kinfo(pid) as? Kinfo.Record)?.let { Groups(group = it.group, terminalGroup = it.terminalGroup) }

  /**
   * The parent of process [pid] from its `kinfo_proc`, the record `ProcessHandle.of`, `isAlive` and
   * `parent` each read on macOS; [ProcessParent.NoProcess] when the kernel finds no such process.
   */
  fun parent(pid: Long): ProcessParent = when (val read = kinfo(pid)) {
    is Kinfo.Record -> ProcessParent.Parent(read.parent)
    Kinfo.NoProcess -> ProcessParent.NoProcess
    Kinfo.Unknown -> ProcessParent.Unknown
  }

  /** One `sysctl(KERN_PROC_PID)` read of a process's `kinfo_proc`, as `ps` and `ProcessHandle` make it. */
  private sealed interface Kinfo {
    /** The whole record, for that process; `ps` prints these as signed ints. */
    data class Record(val group: Long, val terminalGroup: Long, val parent: Long) : Kinfo

    /** The call succeeded with no record: the kernel knows no such process, zombies included. */
    data object NoProcess : Kinfo

    /** Anything else: a failed call, a part of a record, another process's. */
    data object Unknown : Kinfo
  }

  private fun kinfo(pid: Long): Kinfo {
    val sysctl = calls?.sysctl ?: return Kinfo.Unknown
    if (pid !in 1..Int.MAX_VALUE) return Kinfo.Unknown
    return runCatching {
      Memory(MibBytes).use { mib ->
        Memory(KinfoProcSize).use { record ->
          Memory(SizeTBytes).use { length ->
            mib.setInt(0, CtlKern)
            mib.setInt(4, KernProc)
            mib.setInt(8, KernProcPid)
            mib.setInt(12, pid.toInt())
            length.setLong(0, KinfoProcSize)
            val result = sysctl.invokeInt(arrayOf(mib, MibLength, record, length, Pointer.NULL, 0L))
            val read = length.getLong(0)
            when {
              result != 0 -> Kinfo.Unknown
              read == 0L -> Kinfo.NoProcess
              read == KinfoProcSize && record.getInt(KinfoPid) == pid.toInt() -> Kinfo.Record(
                group = record.getInt(KinfoPgid).toLong(),
                terminalGroup = record.getInt(KinfoTpgid).toLong(),
                parent = record.getInt(KinfoPpid).toLong(),
              )
              else -> Kinfo.Unknown
            }
          }
        }
      }
    }.getOrDefault(Kinfo.Unknown)
  }

  /**
   * The current folder of process [pid], as `lsof -a -d cwd -p <pid> -Fn` names it, when `lsof` would
   * print it as it is; [DirectoryRead.NoProcess] when the kernel says there's no such process; else
   * [DirectoryRead.AskLsof].
   */
  fun currentDirectory(pid: Long): DirectoryRead {
    val pidInfo = calls?.pidInfo ?: return DirectoryRead.AskLsof
    if (pid !in 1..Int.MAX_VALUE) return DirectoryRead.AskLsof
    return runCatching {
      // `lsof` passes over a process whose task info it can't read. The error is read straight after
      // the call, on the same thread, before anything else can set it.
      val listed = Memory(TaskAllInfoSize.toLong()).use { info ->
        val read = pidInfo.invokeInt(arrayOf(pid.toInt(), ProcPidTaskAllInfo, 0L, info, TaskAllInfoSize))
        if (read == TaskAllInfoSize) null else failure(read)
      }
      if (listed != null) return@runCatching listed
      val path = Memory(VnodePathInfoSize.toLong()).use { info ->
        val read = pidInfo.invokeInt(arrayOf(pid.toInt(), ProcPidVnodePathInfo, 0L, info, VnodePathInfoSize))
        // Ended since: `lsof` names no folder for one whose folder it can't read for that reason.
        if (read == VnodePathInfoSize) info.getByteArray(CurrentDirectoryPath, PathSize) else return@runCatching failure(read)
      }
      val end = path.indexOf(0)
      if (end < 1 || path[0] != '/'.code.toByte()) return@runCatching DirectoryRead.AskLsof
      if ((0 until end).any { !printedAsIs(path[it]) }) return@runCatching DirectoryRead.AskLsof
      DirectoryRead.Folder(String(path, 0, end, Charsets.US_ASCII))
    }.getOrDefault(DirectoryRead.AskLsof)
  }

  /** What a `proc_pidinfo` call that returned [read] instead of a whole record means. */
  private fun failure(read: Int): DirectoryRead = if (read <= 0 && Native.getLastError() == Esrch) DirectoryRead.NoProcess else DirectoryRead.AskLsof

  private val Architectures = setOf("aarch64", "x86-64")

  // sysctl(3): {CTL_KERN, KERN_PROC, KERN_PROC_PID, pid}, and the size_t it reads and writes the length through.
  private const val CtlKern = 1
  private const val KernProc = 14
  private const val KernProcPid = 1
  private const val MibLength = 4
  private const val MibBytes = 16L
  private const val SizeTBytes = 8L

  // struct kinfo_proc: kp_proc.p_pid, kp_eproc.e_ppid, e_pgid and e_tpgid, each an int32.
  private const val KinfoProcSize = 648L
  private const val KinfoPid = 40L
  private const val KinfoPpid = 560L
  private const val KinfoPgid = 564L
  private const val KinfoTpgid = 576L

  // proc_pidinfo(2) flavours, their records' sizes, and where pvi_cdir.vip_path, a char[MAXPATHLEN], starts.
  private const val ProcPidTaskAllInfo = 2
  private const val TaskAllInfoSize = 232
  private const val ProcPidVnodePathInfo = 9
  private const val VnodePathInfoSize = 2352
  private const val CurrentDirectoryPath = 152L
  private const val PathSize = 1024

  /** errno for "no such process", in `sys/errno.h`. */
  private const val Esrch = 3

  /** Whether `lsof` prints [byte] as it is, in every locale: printable ASCII, but for the backslash it doubles. */
  private fun printedAsIs(byte: Byte): Boolean = byte in 0x20..0x7e && byte != '\\'.code.toByte()
}
