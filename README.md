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

Under **Tasks**, it lists the build targets the CLI reports for the project (`elide build --inspect`): the manifest's
artifacts, plus the targets Elide derives for source sets, dependencies and entrypoints. Double-click one, or use
**Run** from its context menu, to run `elide build <target>`; the same menu assigns it a keyboard shortcut or runs it
before a sync or build.

## Running

The plugin contributes the **Elide** run configuration type, used to invoke the Elide CLI. The command-line field
completes as you type, scoped to the linked project:

- project entrypoints declared in the manifest,
- CLI commands (`run`, `install`, `build`, `test`, etc.),
- positional arguments for commands that take them, including the build tasks of the linked project after `build`,
- global and command-specific flags with their short forms and enumerated values,
- the options a named build task declares, such as `--fresh` after `build maven-dependencies`

Ad-hoc commands can be run without creating a configuration through **Run Anything** (double-⌃, or double-Ctrl, then
type `elide …`), which offers the same completions and runs in the linked project's directory.

### Gutter actions

Run and debug icons appear in the gutter for:

- `elide.pkl` manifests: the `jvm.main` property, each `entrypoint` element, and each `scripts` key (requires the Pkl
  plugin).
- Kotlin and Java `main` functions whose class is an entrypoint of a linked Elide project.
- JUnit test classes and methods in a linked Elide project.

A build icon appears next to each key of the manifest's `artifacts` mapping — JARs, native images, container images
and static sites alike — and runs `elide build <artifact>` for it. It offers no debug or coverage action: assembling
an artifact starts no debuggable process and writes no coverage report.

### Build progress

Every Elide run reports its steps as a tree, the way other build systems do: one node per build step the CLI ran —
dependency resolution, each compilation, each artifact, the entrypoint itself — with the CLI's own duration for it,
the reason a step it skipped gave (*Up to date*, *From cache*, *No sources*), and compiler diagnostics nested under
the step that produced them, navigable to the file, line and column they name.

A diagnostic reads as the message its tool gave, with the file and line it named beside it (`Hello.kt:8`).
Selecting it shows, in the console next to the tree, the block the CLI rendered for it: the position, relative to
the project root whether the tool printed it that way or absolute, the message in the colour of its severity, and
the source excerpt with its frame dimmed and the line it points at picked out. Selecting the step instead shows
every diagnostic its tools reported, in the order they arrived.

Every location an Elide run prints is a link to the file and line it names — the one above a diagnostic, and the
ones in the run's own log, whichever form the tool that reported it used (a path, or a `file://` URI). Text that
only reads like a path is left alone: a location becomes a link when it names a file the project has.

An `elide build` run, including the ones started from an artifact's gutter icon or from the tool window's task list,
reports into the **Build** tool window; `elide run` and the other commands keep the Run window, where the same tree
sits beside the console.

The console beside the tree keeps the whole log, drawn as ordinary text: the CLI writes its account of the build to
standard error, which a console would otherwise show entirely in red. Only what the program under `elide run` writes
to standard error is still shown as error output.

> The tree hides steps that succeeded until **Show successful steps** (the eye icon in the window's toolbar) is
> turned on — an IDE-wide setting shared with the other build systems. Failures, warnings and running steps are
> always shown.

### Test results

An `elide test` run configuration shows its results in the IDE's test runner instead of plain console output: a tree of
suites and tests with pass, fail and skip states, per-test timings and captured output, filtering, sorting and
jump-to-source for JVM tests reported under their class and method names (a method renamed by `@DisplayName` carries no
source location and is not navigable).

The test invocation's build output goes to the **Build** tool window rather than into the test tree.

> The tree is fed by the CLI's TAP reporter: the plugin runs `elide test --reporter=tap`, manually requesting
> `--reporter=console` or `--reporter=junit` keeps that reporter's own console output and no test tree.

### Coverage

**Run with Coverage** on an `elide test` configuration adds `--coverage` to the run and loads the reports the CLI
writes: line highlighting in the editor, per-file and per-directory percentages in the project view, and an entry in
**Run → Show Coverage Data**.

One suite covers every language of the run. Kotlin and Java coverage is read from the JaCoCo execution data the run
leaves under `.dev/artifacts/coverage`, resolved against the classes in `.dev/jvm/classes` and the project's source
roots; JavaScript, TypeScript and the other guest languages come from the LCOV reports under `.dev/reports/coverage`.
The two are merged into a single LCOV report, kept with the IDE's own coverage data rather than in the project.

Coverage collected without the IDE is attached the same way: an `elide test --coverage` run from a terminal, the
IDE's own included, or from a run configuration whose command line already carries the flag, shows up within a few
seconds of the run finishing, as *Elide coverage (project directory)*. The reports are looked for rather than
listened for: they sit outside every content root, where the IDE's virtual file system does not follow them.

Each run replaces the coverage of the previous one of the same configuration, or of the same project for a run
started elsewhere; the IDE is not asked whether to merge the two, since an Elide report always describes a whole run.

> Only `elide test` writes coverage files. `--coverage` on other commands prints the CLI's own summary table and
> leaves nothing behind, so those runs offer no coverage action.

## Debugging

Run configurations offered by the plugin can be debugged using Elide's own JDWP support. The IDE will launch the Elide
CLI with the `--debugger` option and connect to the agent automatically.

Both entrypoint and test configurations support the IDE's **Debug** action, so breakpoints can be set in a JUnit test
and hit through the gutter icon's **Debug** entry. A debugged test run still reports into the test tree. Debugging
`elide test` requires Elide 1.5.3 or newer.

> Only JVM code speaks JDWP. For guest languages the same CLI flag activates the Chrome DevTools or Debug Adapter
> protocol instead, which the IDE's Java debugger cannot attach to, so those configurations offer no **Debug** action.

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
Pkl schema at `https://pkl.elide.dev/<elide version>/` by [`brine`](https://elide.dev), together with the bundled copy
of that schema in `src/main/pkl`, packaged into the plugin jar under `/elide/pkl/`. Both are committed, and
regeneration is a manual step, so CI never needs `brine`:

```bash
tools/codegen.sh                 # or: make codegen -- refetches the version recorded in schema.json
tools/codegen.sh --version 1.5.3 # move the model onto a newer Elide release
```

## Release workflow

Releases are driven by [release-please](https://github.com/googleapis/release-please), configured in
`release-please-config.json`:

1. Run the **Release** workflow from the Actions tab (`workflow_dispatch`). It opens (or, if one is already open,
   refreshes) a release pull request that bumps `.version` and adds the `CHANGELOG.md` section for the next version.
2. Edit that changelog section in the pull request if the generated entries need polish — it becomes the plugin's
   change notes.
3. Merging the release pull request tags `v<version>`, creates the GitHub Release, and publishes the plugin to the
   JetBrains Marketplace and the Elide plugin repository, with the distribution ZIP attached to the release.

The next version follows the merged commits: `fix:` bumps the patch, `feat:` the minor, and `feat!:` (or a
`BREAKING CHANGE:` footer) the major.

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
