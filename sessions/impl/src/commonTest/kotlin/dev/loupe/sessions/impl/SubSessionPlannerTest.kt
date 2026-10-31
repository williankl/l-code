package dev.loupe.sessions.impl

import dev.loupe.domain.session.Session
import dev.loupe.domain.session.SessionId
import dev.loupe.domain.session.SessionOrigin
import dev.loupe.domain.session.SessionPlacement
import dev.loupe.domain.session.SessionStatus
import dev.loupe.domain.session.SubSessionLocation
import kotlin.test.Test
import kotlin.test.assertEquals

class SubSessionPlannerTest {
  private fun session(id: String, name: String, parent: String? = null, placement: SessionPlacement = SessionPlacement.Root, dir: String = "/code/storefront") = Session(
    id = SessionId(id),
    name = name,
    shell = "zsh",
    workingDirectory = dir,
    repositoryRoot = dir,
    branch = "feat/rate-limit",
    parentId = parent?.let(::SessionId),
    placement = placement,
    origin = SessionOrigin.Opened,
    status = SessionStatus.Idle,
  )

  @Test
  fun `a worktree goes next to the repository on its own branch`() {
    val root = session("ar", "api-ratelimit")
    val plan = SubSessionPlanner.plan(root, listOf(root), SubSessionLocation.NewWorktree, "sub")
    assertEquals("/code/storefront.worktrees/sub-1", plan?.workingDirectory)
    assertEquals("feat/rate-limit-sub-1", plan?.branch)
  }

  @Test
  fun `a worktree of a worktree stays in the same worktrees folder`() {
    val parent = session("w", "sub-1", placement = SessionPlacement.Worktree, dir = "/code/storefront.worktrees/sub-1")
    val plan = SubSessionPlanner.plan(parent, listOf(parent), SubSessionLocation.NewWorktree, "sub")
    assertEquals("/code/storefront.worktrees/sub-2", plan?.workingDirectory)
  }

  @Test
  fun `names skip ones already taken`() {
    val sessions = listOf(session("a", "review-1"), session("b", "review-2"))
    assertEquals("review-3", SubSessionPlanner.nextName(sessions, "review"))
  }

  @Test
  fun `sub-sessions are inserted after the parent's existing descendants`() {
    val sessions = listOf(session("a", "a"), session("b", "b", parent = "a"), session("c", "c", parent = "b"), session("d", "d"))
    val inserted = SubSessionPlanner.insertUnder(sessions, SessionId("a"), session("e", "e", parent = "a"))
    assertEquals(listOf("a", "b", "c", "e", "d"), inserted.map { it.name })
    assertEquals(setOf("a", "b", "c", "e"), SubSessionPlanner.subtree(inserted, SessionId("a")).map { it.value }.toSet())
  }
}
