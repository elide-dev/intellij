# Changelog

All notable changes to the Elide plugin for JetBrains IDEs are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
- **Debugging for `elide test`.** Test run configurations now support the IDE's **Debug** action.

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
