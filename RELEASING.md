# Releasing

How a version gets from this repo to users: GitHub Releases (macOS, Windows, Linux), Homebrew
(macOS) and the Snap Store, which Ubuntu's App Center installs from. Installs from a release file
then update themselves.

## Cutting a release

1. Tag and push: `git tag v1.2.0 && git push origin v1.2.0`. Versions are `MAJOR.MINOR.PATCH`
   above `0.0.0`, with `MAJOR` and `MINOR` up to 255 and `PATCH` up to 65535 (MSI rules), so `0.x`
   versions such as `0.0.1` work too.
2. The **Release** workflow builds every platform at once and drafts a GitHub release with the
   files, a `SHA256SUMS` file and notes that start with a download table, one row per platform,
   followed by the changes since the last release. The run's summary shows the same table.
3. Check the draft: download a build, try it, edit the notes.
4. Publish the draft. The **Publish** workflow then updates the Homebrew cask and uploads the snap,
   and installs from a release file offer the update within a few hours (see
   [Self-update](#self-update)).

| File | Platform |
|---|---|
| `Lemonade-Code-1.2.0-macos-arm64.dmg` | macOS, Apple Silicon |
| `Lemonade-Code-1.2.0-macos-x64.dmg` | macOS, Intel |
| `Lemonade-Code-1.2.0-windows-x64.msi` | Windows, installs for the current user |
| `lemonade-code_1.2.0_amd64.deb` | Ubuntu, Debian |
| `lemonade-code-1.2.0-linux-x64.tar.gz` | Any Linux, unpacked anywhere |
| `lemonade-code_1.2.0_amd64.snap` | The Snap Store (also attached) |

A dry run: Actions → Release → Run workflow. It builds everything and keeps the files as workflow
artifacts, listed in the run's summary; no release, no stores. To build one platform, run
**Build for macOS**, **Build for Windows** or **Build for Linux** the same way.

### The workflows

| File | What it does |
|---|---|
| `.github/workflows/release.yml` | On a tag: checks the version, calls the three builds, drafts the release. |
| `.github/workflows/build-macos.yml` | The two DMGs, signed and notarized once the secrets are set. |
| `.github/workflows/build-windows.yml` | Checks the app starts a terminal on Windows, then builds the MSI. |
| `.github/workflows/build-linux.yml` | The .deb, the tar.gz and the snap. |
| `.github/workflows/publish.yml` | On publishing: the Homebrew cask and the Snap Store. |
| `.github/actions/version` | The version from the tag (or the input), checked once for every format. |
| `.github/actions/setup-gradle` | Java 21 and Gradle with its caches. |
| `.github/actions/upload-build` | Keeps `dist/` as an artifact and lists it, with checksums, in the summary. |
| `.github/scripts/release-notes.sh` | The download table and install steps at the top of the notes. |

**Caches.** Gradle's dependencies and build cache are restored in every job, and Windows keeps the
WiX toolset Compose downloads. GitHub only lets a tag read caches saved on `main`, so runs on
`main` save them and tag builds read them: run the dry run on `main` now and then (after
dependency updates, say) to keep releases fast.

## One-time setup

Each part is skipped until its secret exists, so they can be set up one at a time. Secrets and
variables live in Settings → Secrets and variables → Actions.

### Before the first release

- Make the repository public. Homebrew and users download the builds from the release, and a
  private repository's release files need a login.
- Add a LICENSE file, then fill in `license:` in `snap/snapcraft.yaml`.

### macOS signing (needed for Homebrew)

Without it the Mac builds are unsigned: they still get built and attached, but macOS blocks them
until the user clicks Open Anyway in System Settings.

1. Join the Apple Developer Program.
2. Create a **Developer ID Application** certificate (Xcode → Settings → Accounts, or the developer
   site), then export it with its private key from Keychain Access as a `.p12` with a password.
3. Create an app-specific password at account.apple.com, for notarization.
4. Add the secrets:

| Secret | Value |
|---|---|
| `MACOS_CERTIFICATE` | The `.p12`, base64: `base64 -i certificate.p12 \| pbcopy` |
| `MACOS_CERTIFICATE_PASSWORD` | The `.p12` password |
| `MACOS_SIGNING_IDENTITY` | The certificate's name without the prefix, e.g. `Jane Doe (AB12CD34EF)` |
| `APPLE_ID` | Your Apple ID email |
| `APPLE_APP_PASSWORD` | The app-specific password |
| `APPLE_TEAM_ID` | The 10-character team ID |

Compose signs the app and the native libraries inside its jars.
`packaging/macos/sign-pty4j-helper.sh` signs the one program Compose misses (pty4j's spawn
helper), which notarization would otherwise reject.

### Homebrew

1. Create a public repository `williankl/homebrew-tap` (the name must start with `homebrew-`).
2. Create a fine-grained token with Contents read and write on that repository only, and save it as
   the `HOMEBREW_TAP_TOKEN` secret. Do this once Mac signing works, so the tap never points at
   unsigned builds.

Publishing writes `Casks/lemonade-code.rb` there (from `packaging/homebrew/write-cask.sh`). Users
install with:

```sh
brew install --cask williankl/tap/lemonade-code
```

Later the cask can go to Homebrew's main repository (`brew install --cask lemonade-code`), once the
project meets their [notability bar](https://docs.brew.sh/Acceptable-Casks).

### Snap Store and Ubuntu App Center

1. Create an account at [snapcraft.io](https://snapcraft.io) and reserve the name:
   `snapcraft register lemonade-code`.
2. Export upload credentials limited to this snap and save them as the
   `SNAPCRAFT_STORE_CREDENTIALS` secret:
   ```sh
   snapcraft export-login --snaps=lemonade-code \
     --acls=package_access,package_push,package_update,package_release credentials.txt
   ```
3. Ask for classic confinement: a new topic in the forum's
   [store-requests](https://forum.snapcraft.io/c/store-requests) category (text below). Uploads wait
   in manual review until it is granted. Reviewers reserve classic for mature, well-known apps, so a
   new app may be asked to wait.
4. Fill in the listing on snapcraft.io: screenshots (`./gradlew :sample:renderScreens` renders
   them), description, the Development category.
5. Uploads go to the `edge` channel. Once you trust the pipeline, set the repository variable
   `SNAP_CHANNEL` to `stable`; App Center shows stable releases.

Users then install from App Center, or with `sudo snap install lemonade-code --classic`.

Classic confinement request:

> **Snap:** lemonade-code
>
> **Category:** IDEs, terminal emulators
>
> **What it is:** Lemonade Code is a terminal IDE for reviewing work. Every session is the user's
> login shell in a terminal; the app follows the shell's folder, shows the git diff for that
> checkout, and sends review comments to the program running in the terminal.
>
> **Why classic:** It runs the user's own login shell and whatever they start in it (git,
> compilers, coding agents such as Claude Code); strict confinement would swap those for the base
> snap's tools. It reads the shell's current folder from /proc, runs git in any folder the user
> opens, hidden ones included, and creates git worktrees next to the repository. The `home`
> interface leaves out hidden files and the rest of the file system, and no interface runs the
> host's programs.
>
> **Source:** https://github.com/williankl/loupe

### Building the snap by hand

```sh
./gradlew :apps:desktop:createDistributable -PappVersion=1.2.0
rm -rf build/snap && mkdir -p build/snap
cp -a "apps/desktop/build/compose/binaries/main/app/Lemonade Code" build/snap/app
echo 1.2.0 > build/snap/version
snapcraft pack
sudo snap install --dangerous --classic lemonade-code_1.2.0_amd64.snap
```

### Windows

Nothing to set up. The MSI installs for the current user (no admin rights), into
`%LOCALAPPDATA%\Lemonade Code`, with a Start menu entry. Its upgrade code is fixed in
`apps/desktop/build.gradle.kts`, so each version replaces the last; never change it.

The MSI isn't signed yet, so Windows SmartScreen warns on first run (More info → Run anyway).
Signing needs a code-signing certificate and a `signtool` step in `build-windows.yml`.

## Self-update

Installed from the DMG, the MSI, the .deb or the tar.gz, Lemonade Code looks for a newer release
10 seconds after launch and every 6 hours. A release counts once it is published (not a draft, not
a pre-release), has a higher version, and carries both the file for that install and
`SHA256SUMS`. An **Update** button then shows next to Settings; its panel downloads the file,
checks it against `SHA256SUMS`, and the app quits and comes back on the new version, sessions
restored.

| Installed from | How it updates |
|---|---|
| DMG | The new bundle is copied out of the DMG next to the old one, then swapped in. |
| MSI | `msiexec` installs the new MSI over the old one. |
| .deb | `pkexec dpkg -i`, which asks for the user's password. |
| tar.gz | The new folder is unpacked next to the old one, then swapped in. |
| Homebrew, Snap Store | Not by the app: `brew upgrade` and snapd do it. |

The app reads releases from GitHub's API without signing in, so the repository must be public.
`updates/impl` holds it all: `InstallationDetector` tells the install apart, `InstallKind.assetName`
names the file each install downloads (keep it in step with the build workflows), and the helper
scripts that swap the files after the app quits are in its resources. A development build
(`./gradlew run`) never checks; one built with `createDistributable` checks like a tar.gz.

## Not covered yet

Windows code signing, and Linux and Windows on ARM.
