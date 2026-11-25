package dev.lemonadecode.sessions.impl

import dev.lemonadecode.domain.session.Session
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionOrigin
import dev.lemonadecode.domain.session.SessionPlacement
import dev.lemonadecode.domain.session.SessionStatus
import dev.lemonadecode.domain.session.SubSessionLocation
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
  fun `a worktree on a branch of the user's own and its sub-session are named after its last part`() {
    val root = session("ar", "api-ratelimit")
    val plan = SubSessionPlanner.plan(root, listOf(root), SubSessionLocation.NewWorktree, "sub", branch = "worktree/generating-bugs")
    assertEquals("/code/storefront.worktrees/generating-bugs", plan?.workingDirectory)
    assertEquals("worktree/generating-bugs", plan?.branch)
    assertEquals("feat/rate-limit", plan?.baseBranch)
    assertEquals("generating-bugs", plan?.name)
  }

  @Test
  fun `a branch with no slash names the worktree whole`() {
    val root = session("ar", "api-ratelimit")
    val plan = SubSessionPlanner.plan(root, listOf(root), SubSessionLocation.NewWorktree, "sub", branch = "flaky-login")
    assertEquals("flaky-login", plan?.name)
    assertEquals("/code/storefront.worktrees/flaky-login", plan?.workingDirectory)
  }

  @Test
  fun `a folder already there moves the new worktree and its name along`() {
    val root = session("ar", "api-ratelimit")
    val there = setOf("/code/storefront.worktrees/generating-bugs", "/code/storefront.worktrees/generating-bugs-2")
    val plan = SubSessionPlanner.plan(root, listOf(root), SubSessionLocation.NewWorktree, "sub", branch = "feat/generating-bugs") { it in there }
    assertEquals("generating-bugs-3", plan?.name)
    assertEquals("/code/storefront.worktrees/generating-bugs-3", plan?.workingDirectory)
    assertEquals("feat/generating-bugs", plan?.branch, "the branch stays as typed")
  }

  @Test
  fun `a worktree's name is never one a sibling already shows`() {
    val root = session("ar", "api-ratelimit")
    val named = session("s1", "generating-bugs", parent = "ar", placement = SessionPlacement.Worktree)
    val renamed = session("s2", "sub-1", parent = "ar").copy(customName = "generating-bugs-2")
    val elsewhere = session("cf", "flaky-login")
    val sessions = listOf(root, named, renamed, elsewhere)
    assertEquals("generating-bugs-3", SubSessionPlanner.plan(root, sessions, SubSessionLocation.NewWorktree, "sub", branch = "worktree/generating-bugs")?.name)
    assertEquals("flaky-login", SubSessionPlanner.plan(root, sessions, SubSessionLocation.NewWorktree, "sub", branch = "fix/flaky-login")?.name, "only siblings count")
  }

  @Test
  fun `nothing typed yet names nothing`() {
    val root = session("ar", "api-ratelimit")
    val plan = SubSessionPlanner.plan(root, listOf(root), SubSessionLocation.NewWorktree, "sub", branch = "") { true }
    assertEquals("", plan?.name)
    assertEquals("/code/storefront.worktrees/", plan?.workingDirectory)
  }

  @Test
  fun `a worktree with no branch typed skips folders already there`() {
    val root = session("ar", "api-ratelimit")
    val plan = SubSessionPlanner.plan(root, listOf(root), SubSessionLocation.NewWorktree, "sub") { it == "/code/storefront.worktrees/sub-1" }
    assertEquals("sub-2", plan?.name)
    assertEquals("/code/storefront.worktrees/sub-2", plan?.workingDirectory)
    assertEquals("feat/rate-limit-sub-2", plan?.branch)
  }

  @Test
  fun `the same folder keeps numbering its sub-sessions`() {
    val root = session("ar", "api-ratelimit")
    val sessions = listOf(root, session("s1", "sub-1", parent = "ar"), session("w", "generating-bugs", parent = "ar", placement = SessionPlacement.Worktree))
    val plan = SubSessionPlanner.plan(root, sessions, SubSessionLocation.SameFolder, "sub", branch = "worktree/generating-bugs") { true }
    assertEquals("sub-2", plan?.name)
  }

  @Test
  fun `the same folder keeps the parent's branch whatever was typed`() {
    val root = session("ar", "api-ratelimit")
    val plan = SubSessionPlanner.plan(root, listOf(root), SubSessionLocation.SameFolder, "sub", branch = "worktree/generating-bugs")
    assertEquals("/code/storefront", plan?.workingDirectory)
    assertEquals("feat/rate-limit", plan?.branch)
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

  @Test
  fun `a session goes back before the sibling it was before, else after the one it was after`() {
    // a has sub-sessions b (with c under it) and d; e is a root after a.
    val sessions = listOf(session("a", "a"), session("b", "b", parent = "a"), session("c", "c", parent = "b"), session("d", "d", parent = "a"), session("e", "e"))
    fun back(session: Session, after: String?, before: String?) = SubSessionPlanner.insertBack(sessions, session, after?.let(::SessionId), before?.let(::SessionId)).map { it.name }

    val x = session("x", "x", parent = "a")
    assertEquals(listOf("a", "b", "c", "x", "d", "e"), back(x, after = "b", before = "d"))
    assertEquals(listOf("a", "b", "c", "x", "d", "e"), back(x, after = "b", before = "gone"), "after b and what's under it")
    assertEquals(listOf("a", "x", "b", "c", "d", "e"), back(x, after = "gone", before = "b"))
    assertEquals(listOf("a", "b", "c", "d", "x", "e"), back(x, after = "gone", before = "gone"), "last under its parent")
    assertEquals(listOf("a", "b", "c", "d", "x", "e"), back(x, after = null, before = "e"), "e isn't a sibling of a sub-session")

    val root = session("y", "y")
    assertEquals(listOf("y", "a", "b", "c", "d", "e"), back(root, after = null, before = "a"))
    assertEquals(listOf("a", "b", "c", "d", "y", "e"), back(root, after = "a", before = null))
    assertEquals(listOf("a", "b", "c", "d", "e", "y"), back(root, after = null, before = null), "last of all")
  }
}
