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

## Devices

- **iOS screens arrive a few times a second.** Android streams its screen as video, but an iPhone
  or iPad is mirrored with one `idevicescreenshot` after another.
- **iOS is view only.** iPhones and iPads can't be tapped or typed on from Lemonade Code, and their
  screen only shows over a cable. Newer iOS versions may need the developer disk image mounted
  before `idevicescreenshot` can capture them.
- **Recording iOS screens.** iPhones and iPads send their screen as screenshots, a few a second, so
  only Android screens can be recorded.
- **One video per way the screen faces.** Turning the phone while recording goes on in a second
  video, since players don't follow a video that changes size.
- **Recording at full resolution is unmeasured.** It keeps the mirror's bit rate, and what it costs
  the mirror in memory and frame rate hasn't been measured on a phone yet.
- **An encoder that pads its size counts as refusing full resolution.** One that rounds the size
  up, e.g. to a multiple of 16, without saying it crops back, gives pictures of another size than
  asked, which reads as a refusal.
- **A screen that turns off while recording ends it.** The phone sends no video with its screen
  off, so at `screenrecord`'s next restart, within three minutes, the stream doesn't start again and
  the recording stops as "stopped sending video".
- **Recording at the edges of a cut-short picture.** When the stream goes quiet, the mirror hands
  over the picture in hand so the screen's last change shows. If more of that picture comes after,
  it was cut short: the recording takes it back, and the video has a short gap there until the next
  picture, over which players hold the one before. Three edge cases are left as they are:
  - Stopping before more comes keeps that picture, cut short or not.
  - A turn or a restart just after it ends the stream before more can come, so it's kept.
  - When the last picture was taken back and none comes before Stop, the video ends at that
    picture's time rather than at Stop.
- **Tested against recorded tool output.** The Android and iOS support is checked against what
  `adb` and libimobiledevice print, not yet against every device.

## Platforms and releases

- **Signed Windows installer.** Windows SmartScreen warns on the first run until the MSI is signed.
- **ARM on Linux and Windows.** Builds are x64 only for now (Macs get both Apple Silicon and Intel).

## Under the hood

- **Detekt** isn't set up yet; formatting is enforced with Spotless and ktlint.
