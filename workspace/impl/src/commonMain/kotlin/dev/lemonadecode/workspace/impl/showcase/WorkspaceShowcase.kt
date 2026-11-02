package dev.lemonadecode.workspace.impl.showcase

import dev.lemonadecode.domain.review.CommentStatus
import dev.lemonadecode.domain.review.DiffState
import dev.lemonadecode.domain.review.Review
import dev.lemonadecode.domain.review.ReviewComment
import dev.lemonadecode.domain.session.Session
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionOrigin
import dev.lemonadecode.domain.session.SessionPlacement
import dev.lemonadecode.domain.session.SessionStatus
import dev.lemonadecode.domain.session.SubSessionLocation
import dev.lemonadecode.domain.session.SubSessionPlan
import dev.lemonadecode.domain.settings.AppSettings
import dev.lemonadecode.domain.settings.ThemePreference
import dev.lemonadecode.domain.update.AppRelease
import dev.lemonadecode.domain.update.AppVersion
import dev.lemonadecode.domain.update.UpdateProgress
import dev.lemonadecode.domain.update.UpdateState
import dev.lemonadecode.workspace.impl.Composer
import dev.lemonadecode.workspace.impl.LineRange
import dev.lemonadecode.workspace.impl.NavigationStyle
import dev.lemonadecode.workspace.impl.WorkspaceInput
import dev.lemonadecode.workspace.impl.WorkspaceModelMapper
import dev.lemonadecode.workspace.impl.WorkspaceViewState
import dev.lemonadecode.workspace.impl.showcase.ShowcaseFixtures.Repo
import dev.lemonadecode.workspace.impl.showcase.ShowcaseFixtures.comment
import dev.lemonadecode.workspace.impl.showcase.ShowcaseFixtures.path
import dev.lemonadecode.workspace.impl.showcase.ShowcaseFixtures.rowOf
import dev.lemonadecode.workspace.impl.showcase.ShowcaseFixtures.session
import dev.lemonadecode.workspace.uimodel.TerminalLineModel
import dev.lemonadecode.workspace.uimodel.TerminalTranscriptModel
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import dev.lemonadecode.workspace.uimodel.WorkspaceModel
import dev.lemonadecode.workspace.uimodel.WorkspaceView

/**
 * Every screen in the Lemonade Code design (claude.ai/design, `Loupe.dc.html`), built by the real
 * Model mapper from fixed domain data. Drives the IDE previews and the sample app.
 *
 * @property code the frame label in the design canvas.
 * @property widthDp the frame width the design shows it at; the height is always 900.
 */
public enum class WorkspaceShowcase(
  public val code: String,
  public val title: String,
  public val description: String,
  public val widthDp: Int = 1440,
) {
  AgentFinishes("1", "Agent finishes, open the diff", "Terminal view of the main session after the agent is done."),
  CommentOnDiff("2", "Comment on the diff vs main", "Two unsent comments and the composer open on rateLimit.ts:19. Sessions collapsed to the rail."),
  ReviewFullFile("2b", "Review the full file", "rateLimit.ts folded, app.ts showing the full file with comments on lines 9 and 20."),
  SentToSession("3a", "Comments sent to this session’s agent", "The comments went to claude in api-ratelimit; the agent is working through them."),
  HandedToSubSession("3b", "Comments handed to a clean sub-session", "review-1 starts with only the comments and a pointer to the diff."),
  NarrowDrawer("n1", "Narrow, sessions open as a drawer", "Below 960dp the sessions column becomes a drawer over a scrim.", widthDp = 860),
  NarrowWrap("n2", "Narrow, drawer closed, toolbars wrap", "The info dot on the sessions toggle flags a session that needs input.", widthDp = 860),
  SessionTree("2a", "Tree with lineage rails", "bench-redis, four levels down. The path to it is lit in brand lime."),
  ChoosingWorktree("3a·tree", "Choosing a worktree", "The new sub-session panel suggests a branch to keep or type over, and previews its folder before anything is created."),
  AfterCreatingBoth("3b·tree", "After creating both kinds", "sub-1 shares the folder; sub-2 runs in its own worktree on its own branch."),
  SettingsOpen("4a", "Settings open, an agent working", "Appearance and keep awake. The top bar tag shows keep awake is holding the machine up."),
  LightKeepAwake("4b", "Light theme, keep awake on", "The same diff review in the light palette."),
  MultiLineComment("5a", "A range comment posted, another range selected", "rateLimit.ts:24–26 has a range comment; 28–32 is selected with the composer open."),
  SessionMenu("6a", "Menu on lua-script", "Right-click a session: the menu names it and its folder, and the row holds a ring."),
  RenamingSession("6b", "Renaming in place", "Rename swaps the menu’s actions for a name field."),
  NamingWorktree("6c", "Naming a new worktree", "New sub-session in new worktree swaps the menu’s actions for a branch field, filled with a suggestion."),
  UpdateAvailable("7a", "An update is out", "Installed from a release file, the app offers the newer version next to Settings; the panel downloads it and restarts."),
  ;

  public fun model(eventSink: (WorkspaceEvent) -> Unit = {}): WorkspaceModel = WorkspaceModelMapper.map(scene.input, eventSink)

  /** The terminal transcript drawn for [sessionId] in this screen. */
  public fun transcript(sessionId: String): TerminalTranscriptModel {
    val scene = scene
    val session = scene.input.sessions.firstOrNull { it.id.value == sessionId }
    val lines = scene.transcripts[sessionId].orEmpty()
    val prompt = when {
      session == null || session.status == SessionStatus.Working -> emptyList()
      lines.none { it is TerminalLineModel.Command && it.text in AgentCommands } -> listOf(TerminalLineModel.InputPrompt("$"))
      else -> listOf(TerminalLineModel.InputPrompt("›"))
    }
    return TerminalTranscriptModel(lines + prompt)
  }

  private val scene: Scene get() = scenes.getOrPut(this) { sceneFor(this) }

  private companion object {
    val scenes = mutableMapOf<WorkspaceShowcase, Scene>()
  }
}

/** Programs whose prompt is `›` in the transcripts; anything else shows a shell's `$`. */
private val AgentCommands = setOf("claude", "codex")

private data class Scene(
  val input: WorkspaceInput,
  val transcripts: Map<String, List<TerminalLineModel>>,
)

private val firstReview: List<ReviewComment> get() = listOf(comment("rl", 17), comment("rl", 19), comment("app", 20))

private fun List<ReviewComment>.withStatus(status: CommentStatus) = map { it.copy(status = status) }

private val baseSessions = listOf(
  session("ar", "api-ratelimit"),
  session("r1", "review-1", SessionStatus.Working, parent = "ar", origin = SessionOrigin.Review(3)),
  session("cf", "checkout-flake", SessionStatus.NeedsInput, branch = "fix/checkout-flake"),
  session("sc", "scratch", branch = "main"),
)

private val baseTranscripts = mapOf(
  "ar" to ShowcaseTranscripts.main,
  "r1" to ShowcaseTranscripts.subSession("api-ratelimit", firstReview),
  "cf" to listOf(TerminalLineModel.WorkingDirectory("~/code/storefront", "fix/checkout-flake"), TerminalLineModel.Command("codex")),
  "sc" to listOf(TerminalLineModel.WorkingDirectory("~/code/storefront", "main")),
)

private fun scene(
  sessions: List<Session> = baseSessions,
  selected: String = "ar",
  comments: List<ReviewComment> = emptyList(),
  view: WorkspaceViewState.() -> WorkspaceViewState = { this },
  settings: AppSettings = AppSettings(),
  plan: SubSessionPlan? = null,
  menuPlan: SubSessionPlan? = null,
  transcripts: Map<String, List<TerminalLineModel>> = baseTranscripts,
): Scene {
  val collapsed = listOf("cfg", "thr", "test", "readme").associate { path(it) to false }
  val state = WorkspaceViewState(
    selectedId = SessionId(selected),
    navigationStyle = NavigationStyle.Flat,
    fileExpansion = collapsed,
  ).view()
  return Scene(
    input = WorkspaceInput(
      sessions = sessions,
      review = Review(DiffState.Ready(ShowcaseFixtures.snapshot), comments, base = "main", bases = listOf("main", ShowcaseFixtures.Branch, "release/2024.11")),
      settings = settings,
      view = state,
      subSessionPlan = plan,
      pendingMessage = null,
      homeDirectory = ShowcaseFixtures.Home,
      menuWorktreePlan = menuPlan,
    ),
    transcripts = transcripts,
  )
}

private fun diffScene(settings: AppSettings = AppSettings(sidebarExpanded = false)) = scene(
  comments = listOf(comment("rl", 17), comment("app", 20)),
  settings = settings,
  view = {
    val row = rowOf("rl", 19)
    copy(view = WorkspaceView.Diff, composer = Composer(LineRange(path("rl"), row, row), ShowcaseSources.racyComment))
  },
)

private fun sentScene(settings: AppSettings = AppSettings()): Scene {
  val sent = firstReview.withStatus(CommentStatus.Sent("claude"))
  return scene(
    sessions = baseSessions.map { if (it.id.value == "ar") it.copy(status = SessionStatus.Working) else it },
    comments = sent,
    settings = settings,
    transcripts = baseTranscripts + ("ar" to ShowcaseTranscripts.main + ShowcaseTranscripts.sentBlock(sent)),
  )
}

private val treeSessions = listOf(
  baseSessions[0],
  baseSessions[1],
  session("d2", "atomic-refill", SessionStatus.Working, parent = "r1"),
  session("d3", "lua-script", parent = "d2"),
  session("d4", "bench-redis", SessionStatus.NeedsInput, parent = "d3"),
  session("d5", "watch-multi", SessionStatus.Working, parent = "d2"),
  session("r2", "review-2", parent = "ar", origin = SessionOrigin.Review(1)),
  baseSessions[2],
  baseSessions[3],
)

private fun treeScene(
  sessions: List<Session> = treeSessions,
  selected: String = "d4",
  menu: SubSessionLocation? = null,
  plan: SubSessionPlan? = null,
  sessionMenu: SessionId? = null,
  renaming: Boolean = false,
  worktreeBranch: String? = null,
  namingWorktree: Boolean = false,
  extraTranscripts: Map<String, List<TerminalLineModel>> = emptyMap(),
) = scene(
  sessions = sessions,
  selected = selected,
  comments = firstReview.withStatus(CommentStatus.InSubSession(SessionId("r1"), "review-1")) +
    comment("app", 9, CommentStatus.InSubSession(SessionId("r2"), "review-2")),
  view = {
    copy(
      navigationStyle = NavigationStyle.Tree,
      subSessionMenu = menu,
      sessionMenu = sessionMenu,
      renaming = sessionMenu?.takeIf { renaming },
      namingWorktree = sessionMenu?.takeIf { namingWorktree },
      worktreeBranch = worktreeBranch,
    )
  },
  plan = plan,
  menuPlan = worktreeBranch?.takeIf { namingWorktree }?.let { worktreePlan(it) },
  transcripts = baseTranscripts + mapOf(
    "d2" to ShowcaseTranscripts.blank("review-1"),
    "d3" to ShowcaseTranscripts.blank("atomic-refill"),
    "d4" to ShowcaseTranscripts.blank("lua-script"),
    "d5" to ShowcaseTranscripts.blank("atomic-refill"),
    "r2" to ShowcaseTranscripts.subSession("api-ratelimit", listOf(comment("app", 9))),
  ) + extraTranscripts,
)

private const val SuggestedBranch = "worktree/generating-bugs"

/** A new worktree on [branch], in a folder named after its last part. */
private fun worktreePlan(branch: String) = SubSessionPlan("sub-1", SubSessionLocation.NewWorktree, "$Repo.worktrees/${branch.substringAfterLast('/')}", branch, true, "feat/rate-limit")

private fun sceneFor(showcase: WorkspaceShowcase): Scene = when (showcase) {
  WorkspaceShowcase.AgentFinishes -> scene()
  WorkspaceShowcase.CommentOnDiff -> diffScene()
  WorkspaceShowcase.ReviewFullFile -> scene(
    comments = listOf(comment("rl", 17), comment("app", 9), comment("app", 20)),
    settings = AppSettings(sidebarExpanded = false),
    view = { copy(view = WorkspaceView.Diff, fileExpansion = fileExpansion + (path("rl") to false), fullFiles = setOf(path("app"))) },
  )
  WorkspaceShowcase.SentToSession -> sentScene()
  WorkspaceShowcase.HandedToSubSession -> scene(
    selected = "r1",
    comments = firstReview.withStatus(CommentStatus.InSubSession(SessionId("r1"), "review-1")),
  )
  WorkspaceShowcase.NarrowDrawer -> scene(view = { copy(drawerOpen = true) })
  WorkspaceShowcase.NarrowWrap -> diffScene()
  WorkspaceShowcase.SessionTree -> treeScene()
  WorkspaceShowcase.ChoosingWorktree -> treeScene(
    menu = SubSessionLocation.NewWorktree,
    plan = worktreePlan(SuggestedBranch),
    worktreeBranch = SuggestedBranch,
  )
  WorkspaceShowcase.AfterCreatingBoth -> {
    val sameFolder = session("s1", "sub-1", parent = "d4")
    val worktree = session(
      "s2",
      "sub-2",
      parent = "d4",
      branch = "feat/rate-limit-sub-2",
      placement = SessionPlacement.Worktree,
      folder = "$Repo.worktrees/sub-2",
    )
    val sessions = treeSessions.toMutableList().apply { addAll(indexOfFirst { it.id.value == "d4" } + 1, listOf(sameFolder, worktree)) }
    treeScene(
      sessions = sessions,
      selected = "s2",
      extraTranscripts = mapOf(
        "s1" to ShowcaseTranscripts.blank("bench-redis", rule = "same folder"),
        "s2" to ShowcaseTranscripts.worktree("bench-redis", "sub-2", "feat/rate-limit-sub-2"),
      ),
    )
  }
  WorkspaceShowcase.SettingsOpen -> sentScene(AppSettings(keepAwake = true)).let { scene ->
    scene.copy(input = scene.input.copy(view = scene.input.view.copy(settingsOpen = true)))
  }
  WorkspaceShowcase.LightKeepAwake -> diffScene(AppSettings(sidebarExpanded = false, keepAwake = true, theme = ThemePreference.Light))
  WorkspaceShowcase.MultiLineComment -> scene(
    comments = listOf(
      comment("rl", 17),
      comment("rl", 24, endLine = 26, body = ShowcaseSources.rangeComment),
      comment("app", 20),
    ),
    settings = AppSettings(sidebarExpanded = false),
    view = { copy(view = WorkspaceView.Diff, composer = Composer(LineRange(path("rl"), rowOf("rl", 28), rowOf("rl", 32)), "")) },
  )
  WorkspaceShowcase.SessionMenu -> treeScene(sessionMenu = SessionId("d3"))
  WorkspaceShowcase.RenamingSession -> treeScene(sessionMenu = SessionId("d3"), renaming = true)
  WorkspaceShowcase.NamingWorktree -> treeScene(sessionMenu = SessionId("d3"), worktreeBranch = SuggestedBranch, namingWorktree = true)
  WorkspaceShowcase.UpdateAvailable -> scene().let { scene ->
    val update = UpdateState.Available(
      current = AppVersion(1, 2, 0),
      release = AppRelease(AppVersion(1, 3, 0), "https://github.com/williankl/loupe/releases/tag/v1.3.0"),
      progress = UpdateProgress.Downloading(0.42f),
    )
    scene.copy(input = scene.input.copy(view = scene.input.view.copy(updateOpen = true), update = update))
  }
}

private object ShowcaseSources {
  val racyComment: String = SampleSources.commentTexts.getValue("rl:19")
  const val rangeComment: String =
    "These three lines run on every request, even when the bucket is already full. Return early at the cap and skip the refill math."
}
