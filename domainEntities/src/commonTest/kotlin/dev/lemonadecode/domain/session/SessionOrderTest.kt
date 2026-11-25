package dev.lemonadecode.domain.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SessionOrderTest {
  private fun session(id: String, parent: String? = null) = Session(
    id = SessionId(id),
    name = id,
    shell = "zsh",
    workingDirectory = "/code/storefront",
    repositoryRoot = "/code/storefront",
    branch = "main",
    parentId = parent?.let(::SessionId),
    placement = if (parent == null) SessionPlacement.Root else SessionPlacement.SameFolder,
    origin = SessionOrigin.Opened,
    status = SessionStatus.Shell,
  )

  /** api, with review-1 (and its own atomic) and review-2 under it, then checkout and scratch. */
  private val sessions = listOf(
    session("api"),
    session("review-1", parent = "api"),
    session("atomic", parent = "review-1"),
    session("review-2", parent = "api"),
    session("checkout"),
    session("scratch"),
  )

  private fun List<Session>.ids() = map { it.id.value }

  private fun move(id: String, index: Int) = SessionOrder.move(sessions, SessionId(id), index)

  @Test
  fun `siblings share a parent, or are all top level`() {
    assertEquals(listOf("api", "checkout", "scratch"), SessionOrder.siblings(sessions, SessionId("checkout")).ids())
    assertEquals(listOf("review-1", "review-2"), SessionOrder.siblings(sessions, SessionId("review-2")).ids())
    assertEquals(listOf("atomic"), SessionOrder.siblings(sessions, SessionId("atomic")).ids())
    assertEquals(emptyList(), SessionOrder.siblings(sessions, SessionId("gone")))
  }

  @Test
  fun `a session moves past a sibling and everything nested under it`() {
    assertEquals(listOf("checkout", "api", "review-1", "atomic", "review-2", "scratch"), move("checkout", 0).ids())
    assertEquals(listOf("checkout", "api", "review-1", "atomic", "review-2", "scratch"), move("api", 1).ids())
    assertEquals(listOf("checkout", "scratch", "api", "review-1", "atomic", "review-2"), move("api", 2).ids())
  }

  @Test
  fun `its sub-sessions move with it, and it stays under its parent`() {
    val moved = move("review-1", 1)
    assertEquals(listOf("api", "review-2", "review-1", "atomic", "checkout", "scratch"), moved.ids())
    assertEquals(SessionId("api"), moved.first { it.id.value == "review-1" }.parentId)
    assertEquals(SessionId("review-1"), moved.first { it.id.value == "atomic" }.parentId)
    assertEquals(listOf("api", "review-2", "review-1", "atomic", "checkout", "scratch"), move("review-2", 0).ids())
  }

  @Test
  fun `past either end goes to that end`() {
    assertEquals(listOf("api", "review-1", "atomic", "review-2", "scratch", "checkout"), move("checkout", 9).ids())
    assertEquals(listOf("scratch", "api", "review-1", "atomic", "review-2", "checkout"), move("scratch", -3).ids())
  }

  @Test
  fun `the same place, or a session that isn't there, changes nothing`() {
    assertSame(sessions, move("checkout", 1))
    assertSame(sessions, move("atomic", 1))
    assertSame(sessions, move("gone", 0))
  }
}
