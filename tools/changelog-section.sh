#!/bin/bash
#
# Print the CHANGELOG.md section for a given version, without its heading.
#
# Usage: tools/changelog-section.sh 0.7.0

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <version>" >&2
  exit 1
fi

VERSION="$1"
CHANGELOG="$(dirname "$0")/../CHANGELOG.md"

# the section body, with leading and trailing blank lines dropped (blank runs inside it are preserved)
SECTION=$(awk -v version="$VERSION" '
  $0 ~ "^## \\[" version "\\]" { found = 1; next }
  found && /^## / { exit }
  found && /[^[:space:]]/ { while (blanks-- > 0) print ""; blanks = 0; started = 1; print }
  found && !/[^[:space:]]/ && started { blanks++ }
' "$CHANGELOG")

if [[ -z "$SECTION" ]]; then
  echo "Error: CHANGELOG.md has no entries for version $VERSION" >&2
  exit 1
fi

printf '%s\n' "$SECTION"
