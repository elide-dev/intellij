# Elide IntelliJ Plugin

[![Release](https://img.shields.io/github/v/release/elide-dev/intellij?label=release&color=blue)](https://github.com/elide-dev/intellij/releases/latest)
[![CI](https://github.com/elide-dev/intellij/actions/workflows/ci.yml/badge.svg)](https://github.com/elide-dev/intellij/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

JetBrains IDE integration for the [Elide](https://elide.dev) runtime and build tooling.

Elide projects (`elide.pkl`) can be imported just like Gradle or Maven projects, and provide dependency info, structured
build and test output, CLI completion for custom run targets, and more.

## Requirements

- IntelliJ IDEA 2025.3 or newer (builds `253` – `263.*`)
- Elide CLI installed, or a distribution directory selected in project settings; the current command/model integration
  targets Elide 1.6.3. Android features require a CLI that exposes the corresponding `android-*` build targets.
- Optional: the [Pkl](https://pkl-lang.org) plugin (`org.pkl`) for manifest editor features; JavaScript/TypeScript
  language support for build-extension completion.

## Installation

Add the Elide plugin repository under **Settings → Plugins → ⚙ → Manage Plugin Repositories**:

```
https://plugins.elide.dev/intellij
```

then install **Elide** from the marketplace tab. Plugin ZIPs are also attached to every
[GitHub release](https://github.com/elide-dev/intellij/releases).

## Features

**Project import.** Directories containing `elide.pkl` are linked and synced automatically. Changes to manifests,
transitive local Pkl imports, or binary lockfiles prompt a reload. The **Elide** tool window shows linked projects,
modules, and build targets, with Run and Debug actions. Sync batches compile/provided classpaths, attaches requested
dependency classifiers, and imports generated sources without treating kapt stubs as source code.
Kotlin import honors the manifest's compiler options, including its JVM target. Projects enabling
`kotlin.features.compose` use the IDE's bundled Compose compiler plugin for native Kotlin analysis; disabling the
feature and re-syncing removes it. This does not change the Compose or Kotlin versions used by Elide builds.

**Multi-project workspaces.** A manifest declaring `workspace.members` is imported as one project: every member gets
its own modules, source roots, classpath and JDK, and a member consuming a sibling's artifact with `project("name")`
gets a module dependency on it — along with the siblings that artifact brings with it, the way Elide resolves the
classpath — so the IDE compiles and navigates across the workspace. The tool window nests every project's build
targets and source-set modules under that project, and only the root is linked — editing a member's manifest reloads
the workspace. Opening a member directory on its own still works, and points at the workspace above it.

**Run configurations.** Add **Elide** run configurations with command-line completion for entrypoints, CLI commands,
arguments, build tasks and flags. Ad-hoc commands can also be run through **Run Anything**.

**Gutter actions.** Run and Debug icons on Kotlin/Java `main` functions and JUnit tests. When the Pkl plugin is also
installed, build icons also appear on `artifacts` entries in the project manifest, and declared entrypoints and scripts
will also gain Run and Debug actions. An `artifacts` entry declaring a binary Native Image offers Run and Debug of the
image itself alongside its build.

**Build output.** Runs report as a live step tree with progress, timings, skip reasons, and compiler diagnostics rendered
with source excerpts and navigable file locations. Structured CLI progress is decoded rather than displayed as raw
JSON. `elide build` reports to the **Build** tool window, other commands to the **Run** window.

**Test results.** `elide test` configurations show a test tree with pass/fail/skip states, timings, output and
jump-to-source.

**Coverage.** **Run with Coverage** adds `--coverage` and loads JaCoCo execution data or XML and LCOV reports into the
IDE's coverage view, including runs started outside the IDE. Reports from multiple source sets merge into one suite;
line and branch coverage are retained, and an invalid report does not discard other valid reports.

**Debugging.** Entrypoint, test, and explicit build-target configurations support **Debug** via Elide's JDWP support;
the IDE attaches when the CLI announces its endpoint, including Android's dynamically forwarded port. Debugging
`elide test` requires Elide 1.5.3 or newer and is available for JVM code only; for guest languages use DevTools or DAP
instead. Native Image binaries are supported via GDB/LLDB through the JetBrains
[Native Debugging Support](https://plugins.jetbrains.com/plugin/12775) plugin (IntelliJ IDEA Ultimate).

**Manifest editing** (requires the Pkl plugin). Completion and navigation for `jvm.main` and `entrypoint` paths, for
`workspace.members` directories, and for the project and artifact names a `project("name").artifact("name")` reference
or an artifact's `dependsOn`/`from` list carries. Renaming or moving a member directory rewrites the entry that names
it. Inspections report unresolved or invalid JVM entrypoints, members without a manifest, and references to projects
and artifacts the workspace does not declare.

**Build extensions.** Bundled `ElideBuild` TypeScript declarations provide completion before the first sync, without
overriding a project's own global declarations. Pkl extension paths navigate to local files in trusted projects.
JavaScript, TypeScript, and Pkl templates cover build-extension authoring; saving a build script refreshes task discovery.
Manifest gutter actions also recognize extension archives and Android feature modules.

## Android

Import an Elide Android project, then open **Tools → Elide Android** or **Android Devices and Tools** in the Elide
tool-window toolbar. Android operations execute the CLI's discovered targets; they do not require Android Studio's
Gradle model.

- Select an Android application/APK and a ready device, or the project's emulator. Run and Debug save edited files
  before building; Debug attaches the IDE's JVM debugger to the app.
- Inspect the environment without syncing; check/install SDK components, create/start/stop project AVDs, generate
  signing keystores, build feature modules, and build/open obfuscation mappings. SDK installation asks before accepting
  SDK licenses.
- **Run Android Instrumented Tests** runs discovered `android-test-*` targets and displays method results and device
  failure stacks in the native test tree, keeping results from different targets separate.
- **Open Native Android Logcat**, **Device Manager**, and **App Inspection** delegate to the Android tooling already
  available in the IDE/project. Device Manager owns wireless pairing; App Inspection owns device database inspection.
  These tools are not bundled with every IDEA installation. If unavailable, the action explains the prerequisite;
  Elide does not ship a replacement log viewer, SQL browser, or pairing UI. IDEA's Database Tools does not by itself
  provide live access to app-private Android databases.

Android sync imports resource/assets/native-library roots, generated resource identifiers and processor output,
exploded AAR libraries with Maven coordinates and sources, and dependencies on sibling workspace modules.

### Compose previews

Open a Kotlin file and use its preview gutter icon or **Open Elide Compose Preview** from the editor context menu.
The gallery uses **Komodo live previews**, including `@Preview` configurations and parameter providers. No device or
APK install is needed. Select the consuming application when a library belongs to several apps.

Install the standalone Komodo distribution at `~/.local/share/komodo`, including its `plugins` directory.
The launcher is `komodo` (`komodo.exe` on Windows). Komodo is resolved independently, **not through Elide**.
The plugin starts a preview-only Komodo process; IDEA's Kotlin analysis and editor tooling remain unchanged.

The icon toolbar provides **Refresh**, **Cancel**, **Auto refresh**, **Fit previews**, **Inspect layout**, and
**Preview logs**. Layout inspection shows measured bounds, component selection, parent navigation, and jump-to-source.
Outside inspection mode, previews accept pointer and keyboard input; **F6** releases preview keyboard focus.
The native IDE console preserves build/Komodo diagnostics across refreshes and opens on errors without taking focus.

Auto refresh synchronizes unsaved Kotlin buffers without saving files or replacing normal build outputs. Kotlin edits
and saves reuse the live compiler; rapid edits are coalesced without cancelling an in-flight compilation. Unchanged
sources reuse rendered previews, and ordinary string edits reuse preview discovery. Frame-only updates repaint the
affected canvas without rebuilding the gallery or resetting inspector selection and scroll position.
Compilation failures retain the compiler and last successful images, visibly marked stale, so the next edit can recover.
Rehosting previews retains existing images until replacement frames arrive. **Refresh** explicitly rebuilds project
inputs. Hiding the tool window stops Komodo and removes its private session files. Java, generated-code, resource,
and build-configuration changes require saving
and refreshing/rebuilding. The preview compiler does not perform Hilt bytecode transforms. The current layoutlib host
does not support wheel input; wheel scrolling remains available for the gallery.

Rendering requires a trusted project, an imported Android application, Elide 1.6.3+, a JDK 17+, a compatible standalone
Komodo distribution, and an initial download of SHA-256-verified renderer/layoutlib artifacts. The renderer supports
macOS ARM64/x64, Linux x64, and Windows x64; a matching Komodo build is also required. There is no snapshot-renderer
fallback when Komodo is missing or incompatible.

## Settings

**Settings → Build, Execution, Deployment → Build Tools → Elide** configures each linked project:

| Setting | Behavior |
|---|---|
| Elide distribution | Auto-detect uses `$ELIDE_HOME`, standard installation locations, then executable entries on `PATH` (including symlinks). An invalid explicit location reports an error instead of silently selecting another installation. Run configurations also have an **Elide Home** override. |
| Build flags | Flags passed through global `-f` options, before the command. |
| Build / Run / Test / Install options | JSON objects of default CLI options, for example `{"concurrency": 4}`. Scalars become option values, arrays repeat an option, `true` enables a switch, and `false` omits it. Explicit command-line options take precedence; program arguments after `--` are untouched. |
| Dependency classifiers | Comma-separated classifiers to install and attach; defaults to `sources`. Changing these or build flags invalidates the corresponding install marker. |
| JDK home | Explicit project JDK. Otherwise import considers the manifest's Java home, `JAVA_HOME`, and installed matching SDKs. |
| Android SDK / Emulator window | SDK location for device tooling and whether project-emulator launches show a window. CLI builds continue to use the manifest/environment SDK configuration. |

## Building

```bash
./gradlew buildPlugin      # plugin ZIP -> build/distributions/
./gradlew runIde           # sandboxed IDE with the plugin installed
make verify                # descriptor checks + IntelliJ Plugin Verifier
tools/codegen.sh           # regenerate the manifest model from Elide's Pkl schema
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup and repository layout.

## Releases

Releases are driven by [release-please](https://github.com/googleapis/release-please): run the **Release** workflow to
open a release pull request, then merge it to tag, publish to the JetBrains Marketplace and the Elide plugin repository.
Commits follow [Conventional Commits](https://www.conventionalcommits.org/), enforced by commitlint.

## Project documents

- [CHANGELOG.md](CHANGELOG.md) — release notes
- [CONTRIBUTING.md](CONTRIBUTING.md) — development setup and contribution workflow
- [SECURITY.md](SECURITY.md) — vulnerability reporting
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) — community expectations
- [docs/PLATFORM_APIS.md](docs/PLATFORM_APIS.md) — platform APIs the plugin uses

## License

MIT (see [LICENSE](LICENSE)).
