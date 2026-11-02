# Development

Build, run and test Lemonade Code yourself. For how the code is laid out, see
[Architecture](ARCHITECTURE.md).

## What you need

- **JDK 21.** Gradle uses toolchains, so point `JAVA_HOME` at any JDK 21 and it does the rest.
- **git**, for the diff and for the integration tests.
- macOS, Linux or Windows. Everything below runs from the repository root.

## Run it

```sh
./gradlew :apps:desktop:run      # the app, opening one terminal in your home folder
./gradlew :sample:run            # the design showcase: every screen, with fixed data
```

> [!WARNING]
> If you're working on Lemonade Code from a terminal *inside* a copy started with
> `./gradlew :apps:desktop:run`, don't build in the same checkout while it runs. That copy loads its
> classes from the repository's `build` folders, and a rebuild replaces them under it. Install a
> packaged copy (below) for day-to-day use instead.

## Build installers

```sh
./gradlew :apps:desktop:packageDmg            # macOS: a Lemonade Code.app inside a DMG
./gradlew :apps:desktop:packageMsi            # Windows: an MSI
./gradlew :apps:desktop:packageDeb            # Linux: a .deb
./gradlew :apps:desktop:createDistributable   # any platform: an app folder you can run in place
```

Output lands in `apps/desktop/build/compose/binaries/main/`.

## Test

```sh
./gradlew allTests                    # every module's tests
./gradlew :workspace:impl:allTests    # one module's tests
```

The tests are described in [Architecture → Tests](ARCHITECTURE.md#tests). A few tips:

- `AppGraphSmokeTest` builds the real app and saves sessions like the real app does. Set
  `XDG_CONFIG_HOME` to a temporary folder on Linux so it doesn't touch your own saved sessions.
- Some timing-sensitive tests (Turbine's 3-second timeout, the session-saving test) can time out when
  the whole suite runs at once on a busy machine. Run the module on its own before treating that as a
  failure.

## Format

```sh
./gradlew spotlessApply    # format everything (ktlint, following .editorconfig)
./gradlew spotlessCheck    # check without changing anything
```

## Screenshots

The showcase can render every design screen to a PNG, without opening a window:

```sh
./gradlew :sample:renderScreens                # → sample/build/screens
./gradlew :sample:renderScreens -Ptheme=light  # light theme
./gradlew :sample:renderScreens -Plang=pt      # Portuguese
```

The images in [`docs/images`](images) come from these renders, resized and framed for the docs.

## Strings

All interface text is in `workspace/uimodel/src/commonMain/composeResources/`: `values/strings.xml`
for English and `values-pt/strings.xml` for Portuguese. Add a `values-<language>` folder to add a
language.

## Releasing

Tagging a version builds every platform and drafts a GitHub release. The whole process, from the tag
to Homebrew and the Snap Store, is in [RELEASING.md](../RELEASING.md).
