## Summary

<!-- What changes, and why. Link the issue this closes, if any. -->

## Verification

<!--
How you exercised the change. For IDE behavior, say what you did in `./gradlew runIde`.
For build or CI changes, name the tasks you ran.
-->

- [ ] `./gradlew test`
- [ ] Exercised the changed behavior in a sandboxed IDE (`./gradlew runIde`), or explained why that is not applicable

## Checklist

- [ ] Commits follow [Conventional Commits](https://www.conventionalcommits.org/)
- [ ] User-facing changes are listed under `## [Unreleased]` in `CHANGELOG.md`
- [ ] New or removed experimental/internal platform APIs are reflected in `docs/PLATFORM_APIS.md`
- [ ] Generated sources (`src/main/pkl`, `dev/elide/tooling/manifest`) were regenerated rather than hand-edited
