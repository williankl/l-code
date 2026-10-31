# Loupe

A terminal IDE for reviewing work. Every session is a plain terminal: your login shell, starting
in your home folder, exactly like a new terminal window. Run whatever you like in it. Loupe follows
the shell as it `cd`s, shows what changed against `main` in that checkout, lets you comment on
lines or ranges, and pastes the comments into the terminal or into a sub-session opened in the
same folder or in a new git worktree.

Kotlin Multiplatform with Compose Multiplatform (desktop JVM target), Teya's
[Lemonade](https://github.com/teya-engineering/lemonade-design-system) design system, and the
architecture of `teya-business-app`. The design lives in Claude Design, project *Loupe rebuilt on
Lemonade dark theme* (`Loupe.dc.html`).

## Run

```sh
./gradlew :apps:desktop:run            # the app; opens with one terminal at ~
./gradlew :apps:desktop:packageDmg     # a Loupe.app bundle
./gradlew :sample:run                  # the design showcase, static screens
./gradlew :sample:renderScreens        # PNG of every showcase screen → sample/build/screens (-Plang=pt)
./gradlew allTests                     # every module's tests
./gradlew spotlessApply                # format (ktlint, .editorconfig)
```

Shortcuts: ⌘T new terminal, ⌘W close the selected session (and its sub-sessions), ⌘↵ paste the
unsent comments into the terminal, Esc closes popovers. Right-click a session to rename it, copy
its folder path or start a sub-session from it; middle-click closes it.

In the diff, images, SVGs, 3D models (STL, OBJ, PLY, glTF, GLB), Markdown and CSV/TSV files get a
Preview chip that draws the file instead of its text, before and after side by side or one at a
time. In the terminal: drag to select (double click
for a word, triple click for a line, shift-click to extend), ⌘C copies, ⌘A selects all, ⌘V pastes,
the wheel scrolls back. Links underline under the mouse; Ctrl-click (or ⌘-click) opens them. That
covers URLs written out (a bare `www.` address opens over https), even when they wrap, and links
programs mark (OSC 8). Only web and mail
links open; a file link is shown in Finder instead of run.

## Architecture

Same layering as `teya-business-app`: domain models, data sources, features split into
`api`/`impl`/`fake`, Molecule presenters, Metro compile-time DI, fakes over mocks.

| Module | What it holds |
|---|---|
| `build-logic/convention` | `loupe.kmp.base` (desktop target, Metro, `explicitApi()`, return-value checker, Spotless), `.library`, `.compose`, `.uimodel`, `.feature`, `.serialization`. |
| `platform/core` | `LoupeResult` and `safeSuspendRunCatching` (rethrows cancellation). |
| `platform/di-core` | `AppScope`. |
| `platform/architecture-core` / `-test` | `LoupeCoroutineDispatchers`, the app `CoroutineScope`, `AppInitializer`; test dispatchers. |
| `platform/presenter-core` / `-test` | `LoupePresenter<Model>`, `PresenterHost` (Molecule, immediate mode), `presenterAsState`; `presenter.test { }`, `awaitItemWhere`, `yieldUntil`. |
| `platform/localization-core` | `LoupeString`: text a presenter puts in a Model, resolved by the UI. |
| `platform/preferences-core` / `-test` | `PreferenceStore`, a JSON file in the user's app-data folder. |
| `platform/process-core` | `ProcessRunner`, `UserShell`, `HomeDirectory`. |
| `platform/terminal` | `TerminalSession` (pty4j + JediTerm's emulator) and `TerminalView`, a Compose renderer. |
| `platform/syntax-core` | Lightweight code colouring for the diff. |
| `platform/power-core` | Keep-awake (`caffeinate` on macOS, `systemd-inhibit` on Linux). |
| `platform/designsystem` | `LoupeTheme` over Lemonade, plus tooltips, popovers, a slot segmented control. |
| `domainEntities` | Sessions, reviews, diffs, settings. No dependencies. |
| `data/git/{api,impl,fake}` | `GitDataSource` over the `git` command: merge-base diffs with whole-file context, untracked files, worktrees. |
| `sessions/{api,impl,fake}` | `SessionService`: opens login shells in PTYs, follows each shell's folder and branch, infers working / needs input / idle / exited, plans and starts sub-sessions. `SessionTerminals` exposes the terminals to the UI. |
| `review/{api,impl,fake}` | `ReviewService`: diff and comments per checkout, the review text, pasting it into the session or into a review sub-session. |
| `settings/{api,impl,fake}` | `SettingsService` (persisted) and the keep-awake coordinator. |
| `workspace/uimodel` | `WorkspaceModel` + `WorkspaceEvent` and the localized strings (`composeResources`, English and Portuguese). |
| `workspace/ui` | The composables: `WorkspaceContent(model, terminal)` and its parts. Pure rendering. |
| `workspace/impl` | `WorkspacePresenter`, the Model mapper, `WorkspaceScreen` (presenter + live terminals), and the showcase built from fixtures through the same mapper. |
| `apps/desktop` | The Metro `AppGraph`, `main`, packaging. |
| `sample` | The showcase app and screenshot renderer. |

Dependency rules, as in the reference app: `api` depends only on `platform/*` and the domain;
`impl` on its own `api`, other features' `api`, data `api`s and `platform/*`; never `impl` on
`impl`. Bindings are contributed with `@ContributesBinding(AppScope::class)` next to their
implementation; `AppGraph` is the only place they meet.

**Presenters.** `WorkspacePresenter.present()` collects the services' `StateFlow`s, keeps only UI
state (selection, popovers, composer, folded rows), and returns an immutable `WorkspaceModel` with a
remembered `eventSink`. `WorkspaceScreen` wires it with `presenterAsState` and draws
`WorkspaceContent`. Errors become a `model.error` banner; presenters never throw.

**Localization.** All copy is in `workspace/uimodel/src/commonMain/composeResources/values*/strings.xml`.
Presenters put `LoupeString`s in the Model (`Res.string.x.asLoupeString(args)`), composables call
`resolve()` or `stringResource`. The language follows the system locale.

**Tests.** Fakes in `:fake` modules, Turbine for flows, Molecule for presenters. Integration tests
run real `git` and real PTYs; `AppGraphSmokeTest` builds the real graph, runs a shell and draws the
window offscreen (`apps/desktop/build/smoke/app.png`). UI tests run headless with Compose's test
rules: `WorkspaceContentUiTest` clicks, hovers, drags and types through every design screen and
checks the events; `WorkspaceFlowUiTest` drives the real presenter and UI together over fakes
(open a terminal, comment on a line, paste it, settings, sub-sessions, ⌘W); the terminal tests
type into `TerminalView` and check the cursor follows the text and blinks.

## How it works

- **Sessions** run `$SHELL -l` in a pseudo-terminal, starting at `~`. Every second Loupe reads the
  shell's current folder from the OS (`lsof` on macOS, `/proc` on Linux) and asks git for its
  checkout and branch, so the session name, top bar, diff and comments follow your `cd`s.
- **Status** shows only while an agent reports it; a plain terminal shows none. Loupe answers the
  program-status probe (OSC 7501) that Claude Code sends at startup, then Claude reports its own
  state and one per background agent: anything blocked (a permission, a question, an error) shows
  *needs input*, else anything working shows *working* (so a background agent counts while the main
  one waits), else *idle*. Reports go away when the agent quits, or once the shell has the terminal
  back if it crashed.
- **Sending comments** pastes the text with bracketed-paste markers and does not press Enter, so
  nothing runs at a shell prompt; you submit it in whatever program you're running.
- **Worktrees** go next to the repository: `storefront.worktrees/sub-1` on branch
  `<parent-branch>-sub-1`.
- **The diff** is the working tree against the merge base of the default branch (origin's HEAD,
  else `main`, else `master`), untracked files included. It refreshes when you open it, when you
  press refresh, and when the agent in the terminal finishes working.
- **Settings** live in `~/Library/Application Support/Loupe/preferences.json` (macOS).
- **Sessions come back.** Quitting saves every session (name, sub-session tree, the folder its
  shell was in, the last 1,000 lines it showed) to `sessions.json` next to the settings, then ends
  them and whatever runs in them. The next launch opens each one again in a fresh shell in that
  folder, with its output above the new prompt; a folder that's gone falls back to home. A Claude
  Code conversation that was running is resumed with `claude --resume <id>`: Loupe reads which one
  from Claude's own `~/.claude/sessions/<pid>.json`. One you quit yourself isn't. The file is also
  kept up to date while Loupe runs, so a crash keeps the sessions, just not their output.

## Not done yet

- Mouse reporting to full-screen programs (the mouse always selects text).
- Running programs don't survive quitting: shells start fresh and only Claude Code is resumed.
- Status needs the agent to report it. Claude Code does; agents that don't show no status.
- Detekt isn't set up; formatting is enforced with Spotless/ktlint.
- Send-bar buttons are single-line (Lemonade's `Button` has a fixed height), so the design's
  "this session" / "clean context" captions are dropped.
