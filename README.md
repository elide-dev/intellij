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

## Manifest schema codegen

The Elide project manifest model in `src/main/kotlin/dev/elide/tooling/manifest` is **generated** from Elide's
published Pkl schema at <https://pkl.elide.dev/v2/> by [`brine`](https://elide.dev), together with the bundled copy of
that schema in `src/main/pkl` (served to the Pkl language plugin for `elide:` imports). Both are committed, and
regeneration is a manual step, so CI never needs `brine`:

```bash
tools/codegen.sh          # or: make codegen
```

Only the Kotlin model and its kotlinx.serialization support are generated; the Pkl decoders are not, because the
plugin never evaluates Pkl. It runs `elide manifest` and decodes that command's JSON output through
`ElideManifests.parse`, which is guaranteed to match as long as the model is generated from the schema above -- the
CLI serializes the same generated model. `ElideManifestsTest` pins that contract against verbatim `elide manifest`
output.

Never hand-edit the generated sources: re-run codegen after an Elide release that changes the manifest format, and
commit the result.

## Release workflow

Releases are triggered by pushing a version tag. Steps:

1. Update `.version` with the new version (e.g. `0.6.0`)
2. Commit: `chore: bump version to 0.6.0`
3. Tag and push:
   ```bash
   git tag v0.6.0 && git push origin v0.6.0
   ```
4. The `Release` workflow builds the plugin, publishes it to the Elide plugin repository, and creates a GitHub Release with auto-generated notes and the plugin ZIP attached.

**Commit convention:** commits should follow [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `chore:`, etc.). A commitlint check enforces this on every PR.
