#!/usr/bin/env bash

#
# Copyright (c) 2024-2025 Elide Technologies, Inc.
#
# Licensed under the MIT license (the "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   https://opensource.org/license/mit/
#
# Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
# an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations under the License.
#

#
# Verifies that the committed manifest schema mirror is current.
#
# `tools/codegen.sh` needs `brine` to regenerate the Kotlin model, which is not available in CI. Its *input* --
# Elide's published Pkl schema -- can be checked without it: this script re-fetches the schema index and every module
# it lists, and diffs them against `src/main/pkl` and the provenance copy of the index stored next to the generated
# model. A difference means the published schema moved and `tools/codegen.sh` has to be re-run (and its output
# committed), which is exactly the drift the model must not silently accumulate.
#
# Usage: tools/verify-schema.sh [--schema <base-url>]
#

set -euo pipefail

SCHEMA_BASE="${ELIDE_PKL_SCHEMA:-https://pkl.elide.dev/v2/}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --schema) SCHEMA_BASE="$2"; shift 2 ;;
    --schema=*) SCHEMA_BASE="${1#*=}"; shift ;;
    -h|--help) sed -n '17,27p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "error: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

SCHEMA_BASE="${SCHEMA_BASE%/}/"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKL_DIR="$ROOT/src/main/pkl"
SCHEMA_INDEX="$ROOT/src/main/kotlin/dev/elide/tooling/manifest/schema.json"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

echo "Fetching manifest schema index from $SCHEMA_BASE..."
curl -fsSL "$SCHEMA_BASE" -o "$STAGE/schema.json"

# Module names are the only bare `*.pkl` keys in the index; see tools/codegen.sh.
MODULES="$(grep -o '"[A-Za-z][A-Za-z0-9_]*\.pkl"' "$STAGE/schema.json" | tr -d '"' | sort -u)"
if [[ -z "$MODULES" ]]; then
  echo "error: no modules listed in the schema index at $SCHEMA_BASE" >&2
  exit 1
fi

mkdir -p "$STAGE/pkl"
for module in $MODULES; do
  curl -fsSL "$SCHEMA_BASE$module" -o "$STAGE/pkl/$module"
done

status=0

if ! diff -ru "$PKL_DIR" "$STAGE/pkl"; then
  echo "error: the bundled Pkl schema in src/main/pkl differs from $SCHEMA_BASE" >&2
  status=1
fi

if ! diff -u "$SCHEMA_INDEX" "$STAGE/schema.json"; then
  echo "error: the schema index committed beside the generated model is out of date" >&2
  status=1
fi

if [[ $status -ne 0 ]]; then
  echo "       run tools/codegen.sh (requires brine) and commit the regenerated model" >&2
  exit $status
fi

echo "Manifest schema mirror is current."
