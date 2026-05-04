# Elide IntelliJ Plugin

JetBrains IDE integration for the [Elide](https://elide.dev) runtime.

## Features

- Import and manage Elide projects (Kotlin/JVM)
- Automatic dependency installation on project sync, with sources and documentation
- Run configurations generated from entry points declared in the Elide project manifest
- Gutter icons to run JVM main entry points directly from the editor
- IDE actions for common Elide tasks: `build`, `install`, `run`
- Support for custom Elide distributions
- Optional [Pkl](https://pkl-lang.org) language plugin integration
- Tool window and project-level settings panel

## Installation

The plugin is published to the Elide plugin repository. Add it to your IDE under **Settings → Plugins → Manage Plugin Repositories**:

```
https://plugins.elide.dev/intellij
```

Then search for **Elide** in the marketplace tab.

## Requirements

- IntelliJ IDEA 2025.1 or newer (build 251–261.*)
- Java 21 toolchain
- Elide CLI installed on a standard directory (custom installations can be selected in the IDE settings)

## Building

```bash
./gradlew buildPlugin
```

The plugin ZIP is written to `build/distributions/`.

## Release workflow

Releases are automated via [release-please](https://github.com/googleapis/release-please).

**Commit convention:** all commits must follow [Conventional Commits](https://www.conventionalcommits.org/). A commitlint check enforces this on every PR.

| Commit type | Version bump |
|---|---|
| `fix:` | patch |
| `feat:` | minor |
| `feat!:` / `BREAKING CHANGE:` | major |
| `chore:`, `docs:`, `refactor:` | no release |

**How a release happens:**

1. Merge one or more `feat:` / `fix:` commits into `main`.
2. Once CI passes, release-please opens a release PR that bumps `.version` and updates `CHANGELOG.md`.
3. Review and merge the release PR.
4. Once CI passes on that merge, release-please creates the tag and GitHub Release with auto-generated notes.
5. The publish job builds the plugin, deploys it to the Elide plugin repository, and attaches the ZIP to the GitHub Release.

Releases never happen without a human merging the release PR.
