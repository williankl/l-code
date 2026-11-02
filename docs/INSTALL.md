# Install

Lemonade Code is free and runs on macOS, Windows and Linux. Every release is on the
[Releases page](https://github.com/williankl/loupe/releases/latest), with a `SHA256SUMS` file to
check your download against.

**Before you start:** install `git`. Sessions work without it, but the Diff tab needs it to show what
changed. Coding agents such as [Claude Code](https://claude.com/claude-code) are optional; install
them the usual way and run them in any session.

- [macOS](#macos)
- [Windows](#windows)
- [Linux](#linux)
- [Updating](#updating)
- [Where your settings live](#where-your-settings-live)
- [Uninstalling](#uninstalling)

---

## macOS

For Apple Silicon and Intel Macs.

**With Homebrew** (recommended):

```sh
brew install --cask williankl/tap/lemonade-code
```

**Or by hand:** download the DMG for your Mac from
[Releases](https://github.com/williankl/loupe/releases/latest):

| Your Mac | File |
|---|---|
| Apple Silicon (M1 and newer) | `Lemonade-Code-<version>-macos-arm64.dmg` |
| Intel | `Lemonade-Code-<version>-macos-x64.dmg` |

Open it and drag **Lemonade Code** to **Applications**.

> [!TIP]
> If macOS says it can't check the app for malicious software, open **System Settings → Privacy &
> Security**, scroll down and press **Open Anyway**. This only happens with builds that aren't signed
> yet.

---

## Windows

For 64-bit Windows.

1. Download `Lemonade-Code-<version>-windows-x64.msi` from
   [Releases](https://github.com/williankl/loupe/releases/latest).
2. Run it. It installs for your user only, so no administrator rights are needed, and adds Lemonade
   Code to the Start menu.

> [!TIP]
> The installer isn't signed yet, so Windows SmartScreen may warn you the first time. Press
> **More info → Run anyway**.

Sessions run PowerShell: `pwsh` if you have PowerShell 7, otherwise Windows PowerShell, otherwise
`cmd.exe`. Your PowerShell profile loads as usual.

---

## Linux

For 64-bit (x64) Linux.

**Ubuntu, with the Snap Store:** find *Lemonade Code* in the App Center, or run

```sh
sudo snap install lemonade-code --classic
```

The snap uses classic confinement, like other terminals and IDEs, because it runs your own shell,
git and agents in any folder.

**Ubuntu or Debian, with the .deb:** download `lemonade-code_<version>_amd64.deb` from
[Releases](https://github.com/williankl/loupe/releases/latest), then

```sh
sudo apt install ./lemonade-code_<version>_amd64.deb
```

**Any other distribution, with the .tar.gz:** download `lemonade-code-<version>-linux-x64.tar.gz`,
unpack it wherever you like, and run the launcher inside:

```sh
tar -xzf lemonade-code-<version>-linux-x64.tar.gz
./lemonade-code/bin/"Lemonade Code"
```

---

## Updating

| Installed with | How it updates |
|---|---|
| DMG, MSI, .deb or .tar.gz | By itself. An **Update** button shows up next to Settings when a new version is out. One click and the app restarts on it, with your sessions restored. |
| Homebrew | `brew upgrade --cask lemonade-code` |
| Snap Store | Automatically, like every snap |

Updating a .deb asks for your password, because the package is installed system-wide.

---

## Where your settings live

| Platform | Folder |
|---|---|
| macOS | `~/Library/Application Support/Lemonade Code/` |
| Windows | `%APPDATA%\Lemonade Code\` |
| Linux | `~/.config/lemonade-code/` |

Inside are `preferences.json` (your settings) and `sessions.json` (the sessions that come back when
you reopen the app). If you used the app back when it was called Loupe, its old settings folder is
moved here the first time you open it.

---

## Uninstalling

| Installed with | Remove it with |
|---|---|
| Homebrew | `brew uninstall --cask lemonade-code` |
| DMG | Drag **Lemonade Code** from Applications to the Trash |
| MSI | **Settings → Apps → Installed apps → Lemonade Code → Uninstall** |
| Snap Store | `sudo snap remove lemonade-code` |
| .deb | `sudo apt remove lemonade-code` |
| .tar.gz | Delete the `lemonade-code` folder |

To remove your settings and saved sessions as well, delete the folder from
[Where your settings live](#where-your-settings-live).
