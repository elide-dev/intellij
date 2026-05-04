#!/bin/bash
set -euo pipefail

LAST_TAG=$(git describe --tags --abbrev=0 2>/dev/null || echo "")
CURRENT_VERSION=$(cat .version)
PUSH=true

IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION"

# parse args: optional [major|minor|patch] and optional --no-push
for arg in "$@"; do
  case "$arg" in
    major|minor|patch) BUMP_OVERRIDE="$arg" ;;
    --no-push) PUSH=false ;;
    *) echo "Usage: $0 [major|minor|patch] [--no-push]"; exit 1 ;;
  esac
done

# collect commits since last tag
if [[ -n "$LAST_TAG" ]]; then
  COMMITS=$(git log "${LAST_TAG}..HEAD" --pretty=format:"%s" 2>/dev/null || echo "")
else
  COMMITS=$(git log --pretty=format:"%s")
fi

if [[ -z "$COMMITS" ]]; then
  echo "No commits since ${LAST_TAG:-beginning} — nothing to release."
  exit 0
fi

# determine bump type from conventional commits
BUMP="patch"
while IFS= read -r msg; do
  if [[ "$msg" =~ ^[a-z]+(\(.+\))?!: ]] || [[ "$msg" == *"BREAKING CHANGE"* ]]; then
    BUMP="major"
    break
  elif [[ "$msg" =~ ^feat(\(.+\))?: ]] && [[ "$BUMP" != "major" ]]; then
    BUMP="minor"
  fi
done <<< "$COMMITS"

[[ -n "${BUMP_OVERRIDE:-}" ]] && BUMP="$BUMP_OVERRIDE"

case "$BUMP" in
  major) NEW_VERSION="$((MAJOR + 1)).0.0" ;;
  minor) NEW_VERSION="${MAJOR}.$((MINOR + 1)).0" ;;
  patch) NEW_VERSION="${MAJOR}.${MINOR}.$((PATCH + 1))" ;;
esac

echo "Commits since ${LAST_TAG:-the beginning}:"
while IFS= read -r msg; do echo "  $msg"; done <<< "$COMMITS"
echo ""
echo "Detected bump: $BUMP  →  $CURRENT_VERSION → $NEW_VERSION"
echo ""
echo "Will:"
echo "  1. Write $NEW_VERSION to .version"
echo "  2. Commit: chore: bump version to $NEW_VERSION"
echo "  3. Tag: v$NEW_VERSION"
if [[ "$PUSH" == true ]]; then
echo "  4. Push commit and tag to origin"
fi
echo ""
read -rp "Proceed? [y/N] " reply
[[ "$reply" =~ ^[Yy]$ ]] || { echo "Aborted."; exit 0; }

echo "$NEW_VERSION" > .version
git add .version
git commit -m "chore: release v$NEW_VERSION" -m "[skip ci]"
git tag "v$NEW_VERSION"

if [[ "$PUSH" == true ]]; then
  git push origin HEAD "v$NEW_VERSION"
  echo ""
  echo "Released v$NEW_VERSION"
else
  echo ""
  echo "Bumped to v$NEW_VERSION — run 'git push origin HEAD v$NEW_VERSION' to release."
fi
