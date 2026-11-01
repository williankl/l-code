# Releasing

How a version gets from this repo to users: GitHub Releases, Homebrew (macOS) and the Snap Store,
which Ubuntu's App Center installs from.

## Cutting a release

1. Tag and push: `git tag v1.2.0 && git push origin v1.2.0`. Versions are `MAJOR.MINOR.PATCH`
   with `MAJOR` of at least 1 (a DMG rule).
2. The **Release** workflow builds the Mac DMGs (Apple Silicon and Intel), the `.deb` and the snap,
   and drafts a GitHub release with them and a `SHA256SUMS` file. Nothing is public yet.
3. Check the draft: download a build, try it, edit the notes.
4. Publish the draft. The **Publish** workflow then updates the Homebrew cask and uploads the snap.

A dry run: Actions → Release → Run workflow. It builds everything and keeps the files as workflow
artifacts; no release, no stores.

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

## Not covered yet

Windows (the MSI format is configured but not built) and Linux on ARM.
