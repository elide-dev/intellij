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

### Changed

- The plugin is distributed under the MIT license, with sources and issue tracker at
  [elide-dev/intellij](https://github.com/elide-dev/intellij).
- Change notes shown in the plugin manager are generated from this changelog.

### Fixed

- The Elide tool window and project icons follow the IDE theme; previously they were drawn in a near-white stroke and
  were effectively invisible under light themes.
- The plugin logo declares the 40x40 size expected by the plugin manager and the JetBrains Marketplace.

## [0.7.0] - 2026-09-04

### Added

- Debugging for JVM entrypoint run configurations, over the Elide CLI's JDWP server (`elide run --debugger`).
- Run configuration producer and gutter actions for JUnit test classes and methods in linked Elide projects.
- Command-line completion matching the Elide 1.5 CLI: commands, positional arguments, and global and per-command
  options with their short forms and enumerated values.
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
