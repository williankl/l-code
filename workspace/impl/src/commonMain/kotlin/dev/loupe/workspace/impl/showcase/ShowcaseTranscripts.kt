package dev.loupe.workspace.impl.showcase

import dev.loupe.domain.review.ReviewComment
import dev.loupe.workspace.impl.DiffMapper
import dev.loupe.workspace.uimodel.PromptItemModel
import dev.loupe.workspace.uimodel.TerminalLineModel
import dev.loupe.workspace.uimodel.ToolStatModel
import dev.loupe.workspace.uimodel.ToolStatTone

/** What each showcase session's terminal shows, drawn in place of a live terminal. */
internal object ShowcaseTranscripts {
  private const val RepoPath = "~/code/storefront"

  fun tool(verb: String, target: String, stats: String? = null, running: Boolean = false) = TerminalLineModel.ToolCall(verb = verb, target = target, stats = parseStats(stats), running = running)

  private fun parseStats(stats: String?): List<ToolStatModel> = when {
    stats.isNullOrEmpty() -> emptyList()
    "passed" in stats -> listOf(ToolStatModel(stats, ToolStatTone.Positive))
    "files" in stats -> listOf(ToolStatModel(stats, ToolStatTone.Neutral))
    else -> stats.split(' ').map { ToolStatModel(it, if (it.startsWith("+")) ToolStatTone.Positive else ToolStatTone.Critical) }
  }

  val main: List<TerminalLineModel> = listOf(
    TerminalLineModel.WorkingDirectory(RepoPath, "feat/rate-limit"),
    TerminalLineModel.Command("claude"),
    TerminalLineModel.UserMessage(
      "Add per-API-key rate limiting to the public API. Use the existing Redis client and return 429 with Retry-After when a key is over its limit.",
    ),
    TerminalLineModel.AgentMessage("Token bucket per API key, kept in Redis and mounted on /api. The old in-memory throttle goes."),
    tool("read", "src/server/redis.ts"),
    tool("read", "src/middleware/throttle.ts"),
    tool("write", "src/middleware/rateLimit.ts", "+38"),
    tool("edit", "src/server/app.ts", "+5 −2"),
    tool("edit", "src/config.ts", "+3"),
    tool("delete", "src/middleware/throttle.ts", "−20"),
    tool("write", "test/rateLimit.test.ts", "+39"),
    tool("edit", "README.md", "+5"),
    tool("run", "npm test -- rateLimit", "5 passed"),
    TerminalLineModel.AgentMessage(
      "Done. Requests to /api are limited per key (RATE_LIMIT_PER_MIN, default 100). Over-limit requests get a 429 with Retry-After.",
    ),
  )

  private fun items(comments: List<ReviewComment>) = comments.mapIndexed { index, comment ->
    PromptItemModel("${index + 1}.", "${comment.filePath}:${DiffMapper.rangeText(comment.start, comment.end)}", comment.body)
  }

  fun sentBlock(comments: List<ReviewComment>): List<TerminalLineModel> {
    val first = comments.first().filePath
    val noun = if (comments.size == 1) "comment" else "comments"
    return listOf(
      TerminalLineModel.Rule("Sent from diff review · ${comments.size} $noun"),
      TerminalLineModel.UserMessage("Review comments on feat/rate-limit (diff against main). Please address each one:", items(comments)),
      TerminalLineModel.AgentMessage("Working through ${comments.size} review $noun."),
      tool("read", first),
      tool("edit", first, running = true),
    )
  }

  fun subSession(parentName: String, comments: List<ReviewComment>): List<TerminalLineModel> {
    val paths = comments.map { it.filePath }.distinct()
    val noun = if (comments.size == 1) "comment" else "comments"
    return listOf(
      TerminalLineModel.Rule("Sub-session of $parentName · clean context · same folder"),
      TerminalLineModel.WorkingDirectory(RepoPath, "feat/rate-limit"),
      TerminalLineModel.Command("claude"),
      TerminalLineModel.UserMessage(
        "Address these review comments on branch feat/rate-limit. Run git diff main first to see the full change under review.",
        items(comments),
      ),
      TerminalLineModel.AgentMessage("Starting from the diff against main, then the ${comments.size} $noun."),
      tool("run", "git diff main --stat", "6 files"),
    ) + paths.map { tool("read", it) } + tool("edit", paths.first(), running = true)
  }

  fun blank(parentName: String, branch: String = "feat/rate-limit", path: String = RepoPath, rule: String = "clean context · same folder") = listOf(
    TerminalLineModel.Rule("Sub-session of $parentName · $rule"),
    TerminalLineModel.WorkingDirectory(path, branch),
    TerminalLineModel.Command("claude"),
  )

  fun worktree(parentName: String, name: String, branch: String) = listOf(
    TerminalLineModel.Rule("Sub-session of $parentName · new worktree"),
    TerminalLineModel.WorkingDirectory(RepoPath, "feat/rate-limit"),
    TerminalLineModel.Command("git worktree add ../storefront.worktrees/$name -b $branch"),
    TerminalLineModel.Output("Preparing worktree (new branch '$branch')\nHEAD is now at 4e1c9a2 Add per-key rate limiting"),
    TerminalLineModel.WorkingDirectory("$RepoPath.worktrees/$name", branch),
    TerminalLineModel.Command("claude"),
  )
}
