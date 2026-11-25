# Roadmap

What Lemonade Code doesn't do yet. Want something that isn't here?
[Open an issue](https://github.com/williankl/loupe/issues).

## In the terminal

- **Mouse clicks in full-screen programs.** The wheel already goes to programs that ask for it (like
  Claude Code's fullscreen mode), but clicks and drags always select text; programs like `vim` or
  `htop` don't receive them yet.
- **Programs don't survive quitting.** Shells start fresh in the same folder. Claude Code
  conversations are resumed; other programs are not.

## Importing sessions

- **herdr only, so far.** Settings › Import sessions reads herdr. Each other tool is a new source.
- **One conversation per herdr workspace.** A workspace with Claude Code in several panes brings the
  one in its focused pane; the others aren't resumed. Agents other than Claude Code start fresh.
- **herdr's saved layout.** While herdr runs, its layout is read as it last saved it, a few seconds
  behind at most.
- **Imported once, mostly.** An import is remembered by herdr's workspace id, and herdr can hand a
  closed workspace's id to a new one: after a restart, when the highest-numbered workspace was
  closed, or when a herdr session is deleted and made again under the same name. Then:
  - the new workspace can show as *Already imported* when it never was;
  - a worktree imported under such an id can nest under an unrelated session that's still open.
- **Offered again, now and then.** A workspace from an old herdr layout, saved before workspaces
  had ids, is offered once more after a current herdr saves it again. And if saving the record of an
  import fails partway, the session that had just opened can be listed again.

## Status

- **Agents have to report it.** Claude Code does. Other agents show no status until they report one
  the same way.

## Design

- **One-line buttons in the send bar.** Lemonade's buttons have a fixed height, so the design's small
  captions under *Paste into terminal* and *New sub-session* ("this session", "clean context") are
  left out for now.

## Devices

- **iOS screens arrive a few times a second.** Android streams its screen as video, but an iPhone
  or iPad is mirrored with one `idevicescreenshot` after another.
- **iOS is view only.** iPhones and iPads can't be tapped or typed on from Lemonade Code, and their
  screen only shows over a cable. Newer iOS versions may need the developer disk image mounted
  before `idevicescreenshot` can capture them.
- **Tested against recorded tool output.** The Android and iOS support is checked against what
  `adb` and libimobiledevice print, not yet against every device.

## Platforms and releases

- **Signed Windows installer.** Windows SmartScreen warns on the first run until the MSI is signed.
- **ARM on Linux and Windows.** Builds are x64 only for now (Macs get both Apple Silicon and Intel).

## Under the hood

- **Detekt** isn't set up yet; formatting is enforced with Spotless and ktlint.
