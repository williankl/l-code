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
 * Each read is null unless the kernel answered in full: the call succeeded and filled the whole
 * record, for that very process. Its callers then start `ps` or `lsof` as they always did, which also
 * covers what isn't matched here: a folder name that isn't printable ASCII, or has a backslash,
 * which `lsof` prints escaped, depending on the locale. Every buffer is allocated at the size the
 * kernel is told, so it never writes past one.
 *
 * Record sizes and offsets are those of the macOS SDK's `sys/sysctl.h` and `sys/proc_info.h`,
 * checked alike for arm64 and x86_64; on any other system or architecture nothing here is used.
 */
internal object DarwinProcess {
  /** What `ps` prints for a process: the group it's in (`pgid`), and its terminal's foreground group (`tpgid`). */
  data class Groups(val group: Long, val terminalGroup: Long)

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
  fun groups(pid: Long): Groups? {
    val sysctl = calls?.sysctl ?: return null
    if (pid !in 1..Int.MAX_VALUE) return null
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
            // `ps` prints these as signed ints.
            val whole = result == 0 && length.getLong(0) == KinfoProcSize && record.getInt(KinfoPid) == pid.toInt()
            if (whole) Groups(group = record.getInt(KinfoPgid).toLong(), terminalGroup = record.getInt(KinfoTpgid).toLong()) else null
          }
        }
      }
    }.getOrNull()
  }

  /**
   * The current folder of process [pid], as `lsof -a -d cwd -p <pid> -Fn` names it, when `lsof` would
   * print it as it is; null otherwise, or when the kernel doesn't hand back both records.
   */
  fun currentDirectory(pid: Long): String? {
    val pidInfo = calls?.pidInfo ?: return null
    if (pid !in 1..Int.MAX_VALUE) return null
    return runCatching {
      // `lsof` passes over a process whose task info it can't read.
      val listed = Memory(TaskAllInfoSize.toLong()).use { info -> pidInfo.invokeInt(arrayOf(pid.toInt(), ProcPidTaskAllInfo, 0L, info, TaskAllInfoSize)) == TaskAllInfoSize }
      if (!listed) return@runCatching null
      val path = Memory(VnodePathInfoSize.toLong()).use { info ->
        val read = pidInfo.invokeInt(arrayOf(pid.toInt(), ProcPidVnodePathInfo, 0L, info, VnodePathInfoSize))
        if (read == VnodePathInfoSize) info.getByteArray(CurrentDirectoryPath, PathSize) else null
      } ?: return@runCatching null
      val end = path.indexOf(0)
      if (end < 1 || path[0] != '/'.code.toByte()) return@runCatching null
      if ((0 until end).any { !printedAsIs(path[it]) }) return@runCatching null
      String(path, 0, end, Charsets.US_ASCII)
    }.getOrNull()
  }

  private val Architectures = setOf("aarch64", "x86-64")

  // sysctl(3): {CTL_KERN, KERN_PROC, KERN_PROC_PID, pid}, and the size_t it reads and writes the length through.
  private const val CtlKern = 1
  private const val KernProc = 14
  private const val KernProcPid = 1
  private const val MibLength = 4
  private const val MibBytes = 16L
  private const val SizeTBytes = 8L

  // struct kinfo_proc: kp_proc.p_pid, kp_eproc.e_pgid and kp_eproc.e_tpgid, each an int32.
  private const val KinfoProcSize = 648L
  private const val KinfoPid = 40L
  private const val KinfoPgid = 564L
  private const val KinfoTpgid = 576L

  // proc_pidinfo(2) flavours, their records' sizes, and where pvi_cdir.vip_path, a char[MAXPATHLEN], starts.
  private const val ProcPidTaskAllInfo = 2
  private const val TaskAllInfoSize = 232
  private const val ProcPidVnodePathInfo = 9
  private const val VnodePathInfoSize = 2352
  private const val CurrentDirectoryPath = 152L
  private const val PathSize = 1024

  /** Whether `lsof` prints [byte] as it is, in every locale: printable ASCII, but for the backslash it doubles. */
  private fun printedAsIs(byte: Byte): Boolean = byte in 0x20..0x7e && byte != '\\'.code.toByte()
}
