package dev.lemonadecode.imports.impl

import dev.lemonadecode.domain.session.ImportableSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HerdrFormatTest {
  private fun read(content: String, session: String = "default"): List<ImportableSession> = assertIs<HerdrFormat.Layout.Read>(HerdrFormat.sessions(content, session, HerdrFixtures.Home)).sessions

  @Test
  fun `the session list gives each session's folder and whether it runs`() {
    assertEquals(
      listOf(
        HerdrSession(name = "default", default = true, running = true, sessionDir = HerdrFixtures.SessionDir),
        HerdrSession(name = "work", default = false, running = false, sessionDir = HerdrFixtures.NamedSessionDir),
      ),
      HerdrFormat.sessionList(HerdrFixtures.twoSessions),
    )
    assertNull(HerdrFormat.sessionList("usage: herdr session list [--json]"))
  }

  @Test
  fun `workspaces become sessions, each linked worktree right under its repository's`() {
    val sessions = read(HerdrFixtures.layout)
    assertEquals(listOf("fix-tax", "storefront", "rate limit", "~"), sessions.map { it.name })
    val (tax, storefront, rateLimit, home) = sessions

    assertEquals(
      ImportableSession(
        key = "herdr:default:w1:/home/me/code/storefront",
        name = "storefront",
        customName = null,
        folder = "/home/me/code/storefront",
        conversation = HerdrFixtures.StorefrontChat,
      ),
      storefront,
    )
    assertEquals(storefront.key, rateLimit.parentKey)
    assertTrue(rateLimit.worktree)
    assertEquals("rate limit", rateLimit.customName)
    assertEquals("/home/me/code/storefront.worktrees/rate-limit", rateLimit.folder)

    // Its repository has no workspace in herdr, so it stays at the top.
    assertNull(tax.parentKey)
    assertEquals(false, tax.worktree)
    assertNull(tax.conversation)

    assertEquals("/home/me", home.folder)
    assertNull(home.customName)
  }

  @Test
  fun `the conversation comes from the focused pane of the active tab first`() {
    val rateLimit = read(HerdrFixtures.layout).single { it.customName == "rate limit" }
    assertEquals(HerdrFixtures.RateLimitChat, rateLimit.conversation)

    // The same layout with rate limit's second tab active.
    val otherTabActive = HerdrFixtures.layout.replace(Regex("(\"custom_name\": \"rate limit\"[\\s\\S]*?\"active_tab\": )0"), "$11")
    assertTrue(otherTabActive != HerdrFixtures.layout)
    assertEquals(HerdrFixtures.RateLimitOtherTabChat, read(otherTabActive).single { it.customName == "rate limit" }.conversation)
  }

  @Test
  fun `only conversations herdr's Claude Code integration reported, by a plain id, are picked up`() {
    // Codex runs in the home workspace; Lemonade Code resumes Claude Code only.
    assertNull(read(HerdrFixtures.layout).single { it.name == "~" }.conversation)
    assertEquals(listOf(null, null, null), read(HerdrFixtures.untrustedAgents).map { it.conversation })
  }

  @Test
  fun `keys are stable and tell sessions of different herdr sessions apart`() {
    assertEquals(read(HerdrFixtures.layout).map { it.key }, read(HerdrFixtures.layout).map { it.key })
    assertEquals("herdr:work:w1:/home/me/code/infra", read(HerdrFixtures.workLayout, session = "work").single().key)
  }

  @Test
  fun `a workspace from before tabs is read the way herdr reads it`() {
    val old = read(HerdrFixtures.legacyLayout).single()
    assertEquals("old", old.name)
    assertEquals("/home/me/code/old", old.folder)
  }

  @Test
  fun `a newer or unreadable session file is not guessed at`() {
    val newer = assertIs<HerdrFormat.Layout.Unknown>(HerdrFormat.sessions(HerdrFixtures.newerLayout, "default", HerdrFixtures.Home))
    assertTrue("version 4" in newer.detail, newer.detail)
    assertIs<HerdrFormat.Layout.Unknown>(HerdrFormat.sessions("not json", "default", HerdrFixtures.Home))
    assertIs<HerdrFormat.Layout.Unknown>(HerdrFormat.sessions("""{"version": 3, "workspaces": [{"tabs": []}]}""", "default", HerdrFixtures.Home))
  }
}
