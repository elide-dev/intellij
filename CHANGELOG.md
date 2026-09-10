# Changelog

All notable changes to the Elide plugin for JetBrains IDEs are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The section for the version currently in `.version` is used verbatim as the plugin's change notes on the JetBrains
Marketplace, so entries must be user-facing and free of internal jargon.

## [Unreleased]

### Added

- **New Project wizard for Elide projects.** *File > New > Project > Elide* lists the project templates the selected
  Elide distribution ships — the same ones `elide init` offers — asks for their options, and generates and imports the
  project. Requires an Elide release whose CLI supports `elide init --templates --json`.
- **Test results for `elide test`.** Test runs now report into the IDE's test runner instead of the console. The CLI's
  own log goes to the Build window. The tree is fed by the CLI's TAP reporter, which the run is launched with, so it
  requires Elide 1.5.2 or newer; naming `--reporter=console` or `--reporter=junit` on the command line keeps that
  reporter's console output instead.
- **Coverage for `elide test`.** *Run with Coverage* on an Elide test configuration collects coverage and shows it in
  the editor, the project view and the coverage tool window. Kotlin, Java, JavaScript and TypeScript coverage from a
  single run lands in one suite. Reports written by a run the IDE did not start — from a terminal, say — are attached
  as they appear.
- **Build icons for manifest artifacts.** Every key of an `elide.pkl` `artifacts` mapping — JARs, native images,
  container images, static sites — carries a gutter icon that runs `elide build <artifact>`, with a run configuration
  named after the artifact (requires the Pkl plugin).

### Changed

- The plugin is distributed under the MIT license, with sources and issue tracker at
  [elide-dev/intellij](https://github.com/elide-dev/intellij).
- Change notes shown in the plugin manager are generated from this changelog.
- The plugin now requires IntelliJ IDEA 2025.3 or newer. Earlier IDEs keep the last published build that supports them.
- Project linking, sync and build output now use the current IntelliJ external system APIs, replacing calls the
  platform has deprecated or scheduled for removal.
- Gutter run and debug actions for Kotlin `main` functions no longer rely on Kotlin plugin internals.

### Fixed

- Opening a project that contains an `elide.pkl` manifest links and syncs it again. The startup activity failed with an
  internal error before reaching the manifest scan, and Elide tasks were not configured to run inside the IDE process.
- The Elide tool window and project icons follow the IDE theme; previously they were drawn in a near-white stroke and
  were effectively invisible under light themes.
- The plugin logo declares the 40x40 size expected by the plugin manager and the JetBrains Marketplace.
- Gutter run actions for JUnit tests no longer log "Slow operations are prohibited on EDT" errors while the IDE
  updates run actions, and they now work while the IDE is still indexing.
- Running or debugging a Java `main` method from the editor gutter now uses the Elide run configuration for the
  entrypoint declared in the manifest, instead of the IDE's default JVM application configuration. Kotlin entrypoints
  already did.

## [0.7.0] - 2026-09-04

### Added

- Debugging for JVM entrypoint run configurations, over the Elide CLI's JDWP server (`elide run --debugger`).
- Run configuration producer and gutter actions for JUnit test classes and methods in linked Elide projects.
- Command-line completion matching the Elide 1.5 CLI: commands, positional arguments, and global and per-command options
  with their short forms and enumerated values.
- The manifest model is generated from Elide's published Pkl schema, and the schema mirror is drift-checked in CI.

### Fixed

- Project sync links every manifest root in a project and tracks the lockfile for staleness.
- Class-name completion in `elide.pkl` filters by qualified name prefix.
- Task names are passed to the CLI as a single argument vector.
- The Pkl-dependent run configuration producer is registered only when the Pkl plugin is present.

## [0.6.1] - 2026-08-31

### Changed

- Raised the maximum supported IntelliJ platform build.

## [0.6.0] - 2026-05-04

### Added

- Release automation: version bump, tagging, and publication of the plugin distribution.

### Fixed

- Plugin dependency declarations and the Gradle build for recent IntelliJ platform versions.

## [0.5.3] - 2026-05-02

### Added

- Elide distribution resolution for modern install layouts, including platform-specific defaults.

### Fixed

- The CLI binary is resolved at the correct path inside a distribution root.
