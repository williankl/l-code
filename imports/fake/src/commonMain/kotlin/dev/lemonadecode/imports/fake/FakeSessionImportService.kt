package dev.lemonadecode.imports.fake

import dev.lemonadecode.domain.session.ImportResult
import dev.lemonadecode.domain.session.ImportSource
import dev.lemonadecode.domain.session.ImportableSession
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SourceScan
import dev.lemonadecode.imports.api.SessionImportService

/** In-memory [SessionImportService]: answers what it's given and records what it was asked to import. */
public class FakeSessionImportService(
  override val sources: List<ImportSource> = listOf(Herdr),
) : SessionImportService {
  /** What [scan] answers. */
  public var scans: List<SourceScan> = sources.map { SourceScan.Found(it, emptyList()) }
  public var scanCount: Int = 0

  /** Every call to [import], with the sessions it was given. */
  public val imported: MutableList<List<ImportableSession>> = mutableListOf()

  /** What [import] answers; null opens one session per importable one, named `imported-1`, `imported-2`… */
  public var importResult: ImportResult? = null
  private var nextId = 1

  override suspend fun scan(): List<SourceScan> {
    scanCount++
    return scans
  }

  override suspend fun import(sessions: List<ImportableSession>): ImportResult {
    imported += sessions
    return importResult ?: ImportResult(sessions.filter { it.blocked == null }.map { SessionId("imported-${nextId++}") })
  }

  public companion object {
    public val Herdr: ImportSource = ImportSource(id = "herdr", name = "herdr")
  }
}
