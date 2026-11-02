# Features

Everything Lemonade Code can do, one screen at a time. New here? The [README](../README.md) has the
short tour.

- [Sessions](#sessions)
- [The terminal](#the-terminal)
- [Reviewing changes](#reviewing-changes)
- [Comments](#comments)
- [Sending comments](#sending-comments)
- [Sub-sessions and worktrees](#sub-sessions-and-worktrees)
- [File previews](#file-previews)
- [Settings](#settings)
- [Updates](#updates)
- [Sessions come back](#sessions-come-back)
- [Shortcuts](#shortcuts)

---

## Sessions

A session is a terminal. It runs your own login shell and starts in your home folder, exactly like a
new terminal window. Open as many as you like with **+** or **⌘T**.

<img src="images/terminals.png" width="100%" alt="Four sessions in the sidebar and a terminal where Claude Code finished its work">

As you `cd` around, Lemonade Code follows along. The session's name, the top bar, the diff and your
comments all switch to the folder and branch the shell is in.

**Status at a glance.** When an agent like Claude Code is running, the session shows what it's doing:

| Status | Meaning |
|---|---|
| 🟡 **working** | The agent (or one of its background agents) is busy. |
| 🔵 **needs input** | Something is waiting for you: a permission, a question or an error. |
| ⚪ **idle** | Done, and waiting for your next message. |

A plain shell shows no status. Under each name you'll see what's running and where, for example
`claude · feat/rate-limit`, or the folder when you're outside a git repository.

**Make room.** Drag the sidebar's edge to resize it; the width is remembered. Collapse it to a slim
rail of initials with the button at the top left. On a narrow window the sessions slide in as a
drawer, and a dot on the toggle tells you when one of them needs input.

<table>
<tr>
<td width="50%"><img src="images/narrow-drawer.png" alt="A narrow window with the sessions drawer open"></td>
<td width="50%"><img src="images/narrow-wrap.png" alt="A narrow window with the drawer closed and the toolbars wrapped"></td>
</tr>
<tr>
<td align="center"><sub>The drawer, open</sub></td>
<td align="center"><sub>Closed, with toolbars that wrap to fit</sub></td>
</tr>
</table>

**Right-click a session** to rename it, copy its folder path, or start a sub-session from it.
Middle-click closes it. **⌘W** closes the selected session and its sub-sessions.

<img src="images/session-menu.png" width="100%" alt="The right-click menu of a session, naming it and its folder">

---

## The terminal

It's a real terminal, so everything you already use works: your prompt, your aliases, full-screen
programs and coding agents.

- **Select** by dragging. Double-click selects a word, triple-click a line, and Shift-click extends
  the selection.
- **Copy, paste and select all** with ⌘C, ⌘V and ⌘A on macOS, or Ctrl+Shift+C, Ctrl+Shift+V and
  Ctrl+Shift+A on Windows and Linux.
- **Scroll back** with the mouse wheel. Full-screen programs that handle the mouse themselves, like
  Claude Code's fullscreen mode, get the wheel instead, so it scrolls their own view.
- **Open links** with Ctrl-click (or ⌘-click). That covers web addresses written out, even when they
  wrap onto the next line, and links that programs mark for the terminal. A bare `www.` address
  opens over https. Only web and mail links open; a link to a file shows it in your file manager
  instead of running it.
- **Type in any language.** Dead keys and input methods work like anywhere else, so on an
  international layout `'` then `e` types `é`.

---

## Reviewing changes

Switch a session to the **Diff** tab to see what changed in its folder.

<img src="images/review-diff.png" width="100%" alt="The diff of app.ts with the full file shown and a comment on line 9">

- **Against the right branch.** Changes are compared with `main` by default (or your repository's
  default branch). A sub-session compares with its parent session's branch. Pick any other branch
  from the list next to **vs**.
- **Everything included.** Uncommitted changes and new files that git doesn't track yet show up too.
- **Readable.** Code is coloured by language, with three lines of context around each change.
- **The whole file.** Press **Full file** to read a changed file top to bottom.
- **Always fresh.** The diff reloads when you open it, when you press refresh, and when the agent in
  the terminal finishes working.

---

## Comments

Review the changes the way you'd review a pull request.

<img src="images/comment-line.png" width="100%" alt="One comment posted under line 17 and the comment box open on line 19">

- Click the **+** next to a line to comment on it.
- Drag across lines to comment on a range.
- Comments stay **unsent** until you send them, and the bar at the bottom counts them.
- Press **Preview** to see the exact text that will be sent.

<img src="images/comment-range.png" width="100%" alt="A comment on lines 24 to 26, and lines 28 to 32 selected with the comment box open">

---

## Sending comments

When you're ready, you have two choices.

**Paste into terminal** (⌘↵) pastes all your unsent comments into the program in the terminal, each
with its file and line. It's pasted, not run: nothing happens at a shell prompt, and you press Enter
yourself in whatever program you're using.

<img src="images/send-to-session.png" width="100%" alt="Three review comments pasted into Claude Code, which starts working through them">

**New sub-session** opens a fresh session for the review, so the work starts with a clean context:
just your comments and a pointer to the diff.

<img src="images/send-to-sub-session.png" width="100%" alt="review-1, a sub-session that starts with only the review comments">

Sent comments remember where they went, so you can see which session is handling which note.

---

## Sub-sessions and worktrees

Split work off into sub-sessions. Each one is its own terminal, nested under the session it came
from.

<img src="images/session-tree.png" width="100%" alt="A tree of sessions four levels deep, with the path to the selected one lit in lime">

A sub-session can start in one of two places:

| | Same folder | New worktree |
|---|---|---|
| **Folder** | The parent's folder | A new folder next to the repository, named after the branch, like `storefront.worktrees/generating-bugs` |
| **Branch** | The parent's branch | A new branch you name, starting from the parent's |
| **Good for** | Reviews and quick follow-ups | Parallel work that must never collide |

A new worktree asks for its branch first, filled in with a playful suggestion like
`worktree/generating-bugs`. Press **Enter** to keep it, or type over it: everything is yours to
rename, `worktree/` included. The folder follows the name as you type. A name git won't take, or a
branch that already exists, is caught before anything is created. The same field opens from a
session's right-click menu.

Before anything is created, the panel shows you the exact folder and branch you'll get.

<img src="images/new-worktree.png" width="100%" alt="The New sub-session panel previewing a new worktree's folder and branch">

<img src="images/worktree-created.png" width="100%" alt="sub-1 in the same folder and sub-2 in its own worktree, both under bench-redis">

---

## File previews

Some files are easier to review when you can see them. These open on a **Preview** by default; the
chip in the file's header switches between the preview and the text diff.

| Kind | Files | Shown as |
|---|---|---|
| Images | PNG, JPEG, GIF, WebP, BMP, ICO | Before and after, side by side |
| Vector art | SVG | Before and after, side by side |
| 3D models | STL, OBJ, PLY, glTF, GLB | Before and after, side by side. Drag to turn, ⌘ or Ctrl + scroll to zoom, double-click to reset |
| Documents | Markdown | Formatted, the way GitHub shows it: tables, task lists, quotes, code blocks |
| Data | CSV, TSV | A table |

Switch between **Before**, **After** and **Side by side** for any file that changed on both sides.

<img src="images/preview-3d.png" width="100%" alt="A 3D mug model before and after, side by side">

<img src="images/preview-image.png" width="100%" alt="An app icon before and after, side by side">

<img src="images/preview-markdown.png" width="100%" alt="A Markdown document drawn formatted">

<img src="images/preview-table.png" width="100%" alt="A CSV file drawn as a table">

---

## Settings

The gear at the top right opens settings.

<img src="images/settings.png" width="100%" alt="Settings open with the Dark theme picked and Keep awake on">

- **Appearance:** Dark, Light, or follow the System.
- **Keep awake while sessions work:** while any session is working, your computer won't go to
  sleep and the screen won't dim or lock. Once nothing is working, both behave as usual again. While
  it's holding the computer awake, a tag in the top bar says so.

<img src="images/light-diff.png" width="100%" alt="The diff review in the light theme, with the Keeping awake tag in the top bar">

---

## Updates

Installed from a release file (DMG, MSI, .deb or tar.gz), Lemonade Code checks for a new version at
launch and every few hours. When one is out, an **Update** button appears next to Settings. One click
downloads it, checks it, restarts the app on the new version, and brings your sessions back.

<img src="images/update.png" width="100%" alt="The Update available panel, downloading version 1.3.0">

Installed with Homebrew or the Snap Store? Those update the app for you.

---

## Sessions come back

Quit whenever you like. Every session is saved: its name, where it sits in the tree, the folder its
shell was in and the last 1,000 lines it showed. Next time you open the app, each one comes back in
a fresh shell in that folder, with its old output above the new prompt.

If Claude Code was running in a session, its conversation is resumed too. Programs other than Claude
Code start fresh. Sessions are also saved while the app runs, so even after a crash you keep them
(just not their latest output).

---

## Shortcuts

On Windows and Linux, use **Ctrl** wherever you see **⌘**.

| Keys | Does |
|---|---|
| **⌘T** | New terminal session |
| **⌘W** | Close the selected session and its sub-sessions |
| **⌘↵** | Paste the unsent comments into the terminal |
| **Esc** | Close the open menu, panel or drawer |

In the terminal:

| macOS | Windows, Linux | Does |
|---|---|---|
| ⌘C | Ctrl+Shift+C | Copy the selection |
| ⌘V | Ctrl+Shift+V | Paste |
| ⌘A | Ctrl+Shift+A | Select everything |
| ⌘-click | Ctrl-click | Open a link |

With the mouse:

| Action | Does |
|---|---|
| Right-click a session | Rename, copy folder path, start a sub-session |
| Middle-click a session | Close it |
| Click **+** beside a line | Comment on that line |
| Drag across lines | Comment on a range |
| Drag a 3D model | Turn it around |
| Double-click / triple-click in the terminal | Select a word / a line |
