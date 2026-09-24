# Changelog

All notable changes to the Elide plugin for JetBrains IDEs are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.9.1](https://github.com/elide-dev/intellij/compare/v0.9.0...v0.9.1) (2026-09-24)


### Fixed

* **ci:** refresh the apt index before installing xmllint ([acc920a](https://github.com/elide-dev/intellij/commit/acc920a23818cf28a6d49c0626072bcb9d1b5ead))
* **release:** read published metadata from the packaged descriptor ([97aca5e](https://github.com/elide-dev/intellij/commit/97aca5e7085a0a68b968ca0e078043b1ac323889))

## [0.9.0](https://github.com/elide-dev/intellij/compare/v0.8.0...v0.9.0) (2026-09-24)


### Added

* debugger support for test configurations ([cfda057](https://github.com/elide-dev/intellij/commit/cfda0573eccd41bbd65f228a0415904bf118aa6a))
* multi project workspaces ([#18](https://github.com/elide-dev/intellij/issues/18)) ([f526415](https://github.com/elide-dev/intellij/commit/f526415c204f6d753d7371711c41dea593a65abc))
* running and debugging native image binaries ([#17](https://github.com/elide-dev/intellij/issues/17)) ([1e76874](https://github.com/elide-dev/intellij/commit/1e768742ce916312d24e1876c893faf491c58d3a))


### Fixed

* disable schema drift checks and automate releases ([#12](https://github.com/elide-dev/intellij/issues/12)) ([fd43ace](https://github.com/elide-dev/intellij/commit/fd43ace105341fd34b0be07df082e5ea3b662803))

## [0.8.0] - 2026-09-11

### Added

- **New Project wizard for Elide projects.** Uses the templates provided by the Elide CLI.
- **Test results for `elide test`.** Test runs now report into the IDE's test runner instead of the console.
- **Coverage for `elide test`.** *Run with Coverage* on an Elide test configuration collects coverage and shows it in
  the editor.
- **Build gutter icons for manifest artifacts.** Every key of an `elide.pkl` `artifacts` mapping (JARs, native images,
  container images, etc.) now shows a gutter icon that runs the corresponding build task (requires the Pkl plugin).
- **Completion for project tasks.** After `build` on an Elide command line, in a run configuration or in **Run
  Anything**, the tasks of the linked project will be offered for completion.
- **Build tasks in the Elide tool window.** The tool window lists the project's build targets under **Tasks**.
- **Structured build progress.** Elide builds now report their steps as a tree instead in the **Build** tool window.

### Changed

- The plugin now requires IntelliJ IDEA 2025.3 or newer.

### Fixed

- Opening a project that contains an `elide.pkl` manifest links and syncs it again.
- The Elide tool window and project icons correctly follow the IDE theme.
- Gutter run actions for JUnit tests no longer log "Slow operations are prohibited on EDT" errors.
- Running or debugging a Java `main` method from the editor gutter now uses the Elide run configuration for the
  entrypoint declared in the manifest instead of the IDE's default JVM application configuration.
- `elide build` command lines complete the `--` separator.

## [0.7.0] - 2026-09-04

### Added

- Debugging for JVM entrypoint run configurations, over the Elide CLI's JDWP server (`elide run --debugger`).
- Run configuration producer and gutter actions for JUnit test classes and methods in linked Elide projects.
- Command-line completion matching the Elide CLI: commands, positional arguments, and global and per-command options.

### Fixed

- Project sync links every manifest root in a project and tracks the lockfile for staleness.
- Class-name completion in `elide.pkl` filters by qualified name prefix.
- The Pkl-dependent run configuration producer is registered only when the Pkl plugin is present.

## [0.6.1] - 2026-08-31

### Changed

- Raised the maximum supported IntelliJ platform build.

## [0.6.0] - 2026-05-04

### Added

- Release automation: version bump, tagging, and publication of the plugin distribution.

## [0.5.3] - 2026-05-02

### Added

- Elide distribution resolution for modern install layouts.

### Fixed

- The CLI binary is resolved at the correct path inside a distribution root.
