package dev.lemonadecode.platform.terminal

import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.platform.process.ProcessParent
import dev.lemonadecode.platform.process.ProcessParents
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * [ProcessParents] from macOS's kernel, read the way `ProcessHandle` reads them (see [DarwinProcess]);
 * elsewhere it can't tell. Here, beside the reads `ForegroundProcess` and `ProcessDirectory` make,
 * rather than in process-core, so the record is read in one place; public so other modules' tests
 * can check walks over real processes with it.
 */
@ContributesBinding(AppScope::class)
@Inject
public class DarwinProcessParents : ProcessParents {
  override fun of(pid: Long): ProcessParent = DarwinProcess.parent(pid)

  override fun ofThisProcess(): Long? = DarwinProcess.ownParent()
}
