<div align="center">

<img src="apps/desktop/icons/lemonade-code.svg" width="112" alt="Lemonade Code icon: a prompt chevron next to half a lemon slice">

# Lemonade Code

**Your terminal, with a code review built in.**

Run Claude Code (or anything else) in real terminals.<br>
When the work is done, see what changed, comment on any line, and send your notes straight back.

[![Free](https://img.shields.io/badge/price-free-E1E51A?style=flat-square&labelColor=28310B)](#-get-it)
[![macOS · Windows · Linux](https://img.shields.io/badge/runs_on-macOS_·_Windows_·_Linux-E1E51A?style=flat-square&labelColor=28310B)](docs/INSTALL.md)
[![Built with Lemonade](https://img.shields.io/badge/built_with-🍋_Lemonade-E1E51A?style=flat-square&labelColor=28310B)](#-built-with-lemonade)
[![Compose Multiplatform](https://img.shields.io/badge/Compose_Multiplatform-Kotlin-E1E51A?style=flat-square&labelColor=28310B&logo=kotlin&logoColor=E1E51A)](docs/ARCHITECTURE.md)

[![Download for macOS](https://img.shields.io/badge/Download-macOS-E1E51A?style=for-the-badge&labelColor=28310B)](https://github.com/williankl/loupe/releases/latest)
[![Download for Windows](https://img.shields.io/badge/Download-Windows-E1E51A?style=for-the-badge&labelColor=28310B)](https://github.com/williankl/loupe/releases/latest)
[![Download for Linux](https://img.shields.io/badge/Download-Linux-E1E51A?style=for-the-badge&labelColor=28310B)](https://github.com/williankl/loupe/releases/latest)

<br>

<img src="docs/images/hero.png" width="100%" alt="Lemonade Code: a terminal where Claude Code just finished, and in front of it the review of its changes with two comments">

</div>

<br>

## Why Lemonade Code?

AI agents write a lot of code. Reading it is still your job. Lemonade Code puts the terminal and the
review in the same window, so checking an agent's work feels like reviewing a pull request, not
digging through `git diff`.

<table>
<tr>
<td width="33%" valign="top">

### 🖥️ Real terminals
Each session is your own shell, with your aliases, your tools and your agents. Nothing to learn,
nothing to configure.

</td>
<td width="33%" valign="top">

### 🔍 A real code review
See every change against `main`, comment on lines or ranges, and send all your notes in one go.

</td>
<td width="33%" valign="top">

### 🌱 Room to branch out
Hand work to a fresh sub-session, in the same folder or its own git worktree, and keep the tree
tidy.

</td>
</tr>
</table>

<br>

## ✨ Take the tour

### All your terminals, one window

Start Claude Code, Codex, or any program you like. The sidebar shows what every session is up to,
**working**, **needs input** or **idle**, so you always know which one wants you.

<img src="docs/images/terminals.png" width="100%" alt="The sessions sidebar with four sessions and their status, next to a terminal where Claude Code finished adding rate limiting">

### See exactly what changed

Switch to the **Diff** tab to see every change in that folder against `main`, or any branch you
pick. New and uncommitted files are included, code is coloured, and the full file is one click away.

<img src="docs/images/review-diff.png" width="100%" alt="The diff of src/server/app.ts showing the full file, with a comment under line 9 and the changed files listed beside it">

### Comment like it's a pull request

Click the **+** next to a line, or drag across several lines, and write what should change. Your
comments wait until you're ready to send them.

<img src="docs/images/comment-range.png" width="100%" alt="A comment on lines 24 to 26 and the comment box open on lines 28 to 32">

### Send your notes back in one click

Press **Paste into terminal** (⌘↵) and every comment lands in the agent's prompt, with its file and
line. Nothing runs until you press Enter. Want a clean slate? Hand them to a new sub-session instead.

<table>
<tr>
<td width="50%"><img src="docs/images/send-to-session.png" alt="Three review comments pasted into Claude Code in the same session"></td>
<td width="50%"><img src="docs/images/send-to-sub-session.png" alt="The same comments handed to review-1, a new sub-session with a clean context"></td>
</tr>
<tr>
<td align="center"><sub><b>To the same agent</b>, which picks up right where it was</sub></td>
<td align="center"><sub><b>To a clean sub-session</b>, with only your notes as context</sub></td>
</tr>
</table>

### Branch out without the mess

Start a sub-session in the same folder, or in a new **git worktree** on its own branch, so parallel
work never collides. Sessions nest as deep as you need, and a lime rail traces each one back to
where it started.

<img src="docs/images/new-worktree.png" width="100%" alt="The New sub-session panel with New worktree picked, showing the folder and branch it will create">

### Previews for more than code

Images, SVGs, videos, sounds, 3D models, Markdown and CSV files show up as what they are, before and
after, side by side. GIFs, videos and sounds play, a sound shows its waveform, and you can drag a 3D
model to turn it around.

<table>
<tr>
<td width="50%"><img src="docs/images/grid-preview-3d.png" alt="A 3D mug model, before and after a redesign, side by side"></td>
<td width="50%"><img src="docs/images/grid-preview-image.png" alt="An app icon, before and after, side by side"></td>
</tr>
<tr>
<td align="center"><sub><b>3D models</b>: STL, OBJ, PLY, glTF, GLB</sub></td>
<td align="center"><sub><b>Images and videos</b>: PNG, JPEG, GIF, WebP, SVG, MP4, MOV, WebM and more</sub></td>
</tr>
<tr>
<td width="50%"><img src="docs/images/grid-preview-markdown.png" alt="A Markdown file drawn formatted, with a table and a checklist"></td>
<td width="50%"><img src="docs/images/grid-preview-table.png" alt="A CSV file drawn as a table"></td>
</tr>
<tr>
<td align="center"><sub><b>Markdown</b>, drawn the way GitHub shows it</sub></td>
<td align="center"><sub><b>CSV and TSV</b>, as a proper table</sub></td>
</tr>
</table>

### Dark or light

Pick one, or follow your system. Both themes come straight from the Lemonade design system.

<img src="docs/images/theme-split.png" width="100%" alt="The same review screen, half in the dark theme and half in the light theme">

### The little things

<table>
<tr>
<td width="33%" valign="top"><img src="docs/images/touch-keep-awake.png" alt="The settings list with Keep awake on, and the Keeping awake tag in the top bar"><br><b>Keep awake</b><br><sub>Your computer won't sleep or lock while an agent is working.</sub></td>
<td width="33%" valign="top"><img src="docs/images/touch-session-menu.png" alt="The right-click menu of a session"><br><b>Right-click menu</b><br><sub>Rename a session, copy its folder, branch off from it, or close it.</sub></td>
<td width="33%" valign="top"><img src="docs/images/touch-update.png" alt="Settings › Updates offering a new version"><br><b>Updates itself</b><br><sub>Update from Settings, and your sessions come right back.</sub></td>
</tr>
<tr>
<td width="33%" valign="top"><img src="docs/images/touch-narrow.png" alt="A narrow window with the sessions shown as a drawer"><br><b>Fits small windows</b><br><sub>On a narrow window the sidebar slides in as a drawer.</sub></td>
<td width="33%" valign="top"><img src="docs/images/touch-rename.png" alt="Renaming a session in place"><br><b>Rename in place</b><br><sub>No dialogs. Type the new name right where the old one was.</sub></td>
<td width="33%" valign="top"><img src="docs/images/touch-worktree.png" alt="The worktree folder and branch shown before creating them"><br><b>No surprises</b><br><sub>See the folder and branch a worktree will get before it exists.</sub></td>
</tr>
</table>

And a few more:

- 🔁 **Sessions come back.** Quit and reopen: every session returns in the folder it was in, with its
  last output on screen. Claude Code conversations pick up where they left off.
- 🔗 **Clickable links.** Ctrl-click (or ⌘-click) any web address in the terminal to open it.
- ⌨️ **Every keyboard works.** Accents, dead keys and input methods type just like anywhere else.
- 📱 **Phones beside the code.** Turn on the Mobile plugin to see an Android or iOS screen next to
  its logs, use the phone from the app, and hand its errors or a screenshot to the agent.
- 🌍 **Speaks your language.** English, German, Spanish, French, Portuguese and Slovak. It follows
  your system, or pick one in Settings.

See everything in the **[feature guide](docs/FEATURES.md)**.

<br>

## 📦 Get it

Lemonade Code is free. Pick your platform:

| Platform | Install |
|---|---|
| **macOS** (Apple Silicon or Intel) | `brew install --cask williankl/tap/lemonade-code`, or the DMG from [Releases](https://github.com/williankl/loupe/releases/latest) |
| **Windows** (x64) | The MSI from [Releases](https://github.com/williankl/loupe/releases/latest). No admin rights needed. |
| **Ubuntu** | `sudo snap install lemonade-code --classic`, or from the App Center |
| **Ubuntu, Debian** | The `.deb` from [Releases](https://github.com/williankl/loupe/releases/latest): `sudo apt install ./lemonade-code_*_amd64.deb` |
| **Any Linux** (x64) | The `.tar.gz` from [Releases](https://github.com/williankl/loupe/releases/latest). Unpack it anywhere and run `lemonade-code/bin/Lemonade Code`. |

You'll also want `git` installed, so the Diff tab has something to show. Step-by-step help, updates
and uninstalling are in the **[install guide](docs/INSTALL.md)**.

<br>

## 🍋 Built with Lemonade

<table>
<tr>
<td>

Lemonade Code looks and feels the way it does thanks to
**[Lemonade](https://github.com/teya-engineering/lemonade-design-system)**, the open-source design
system from **[Teya](https://github.com/teya-engineering)**.

Every colour, the Figtree typeface, every spacing step, corner radius and shadow comes from
Lemonade's design tokens. Buttons, tags, switches, segmented controls, text fields, toasts, spinners,
badges and icons are Lemonade components, and the light and dark themes are Lemonade's own. The app
even takes its name from it.

Lemonade ships one design language to **Compose Multiplatform**, **SwiftUI** and the **web**, under
the Apache 2.0 license. If you build apps on any of those, it's well worth a look, and a ⭐.

**→ [teya-engineering/lemonade-design-system](https://github.com/teya-engineering/lemonade-design-system)**

</td>
</tr>
</table>

How the app uses Lemonade, and the few pieces it adds on top, is in the **[design notes](docs/DESIGN.md)**.

<br>

## 📚 Learn more

| | |
|---|---|
| 🧭 **[Features](docs/FEATURES.md)** | Every feature, with screenshots, plus keyboard and mouse shortcuts |
| 📦 **[Install](docs/INSTALL.md)** | Installing, updating and removing the app on each platform |
| ⚙️ **[How it works](docs/HOW-IT-WORKS.md)** | What happens behind the scenes: sessions, status, worktrees, the diff |
| 🎨 **[Design](docs/DESIGN.md)** | Lemonade, the themes, the icon and the layouts |
| 🏗️ **[Architecture](docs/ARCHITECTURE.md)** | Modules, presenters, dependency injection and tests |
| 🛠️ **[Development](docs/DEVELOPMENT.md)** | Building, running and testing it yourself |
| 🗺️ **[Roadmap](docs/ROADMAP.md)** | What isn't done yet |
| 🚀 **[Releasing](RELEASING.md)** | How a version gets from this repo to your computer |

<br>

## 📄 License

Lemonade Code is free to use, change and share, at home or at work. What you can't do is sell it, or
sell a product or service whose value comes mainly from it. The exact terms are in the
**[license](LICENSE)**: the Apache 2.0 license with the Commons Clause added.

<br>

<div align="center">
<sub>Made with Kotlin, Compose Multiplatform and 🍋 <a href="https://github.com/teya-engineering/lemonade-design-system">Lemonade</a>.</sub>
</div>
