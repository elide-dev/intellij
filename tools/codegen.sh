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
# Regenerates the Elide project manifest bindings from Elide's published Pkl schema.
#
# This is a deliberately manual step: `brine` is not available in CI, so both the Pkl schema mirror
# (`src/main/pkl`, bundled for the Pkl IDE plugin) and the generated Kotlin model
# (`src/main/kotlin/dev/elide/tooling/manifest`) are committed to the repository. Run this after an
# Elide release that changes the manifest format, and commit the result.
#
# Only the model and its kotlinx.serialization accessories are generated; the Pkl decoders are not,
# because the plugin never evaluates Pkl itself -- it shells out to `elide manifest` and decodes the
# JSON that command emits. That output is produced by this exact schema, so the two agree by
# construction.
#
# Usage: tools/codegen.sh [--schema <base-url>]
#

set -euo pipefail

SCHEMA_BASE="${ELIDE_PKL_SCHEMA:-https://pkl.elide.dev/v2/}"
PACKAGE_NAME="dev.elide.tooling.manifest"
MODULE_PREFIX="elide"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --schema) SCHEMA_BASE="$2"; shift 2 ;;
    --schema=*) SCHEMA_BASE="${1#*=}"; shift ;;
    -h|--help) sed -n '17,29p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "error: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

SCHEMA_BASE="${SCHEMA_BASE%/}/"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKL_DIR="$ROOT/src/main/pkl"
KOTLIN_ROOT="$ROOT/src/main/kotlin"
GENERATED_DIR="$KOTLIN_ROOT/${PACKAGE_NAME//.//}"

if ! command -v brine >/dev/null 2>&1; then
  echo "error: 'brine' was not found on PATH." >&2
  echo "       Manifest codegen is a manual step; install brine and re-run this script." >&2
  exit 1
fi

if command -v shasum >/dev/null 2>&1; then
  sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }
elif command -v sha256sum >/dev/null 2>&1; then
  sha256() { sha256sum "$1" | cut -d' ' -f1; }
else
  echo "error: neither 'shasum' nor 'sha256sum' is available; cannot verify the schema." >&2
  exit 1
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

echo "Fetching manifest schema index from $SCHEMA_BASE..."
curl -fsSL "$SCHEMA_BASE" -o "$STAGE/schema.json"

# The index lists every module of the schema, including the ones no other module imports, so it --
# not the import graph -- decides what is generated. Module names are the only bare `*.pkl` keys in
# it; `entrypoint` is path-prefixed and therefore excluded.
MODULES="$(grep -o '"[A-Za-z][A-Za-z0-9_]*\.pkl"' "$STAGE/schema.json" | tr -d '"' | sort -u)"
if [[ -z "$MODULES" ]]; then
  echo "error: no modules listed in the schema index at $SCHEMA_BASE" >&2
  exit 1
fi

mkdir -p "$STAGE/pkl"
for module in $MODULES; do
  curl -fsSL "$SCHEMA_BASE$module" -o "$STAGE/pkl/$module"

  # Each module is pinned by the index's digest: a truncated or substituted download must not reach
  # the generator, since the model's compatibility with `elide manifest` rests on this exact schema.
  digest="$(sha256 "$STAGE/pkl/$module")"
  if ! grep -q "\"$digest\"" "$STAGE/schema.json"; then
    echo "error: $module does not match its digest in the schema index ($digest)" >&2
    exit 1
  fi

  echo "  verified $module"
done

echo "Refreshing bundled Pkl schema in src/main/pkl..."
rm -rf "$PKL_DIR"
mkdir -p "$PKL_DIR"
cp "$STAGE/pkl/"*.pkl "$PKL_DIR/"

echo "Generating Kotlin manifest model into ${GENERATED_DIR#"$ROOT/"}..."
rm -rf "$GENERATED_DIR"
brine \
  --src "$PKL_DIR" \
  --out "$KOTLIN_ROOT" \
  --package-name "$PACKAGE_NAME" \
  --module-prefix "$MODULE_PREFIX" \
  --serialization \
  --no-decoders

# Provenance for the committed sources: which schema revision they were generated from.
cp "$STAGE/schema.json" "$GENERATED_DIR/schema.json"

echo "Manifest codegen complete."
