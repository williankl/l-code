package dev.lemonadecode.imports.impl

import dev.lemonadecode.domain.session.SourceScan
import dev.lemonadecode.platform.process.HomeDirectory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HerdrSessionSourceTest {
  private class FakeHerdr(
    var output: HerdrOutput = HerdrOutput.Printed(HerdrFixtures.sessionList),
    val files: MutableMap<String, String> = mutableMapOf("${HerdrFixtures.SessionDir}/session.json" to HerdrFixtures.layout),
  ) : HerdrAccess {
    val read = mutableListOf<String>()

    /** Files that can't be read, as when they belong to someone else. */
    val unreadable = mutableSetOf<String>()

    override suspend fun listSessions(): HerdrOutput = output

    override suspend fun readText(path: String): String? {
      read += path
      if (path in unreadable) error("Permission denied")
      return files[path]
    }
  }

  private val herdr = FakeHerdr()
  private val source = HerdrSessionSource(
    herdr,
    object : HomeDirectory {
      override val path: String = HerdrFixtures.Home
    },
  )

  @Test
  fun `it reads the layout of each session herdr lists`() = runTest {
    val found = assertIs<SourceScan.Found>(source.read())
    assertEquals(HerdrSessionSource.Info, found.source)
    assertEquals(listOf("fix-tax", "storefront", "rate limit", "~"), found.sessions.map { it.name })
    assertEquals(false, found.running)
    assertEquals(listOf("${HerdrFixtures.SessionDir}/session.json"), herdr.read)
  }

  @Test
  fun `named sessions come too, and a running one is noted`() = runTest {
    herdr.output = HerdrOutput.Printed(HerdrFixtures.twoSessions)
    herdr.files["${HerdrFixtures.NamedSessionDir}/session.json"] = HerdrFixtures.workLayout
    val found = assertIs<SourceScan.Found>(source.read())
    assertEquals(listOf("fix-tax", "storefront", "rate limit", "~", "infra"), found.sessions.map { it.name })
    assertTrue(found.running)
  }

  @Test
  fun `a layout it can't read hides only that herdr session's workspaces`() = runTest {
    herdr.output = HerdrOutput.Printed(HerdrFixtures.twoSessions)
    herdr.files["${HerdrFixtures.SessionDir}/session.json"] = HerdrFixtures.newerLayout
    herdr.files["${HerdrFixtures.NamedSessionDir}/session.json"] = HerdrFixtures.workLayout
    val found = assertIs<SourceScan.Found>(source.read())
    assertEquals(listOf("infra"), found.sessions.map { it.name })
    assertEquals(1, found.skipped)

    // The same for one it isn't allowed to read.
    herdr.files["${HerdrFixtures.SessionDir}/session.json"] = HerdrFixtures.layout
    herdr.unreadable += "${HerdrFixtures.NamedSessionDir}/session.json"
    val readable = assertIs<SourceScan.Found>(source.read())
    assertEquals(listOf("fix-tax", "storefront", "rate limit", "~"), readable.sessions.map { it.name })
    assertEquals(1, readable.skipped)

    // With nothing readable at all, it says why.
    herdr.unreadable += "${HerdrFixtures.SessionDir}/session.json"
    assertEquals("Permission denied", assertIs<SourceScan.Failed>(source.read()).message)
  }

  @Test
  fun `a session that never saved a layout has nothing to bring`() = runTest {
    herdr.files.clear()
    assertEquals(emptyList(), assertIs<SourceScan.Found>(source.read()).sessions)
  }

  @Test
  fun `without herdr, or when it fails or answers in a way it never did, it says so`() = runTest {
    herdr.output = HerdrOutput.NotInstalled
    assertIs<SourceScan.NotInstalled>(source.read())

    herdr.output = HerdrOutput.Failed("permission denied")
    assertEquals("permission denied", assertIs<SourceScan.Failed>(source.read()).message)

    herdr.output = HerdrOutput.Printed("default  stopped  ~/.config/herdr")
    assertIs<SourceScan.UnknownFormat>(source.read())

    herdr.output = HerdrOutput.Printed(HerdrFixtures.sessionList)
    herdr.files["${HerdrFixtures.SessionDir}/session.json"] = HerdrFixtures.newerLayout
    assertIs<SourceScan.UnknownFormat>(source.read())
  }
}
