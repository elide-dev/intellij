# Elide IntelliJ Plugin

[![CI](https://github.com/elide-dev/intellij/actions/workflows/ci.yml/badge.svg)](https://github.com/elide-dev/intellij/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

JetBrains IDE integration for the [Elide](https://elide.dev) runtime and build tooling.

The plugin registers Elide as an IDE *external system*: an Elide project (`elide.pkl`) is imported like a Gradle or
Maven project, its dependencies become IDE libraries, and every `elide` command runs from the IDE with completion,
console output, and JDWP support.

## Requirements

- IntelliJ IDEA 2025.3 or newer (builds `253` – `262.*`)
- Elide CLI installed, or a distribution directory selectable in project settings
- Optional: the [Pkl](https://pkl-lang.org) language plugin (`org.pkl`), which enables manifest editor features

## Installation

The plugin is published to the Elide plugin repository. Add it under **Settings → Plugins → ⚙ → Manage Plugin
Repositories**:

```
https://plugins.elide.dev/intellij
```

Then install **Elide** from the marketplace tab.

Plugin ZIPs are also attached to every [GitHub release](https://github.com/elide-dev/intellij/releases) and can be
installed with **Settings → Plugins → ⚙ → Install Plugin from Disk**.

## Importing a project

A directory is an Elide project when it contains an `elide.pkl` manifest.

- **Open** a project directory: every base directory containing a manifest is linked and synced in the background.
- **Open an existing IDE project** that gains a manifest: the IDE offers to link it.
- Manifest or lockfile changes mark the project stale, and the IDE offers a reload.
- Changing the configured Elide distribution resyncs the affected projects immediately.

The **Elide** tool window shows the linked projects and their module structure, with the reload actions for keeping them
in sync.

## Running

The plugin contributes the **Elide** run configuration type, used to invoke the Elide CLI. The command-line field
completes as you type, scoped to the linked project:

- project entrypoints declared in the manifest,
- CLI commands (`run`, `install`, `build`, `test`, etc.),
- positional arguments for commands that take them,
- global and command-specific flags with their short forms and enumerated values

Ad-hoc commands can be run without creating a configuration through **Run Anything** (double-⌃, or double-Ctrl, then
type `elide …`), which offers the same completions and runs in the linked project's directory.

### Gutter actions

Run and debug icons appear in the gutter for:

- `elide.pkl` manifests: the `jvm.main` property, each `entrypoint` element, and each `scripts` key (requires the Pkl
  plugin).
- Kotlin and Java `main` functions whose class is an entrypoint of a linked Elide project.
- JUnit test classes and methods in a linked Elide project.

## Debugging

Run configurations offered by the plugin can be debugged using Elide's own JDWP support. The IDE will launch the Elide
CLI with the `--debugger` option and connect to the agent automatically.

> Currently only entrypoint configurations support the IDE's "Debug" action, debugging test configurations will be
> available soon.

## Manifest editing

With the Pkl plugin installed, `elide.pkl` provides:

- Navigation and class-name completion for `jvm { main = "..." }`.
- Path completion and navigation for `entrypoint` file references.
- Inspections: **Unresolved JVM Entrypoint** (`Cannot resolve class X`) and **Invalid JVM Entrypoint**
  (`Class X has no 'main' method`).

## Settings

**Settings → Build, Execution, Deployment → Build Tools → Elide**, per linked project:

- **Elide distribution** — *Auto-detect* (default) or *Custom path*, which enables a directory chooser for a
  distribution root.

Auto-detection uses `$ELIDE_HOME` if set, otherwise the first existing candidate:

| Platform | Candidates, in order                                                                                       |
|----------|------------------------------------------------------------------------------------------------------------|
| Unix     | `$XDG_DATA_HOME/elide`, `~/.local/share/elide`, `/opt/elide/current`, `~/.elide`                           |
| Windows  | `%LOCALAPPDATA%\elide`, `%ProgramFiles%\Elide`, `%USERPROFILE%\.local\share\elide`, `%USERPROFILE%\.elide` |

> Run configurations executed against an IntelliJ *run target* carry their own **Elide Home** field, so a target can
> use a distribution different from the one configured for the project.

## Building

```bash
./gradlew buildPlugin      # plugin ZIP -> build/distributions/
./gradlew runIde           # sandboxed IDE with the plugin installed
make verify                # descriptor checks + IntelliJ Plugin Verifier
```

Development setup, verification expectations, and the repository layout are documented in
[CONTRIBUTING.md](CONTRIBUTING.md).

### Manifest schema codegen

The Elide project manifest model in `src/main/kotlin/dev/elide/tooling/manifest` is **generated** from Elide's published
Pkl schema at <https://pkl.elide.dev/v2/> by [`brine`](https://elide.dev), together with the bundled copy of that schema
in `src/main/pkl`, packaged into the plugin jar under `/elide/pkl/`. Both are committed, and regeneration is a manual
step, so CI never needs `brine`:

```bash
tools/codegen.sh          # or: make codegen
```

## Release workflow

Releases are triggered by pushing a version tag:

1. `make bump [major|minor|patch]` — validates that `CHANGELOG.md` has an `Unreleased` section, dates it under the new
   version, writes `.version`, commits, and tags locally.
2. Push the commit and tag:
   ```bash
   git push origin HEAD v0.8.0
   ```
3. The `Release` workflow builds and verifies the plugin, publishes it to the JetBrains Marketplace and the Elide plugin
   repository, and creates a GitHub Release whose notes are the changelog section for that version.

**Commit convention:** commits follow [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`,
`chore:`, etc.). A commitlint check enforces this on every PR.

## Project documents

- [CHANGELOG.md](CHANGELOG.md) — release notes, and the source of the plugin's change notes
- [CONTRIBUTING.md](CONTRIBUTING.md) — development setup and contribution workflow
- [SECURITY.md](SECURITY.md) — vulnerability reporting
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) — community expectations
- [docs/PLATFORM_APIS.md](docs/PLATFORM_APIS.md) — experimental, internal and deprecated platform APIs the plugin uses

## License

MIT — see [LICENSE](LICENSE).
