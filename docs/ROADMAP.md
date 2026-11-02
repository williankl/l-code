# Roadmap

What Lemonade Code doesn't do yet. Want something that isn't here?
[Open an issue](https://github.com/williankl/loupe/issues).

## In the terminal

- **Mouse clicks in full-screen programs.** The wheel already goes to programs that ask for it (like
  Claude Code's fullscreen mode), but clicks and drags always select text; programs like `vim` or
  `htop` don't receive them yet.
- **Programs don't survive quitting.** Shells start fresh in the same folder. Claude Code
  conversations are resumed; other programs are not.

## Status

- **Agents have to report it.** Claude Code does. Other agents show no status until they report one
  the same way.

## Design

- **One-line buttons in the send bar.** Lemonade's buttons have a fixed height, so the design's small
  captions under *Paste into terminal* and *New sub-session* ("this session", "clean context") are
  left out for now.

## Platforms and releases

- **Signed Windows installer.** Windows SmartScreen warns on the first run until the MSI is signed.
- **ARM on Linux and Windows.** Builds are x64 only for now (Macs get both Apple Silicon and Intel).

## Under the hood

- **Detekt** isn't set up yet; formatting is enforced with Spotless and ktlint.
