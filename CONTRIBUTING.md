# Contributing

Thanks for helping improve the Elide plugin for JetBrains IDEs. This document covers the local setup, the checks that
run in CI, and the conventions the repository follows.

## Prerequisites

- JDK 21 (the Gradle build pins a Java 21 toolchain)
- The [Elide CLI](https://docs.elide.dev/installation), for exercising sync, run, and debug against a real project
- No local IntelliJ installation is needed: the Gradle IntelliJ Platform plugin downloads the target IDE

## Common tasks

```bash
./gradlew test                  # unit tests
./gradlew buildPlugin           # plugin ZIP -> build/distributions/
./gradlew runIde                # sandboxed IDE with the plugin installed
./gradlew verifyPlugin          # IntelliJ Plugin Verifier against the supported build range
./gradlew verifyPluginProjectConfiguration   # descriptor and build-config checks
```

`make dist` and `make codegen` wrap the equivalent Gradle and tooling invocations; see the `Makefile`.

## Manual verification

The plugin is an IDE integration, so behavioral changes should be exercised in `runIde`:

1. `./gradlew runIde`
2. Open a directory containing an `elide.pkl` manifest and let the project sync.
3. Exercise the changed surface — sync, an **Elide** run configuration, a gutter run/debug action, or manifest
   completion — and confirm the result in the IDE.

Describe what you exercised in the pull request.

## Repository layout

| Path | Contents |
|---|---|
| `src/main/kotlin/dev/elide/intellij` | Plugin implementation: project import, run configurations, settings, PSI support |
| `src/main/kotlin/dev/elide/tooling/manifest` | **Generated** Kotlin model of the Elide project manifest |
| `src/main/pkl` | Bundled mirror of Elide's published Pkl schema, packaged under `/elide/pkl/` |
| `src/main/resources/META-INF` | Plugin descriptors and logo |
| `tools` | Codegen, schema drift check, and deployment helpers |

`dev/elide/tooling/manifest` and `src/main/pkl` are generated from Elide's published Pkl schema and must not be edited
by hand. Regenerate them with `tools/codegen.sh` (requires `brine`) and commit the result; CI verifies that the bundled
schema still matches the published one.

## Platform API usage

The plugin relies on a small set of experimental, internal and deprecated IntelliJ Platform APIs, inventoried in
[`docs/PLATFORM_APIS.md`](docs/PLATFORM_APIS.md). When you add or remove one, update that document in the same change,
and re-check it with `./gradlew verifyPlugin` whenever the supported build range in `gradle/libs.versions.toml` moves.

## Commits and pull requests

- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`,
  `chore:`, `ci:`, `test:`, …). A commitlint check enforces this on every pull request.
- User-facing changes need no changelog edit: `CHANGELOG.md` is generated from commit subjects by release-please, so
  write the subject as the changelog entry you want to read. Its section for a version becomes the change notes shown
  on the JetBrains Marketplace.
- Keep `./gradlew test` and `./gradlew verifyPluginProjectConfiguration` green; CI runs them along with the IntelliJ
  Plugin Verifier and the manifest schema drift check.
- Code style follows the checked-in `.editorconfig` and the official Kotlin style.

## Releasing

Releases are cut on demand by maintainers:

1. Run the **Release** workflow from the Actions tab. It opens or refreshes a release pull request that bumps
   `.version` and adds the `CHANGELOG.md` section for the next version, derived from the commits since the last tag.
2. Amend that section in the pull request if the generated entries need polish; the merged text is what the
   Marketplace shows. Run the workflow again to fold in commits that landed after the pull request was opened.
3. Merging it tags `v<version>`, creates the GitHub Release, and triggers the publish job, which builds, signs and
   publishes the plugin and attaches the distribution ZIP to the release.

`release-please-config.json` and `.release-please-manifest.json` hold the configuration and the last released version.

## Licensing

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE), and that new source
files carry the same license header as the existing ones.
