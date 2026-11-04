package dev.lemonadecode.workspace.impl.showcase

import dev.lemonadecode.domain.mobile.DeviceLogs
import dev.lemonadecode.domain.mobile.DeviceScreen
import dev.lemonadecode.domain.mobile.MobileDevice
import dev.lemonadecode.domain.mobile.NearbyDevice
import dev.lemonadecode.domain.review.BranchTip
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
import dev.lemonadecode.domain.settings.AppLanguage
import dev.lemonadecode.domain.settings.AppSettings
import dev.lemonadecode.domain.settings.DevicesLayout
import dev.lemonadecode.domain.settings.ThemePreference
import dev.lemonadecode.domain.update.AppInstallation
import dev.lemonadecode.domain.update.AppRelease
import dev.lemonadecode.domain.update.AppVersion
import dev.lemonadecode.domain.update.UpdateChannel
import dev.lemonadecode.domain.update.UpdateChecks
import dev.lemonadecode.domain.update.UpdateState
import dev.lemonadecode.workspace.impl.Composer
import dev.lemonadecode.workspace.impl.DevicesStep
import dev.lemonadecode.workspace.impl.DevicesViewState
import dev.lemonadecode.workspace.impl.LineRange
import dev.lemonadecode.workspace.impl.MobileInput
import dev.lemonadecode.workspace.impl.NavigationStyle
import dev.lemonadecode.workspace.impl.WorkspaceInput
import dev.lemonadecode.workspace.impl.WorkspaceModelMapper
import dev.lemonadecode.workspace.impl.WorkspaceViewState
import dev.lemonadecode.workspace.impl.showcase.ShowcaseFixtures.Repo
import dev.lemonadecode.workspace.impl.showcase.ShowcaseFixtures.comment
import dev.lemonadecode.workspace.impl.showcase.ShowcaseFixtures.path
import dev.lemonadecode.workspace.impl.showcase.ShowcaseFixtures.rowOf
import dev.lemonadecode.workspace.impl.showcase.ShowcaseFixtures.session
import dev.lemonadecode.workspace.uimodel.DeviceTab
import dev.lemonadecode.workspace.uimodel.DevicesWindow
import dev.lemonadecode.workspace.uimodel.SettingsPage
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
 * @property widthDp the frame width the design shows it at.
 * @property heightDp the frame height the design shows it at.
 * @property part the whole window, or only the Devices panel, as its own window shows it.
 */
public enum class WorkspaceShowcase(
  public val code: String,
  public val title: String,
  public val description: String,
  public val widthDp: Int = 1440,
  public val heightDp: Int = 900,
  public val part: ShowcasePart = ShowcasePart.Window,
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
  SettingsOpen("4a", "Settings open, an agent working", "Settings is a short list; every item opens its own page. The top bar tag shows keep awake is holding the machine up."),
  LightKeepAwake("4b", "Light theme, keep awake on", "The same diff review in the light palette."),
  MultiLineComment("5a", "A range comment posted, another range selected", "rateLimit.ts:24–26 has a range comment; 28–32 is selected with the composer open."),
  SessionMenu("6a", "Menu on lua-script", "Right-click a session: the menu names it and its folder, and the row holds a ring."),
  RenamingSession("6b", "Renaming in place", "Rename swaps the menu’s actions for a name field."),
  NamingWorktree("6c", "Naming a new worktree", "New sub-session in new worktree swaps the menu’s actions for a branch field, filled with a suggestion."),
  SettingsList("8a", "Settings list", "Five rows show their current value; the gear's dot says an update is out. Open one to change it."),
  SettingsLanguage("8b", "Language, Deutsch chosen", "Each language in its own name, with its flag. The whole app follows at once."),
  SettingsPlugins("8c", "Plugins › Mobile", "The Mobile plugin mirrors Android and iOS screens, pairs devices and reads their logs."),
  SettingsUpdates("8d", "Updates, 1.9.0 available", "What's new, then Update now. It downloads, then a restart finishes it."),
  DeviceList("8e", "Devices · list", "Connected devices, Android and iOS apart. Pairing starts at the bottom.", widthDp = 420, heightDp = 800, part = ShowcasePart.Devices),
  DeviceSideBySide("8f", "Device · side by side", "The screen and its logs side by side, the default.", widthDp = 760, heightDp = 800, part = ShowcasePart.Devices),
  DeviceLandscape("8f·wide", "Device · landscape", "A screen on its side goes above its logs, as wide as the panel.", widthDp = 760, heightDp = 800, part = ShowcasePart.Devices),
  DeviceStacked("8f·narrow", "Device · together, narrow", "Without room beside the logs, a portrait screen goes above them.", widthDp = 420, heightDp = 800, part = ShowcasePart.Devices),
  DeviceTabs("8g", "Device · tabs, Logs selected", "Switched to tabs from the header. iOS logs over USB; errors can go to the agent.", widthDp = 420, heightDp = 800, part = ShowcasePart.Devices),
  PairPlatform("8h", "Pair · 1 platform", "Pairing asks one thing at a time: first, what are you pairing?", widthDp = 420, heightDp = 800, part = ShowcasePart.Devices),
  PairMethod("8i", "Pair · 2 method", "Then how: a QR code, a pairing code or a device found on this network.", widthDp = 420, heightDp = 800, part = ShowcasePart.Devices),
  PairCode("8j", "Pair · 3 code", "Then what the phone shows: its address and the 6-digit code.", widthDp = 420, heightDp = 800, part = ShowcasePart.Devices),
  Paired("8k", "Paired", "The new device is connected, ready to open.", widthDp = 420, heightDp = 800, part = ShowcasePart.Devices),
  DisplayMain("8l", "Display 1 · main window", "After ↗ the main window gets its space back; the phone button gets a ↗ mark and brings the other window forward."),
  DisplayDevices("8m", "Display 2 · Devices window", "The Devices panel in its own window, on another display.", widthDp = 520, heightDp = 900, part = ShowcasePart.Devices),
  DiffFileList("8n", "Changed files beside the diff", "The file list follows the scroll. Picking a file jumps to it and opens it if it was folded."),
  DiffBasePicker("8o", "Picking the base branch", "The base branch is a picker: find a branch by name, or HEAD for only the uncommitted changes."),
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

/** Whole window, or only the Devices panel. */
public enum class ShowcasePart { Window, Devices }

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
  mobile: MobileInput = MobileInput(),
  update: UpdateState = UpdateState.None,
  review: Review? = null,
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
      review = review ?: Review(
        DiffState.Ready(ShowcaseFixtures.snapshot),
        comments,
        base = "main",
        bases = listOf("main", ShowcaseFixtures.Branch, "release/2024.11"),
        defaultBranch = "main",
        branchTips = mapOf(
          "main" to BranchTip(Now - 2 * Hour, "Ana Ribeiro"),
          ShowcaseFixtures.Branch to BranchTip(Now - 20 * Minute, "You"),
          "release/2024.11" to BranchTip(Now - 4 * Day, "Release bot"),
        ),
      ),
      settings = settings,
      view = state,
      subSessionPlan = plan,
      pendingMessage = null,
      homeDirectory = ShowcaseFixtures.Home,
      menuWorktreePlan = menuPlan,
      update = update,
      installation = AppInstallation(AppVersion(1, 8, 2), UpdateChannel.SelfUpdate),
      updateChecks = UpdateChecks(lastCheckedMillis = Now - 2 * Hour),
      mobile = mobile.copy(enabled = settings.mobilePlugin),
      nowMillis = Now,
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
  WorkspaceShowcase.SettingsList -> settingsScene(SettingsPage.List)
  WorkspaceShowcase.SettingsLanguage -> settingsScene(SettingsPage.Language, language = AppLanguage.German, update = UpdateState.None)
  WorkspaceShowcase.SettingsPlugins -> settingsScene(SettingsPage.Plugins, update = UpdateState.None)
  WorkspaceShowcase.SettingsUpdates -> settingsScene(SettingsPage.Updates)
  WorkspaceShowcase.DeviceList -> devicesScene(DevicesViewState())
  WorkspaceShowcase.DeviceSideBySide -> devicesScene(DevicesViewState(step = DevicesStep.Device, deviceId = ShowcaseDevices.pixel.id))
  WorkspaceShowcase.DeviceLandscape -> devicesScene(DevicesViewState(step = DevicesStep.Device, deviceId = ShowcaseDevices.pixel.id), screen = ShowcaseDevices.screen(2400, 1080))
  WorkspaceShowcase.DeviceStacked -> devicesScene(DevicesViewState(step = DevicesStep.Device, deviceId = ShowcaseDevices.pixel.id))
  WorkspaceShowcase.DeviceTabs -> devicesScene(
    DevicesViewState(step = DevicesStep.Device, deviceId = ShowcaseDevices.iphone.id, tab = DeviceTab.Logs),
    layout = DevicesLayout.Tabs,
    logs = ShowcaseDevices.iosLogs,
    screen = ShowcaseDevices.screen(1179, 2556),
  )
  WorkspaceShowcase.PairPlatform -> devicesScene(DevicesViewState(step = DevicesStep.PairPlatform))
  WorkspaceShowcase.PairMethod -> devicesScene(DevicesViewState(step = DevicesStep.PairMethod), nearby = ShowcaseDevices.nearby)
  WorkspaceShowcase.PairCode -> devicesScene(DevicesViewState(step = DevicesStep.PairCode, pairAddress = "192.168.1.57:37099", pairCode = "482913"))
  WorkspaceShowcase.Paired -> devicesScene(
    DevicesViewState(step = DevicesStep.Paired, paired = ShowcaseDevices.pixel7a.id),
    devices = ShowcaseDevices.connected + ShowcaseDevices.pixel7a,
  )
  WorkspaceShowcase.DisplayMain -> scene(
    settings = AppSettings(mobilePlugin = true),
    view = { copy(devices = DevicesViewState(step = DevicesStep.Device, deviceId = ShowcaseDevices.pixel.id), devicesWindow = DevicesWindow.Separate) },
    mobile = MobileInput(tools = ShowcaseDevices.tools, devices = ShowcaseDevices.connected),
  )
  WorkspaceShowcase.DisplayDevices -> devicesScene(
    DevicesViewState(step = DevicesStep.Device, deviceId = ShowcaseDevices.pixel.id),
    window = DevicesWindow.Separate,
  )
  WorkspaceShowcase.DiffFileList -> diffScene()
  WorkspaceShowcase.DiffBasePicker -> scene(
    comments = listOf(comment("rl", 17), comment("app", 20)),
    settings = AppSettings(sidebarExpanded = false),
    view = { copy(view = WorkspaceView.Diff, basePickerOpen = true) },
    review = Review(
      DiffState.Ready(ShowcaseFixtures.snapshot),
      listOf(comment("rl", 17), comment("app", 20)),
      base = "develop",
      bases = listOf("main", "develop", "release/2.4", "feat/auth-tokens"),
      defaultBranch = "main",
      branchTips = mapOf(
        "main" to BranchTip(Now - 2 * Hour, "Ana Ribeiro"),
        "develop" to BranchTip(Now - 26 * Hour, "Ana Ribeiro"),
        "release/2.4" to BranchTip(Now - 4 * Day, "Release bot"),
        "feat/auth-tokens" to BranchTip(Now - 1 * Hour, "Maria"),
      ),
    ),
  )
}

/** The newer version the Updates screens offer. */
private val NewRelease = UpdateState.Available(
  current = AppVersion(1, 8, 2),
  release = AppRelease(AppVersion(1, 9, 0), "https://github.com/williankl/loupe/releases/tag/v1.9.0", "Mobile plugin, language settings and faster diffs on large files."),
)

/** Settings open on [page] over the first screen, with the Mobile plugin on. */
private fun settingsScene(
  page: SettingsPage,
  language: AppLanguage = AppLanguage.System,
  update: UpdateState = NewRelease,
) = scene(
  settings = AppSettings(mobilePlugin = true, language = language),
  view = { copy(settingsOpen = true, settingsPage = page) },
  mobile = MobileInput(tools = ShowcaseDevices.tools, devices = ShowcaseDevices.connected),
  update = update,
)

/** The Devices panel at [panel]'s step, with the design's four devices connected. */
private fun devicesScene(
  panel: DevicesViewState,
  layout: DevicesLayout = DevicesLayout.SideBySide,
  window: DevicesWindow = DevicesWindow.Docked,
  devices: List<MobileDevice> = ShowcaseDevices.connected,
  logs: DeviceLogs = ShowcaseDevices.androidLogs,
  screen: DeviceScreen = ShowcaseDevices.screen(),
  nearby: List<NearbyDevice> = emptyList(),
) = scene(
  settings = AppSettings(mobilePlugin = true, devicesLayout = layout),
  view = { copy(devices = panel, devicesWindow = window) },
  mobile = MobileInput(
    tools = ShowcaseDevices.tools,
    devices = devices,
    logs = logs.takeIf { panel.step == DevicesStep.Device },
    screen = screen.takeIf { panel.step == DevicesStep.Device },
    nearby = nearby,
  ),
)

/** The moment the showcase is set at, so "2 h ago" reads the same in every render. */
private const val Now: Long = 1_793_600_000_000
private const val Minute: Long = 60_000
private const val Hour: Long = 60 * Minute
private const val Day: Long = 24 * Hour

private object ShowcaseSources {
  val racyComment: String = SampleSources.commentTexts.getValue("rl:19")
  const val rangeComment: String =
    "These three lines run on every request, even when the bucket is already full. Return early at the cap and skip the refill math."
}
