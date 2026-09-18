# Elide IntelliJ Plugin

[![Release](https://img.shields.io/github/v/release/elide-dev/intellij?label=release&color=blue)](https://github.com/elide-dev/intellij/releases/latest)
[![CI](https://github.com/elide-dev/intellij/actions/workflows/ci.yml/badge.svg)](https://github.com/elide-dev/intellij/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

JetBrains IDE integration for the [Elide](https://elide.dev) runtime and build tooling.

Elide projects (`elide.pkl`) can be imported just like Gradle or Maven projects, and provide dependency info, structured
build and test output, CLI completion for custom run targets, and more.

## Requirements

- IntelliJ IDEA 2025.3 or newer (builds `253` – `262.*`)
- Elide CLI installed, or a distribution directory selected in project settings
- Optional: the [Pkl](https://pkl-lang.org) plugin (`org.pkl`) for manifest editor features

## Installation

Add the Elide plugin repository under **Settings → Plugins → ⚙ → Manage Plugin Repositories**:

```
https://plugins.elide.dev/intellij
```

then install **Elide** from the marketplace tab. Plugin ZIPs are also attached to every
[GitHub release](https://github.com/elide-dev/intellij/releases).

## Features

**Project import.** Directories containing `elide.pkl` are linked and synced automatically; manifest or lockfile changes
prompt a reload. The **Elide** tool window shows linked projects, their modules, and the build targets reported by the
CLI, runnable from the tree view.

**Run configurations.** Add **Elide** run configurations with command-line completion for entrypoints, CLI commands,
arguments, build tasks and flags. Ad-hoc commands can also be run through **Run Anything**.

**Gutter actions.** Run and Debug icons on Kotlin/Java `main` functions and JUnit tests. When the Pkl plugin is also
installed, build icons also appear on `artifacts` entries in the project manifest, and declared entrypoints and scripts
will also gain Run and Debug actions. An `artifacts` entry declaring a binary Native Image offers Run and Debug of the
image itself alongside its build.

**Build output.** Runs report as a step tree with timings, skip reasons, and compiler diagnostics rendered with source
excerpts and navigable file locations. `elide build` reports to the **Build** tool window, other commands to the **Run**
window.

**Test results.** `elide test` configurations show a test tree with pass/fail/skip states, timings, output and
jump-to-source.

**Coverage.** **Run with Coverage** adds `--coverage` and loads the resulting JaCoCo and LCOV reports into the IDE's
coverage view, including runs started outside the IDE.

**Debugging.** Entrypoint and test configurations support **Debug** via Elide's JDWP support; the IDE attaches
automatically. Debugging `elide test` requires Elide 1.5.3 or newer and is available for JVM code only, for guest
languages use DevTools or DAP instead. Native Image binaries are also supported via GDB/LLDB through the JetBrains
[Native Debugging Support](https://plugins.jetbrains.com/plugin/12775) plugin (IntelliJ IDEA Ultimate)

**Manifest editing** (requires the Pkl plugin). Completion and navigation for `jvm.main` and `entrypoint` paths, plus
inspections for unresolved or invalid JVM entrypoints.

## Settings

**Settings → Build, Execution, Deployment → Build Tools → Elide** selects the Elide distribution per project:
*Auto-detect* (default) or a custom path. Auto-detection uses `$ELIDE_HOME`, then the standard install locations for the
platform. Run targets carry their own **Elide Home** field.

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
