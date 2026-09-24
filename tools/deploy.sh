#!/bin/bash
set -euo pipefail

# check that the tools used to read the packaged descriptor are available
for tool in xmllint unzip jq; do
    if ! command -v "$tool" &> /dev/null; then
        echo "Error: $tool is required but not installed"
        exit 1
    fi
done

PLUGINS_URL="${ELIDE_PLUGINS_URL:-${PLUGINS_URL}}"
PLUGIN_VERSION="$(cat .version)"
PLUGIN_FILE="build/distributions/elide-intellij-${PLUGIN_VERSION}.zip"

if [[ ! -f "$PLUGIN_FILE" ]]; then
    echo "Error: plugin archive not found: $PLUGIN_FILE"
    exit 1
fi

# the descriptor under `src` is a template: the build range is patched in by the Gradle plugin at build time, so only
# the copy packaged inside the plugin jar describes the archive actually being uploaded
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

PLUGIN_JAR="elide-intellij/lib/elide-intellij-${PLUGIN_VERSION}.jar"
unzip -q -o "$PLUGIN_FILE" "$PLUGIN_JAR" -d "$WORK_DIR"
unzip -q -o "$WORK_DIR/$PLUGIN_JAR" "META-INF/plugin.xml" -d "$WORK_DIR"
XML_FILE="$WORK_DIR/META-INF/plugin.xml"

# prepare metadata
PLUGIN_ID=$(xmllint --xpath "string(//idea-plugin/id)" "$XML_FILE" 2>/dev/null)
PLUGIN_NAME=$(xmllint --xpath "string(//idea-plugin/name)" "$XML_FILE" 2>/dev/null)
PLUGIN_DESCRIPTION=$(xmllint --xpath "string(//idea-plugin/description)" "$XML_FILE" 2>/dev/null)
PLUGIN_VENDOR=$(xmllint --xpath "string(//idea-plugin/vendor)" "$XML_FILE" 2>/dev/null)
PLUGIN_VENDOR_URL=$(xmllint --xpath "string(//idea-plugin/vendor/@url)" "$XML_FILE" 2>/dev/null)
SINCE_BUILD=$(xmllint --xpath "string(//idea-plugin/idea-version/@since-build)" "$XML_FILE" 2>/dev/null)
UNTIL_BUILD=$(xmllint --xpath "string(//idea-plugin/idea-version/@until-build)" "$XML_FILE" 2>/dev/null)

# an empty build range silently drops compatibility gating from the published metadata, which is the failure this
# script is meant to avoid; treat it as fatal rather than uploading a plugin the repository cannot constrain
if [[ -z "$SINCE_BUILD" ]]; then
    echo "Error: no since-build found in the packaged descriptor: $XML_FILE"
    exit 1
fi

# Print the extracted values
echo "Uploading plugin:"
echo "ID=$PLUGIN_ID"
echo "Name=$PLUGIN_NAME"
echo "Version=$PLUGIN_VERSION"
echo "Description=$PLUGIN_DESCRIPTION"
echo "Vendor=${PLUGIN_VENDOR:-n/a}"
echo "Vendor URL=${PLUGIN_VENDOR_URL:-n/a}"
echo "Since build=${SINCE_BUILD:-n/a}"
echo "Until build=${UNTIL_BUILD:-n/a}"

url_encode() {
    jq -rn --arg str "$1" '$str | @uri'
}

ENCODED_ID=$(url_encode "$PLUGIN_ID")
ENCODED_VERSION=$(url_encode "$PLUGIN_VERSION")

echo "Getting presigned upload URL..."
UPLOAD_URL=$(curl --fail -s \
  -H "x-api-key: ${ELIDE_PLUGINS_KEY}" \
  "${PLUGINS_URL}/intellij/files/presign?id=${ENCODED_ID}&version=${ENCODED_VERSION}")

echo "Uploading plugin archive..."
curl -v --fail -# \
  -X PUT \
  -H "Content-Type: application/octet-stream" \
  --data-binary "@$PLUGIN_FILE" \
  "$UPLOAD_URL"
echo "Archive uploaded"

echo "Updating plugin metadata..."
METADATA_JSON="$(jq -n \
  --arg id "$PLUGIN_ID" \
  --arg name "$PLUGIN_NAME" \
  --arg description "$PLUGIN_DESCRIPTION" \
  --arg version "$PLUGIN_VERSION" \
  --arg vendor "$PLUGIN_VENDOR" \
  --arg vendorUrl "$PLUGIN_VENDOR_URL" \
  --arg sinceBuild "$SINCE_BUILD" \
  --arg untilBuild "$UNTIL_BUILD" \
  '{
    pluginId: $id,
    name: $name,
    description: $description,
    version: $version
  } +
  (if $vendor != "" then {vendorName: $vendor} else {} end) +
  (if $vendorUrl != "" then {vendorUrl: $vendorUrl} else {} end) +
  (if $sinceBuild != "" then {sinceBuild: $sinceBuild} else {} end) +
  (if $untilBuild != "" then {untilBuild: $untilBuild} else {} end)'
)"

curl --fail -s \
  -H "x-api-key: ${ELIDE_PLUGINS_KEY}" \
  -H "Content-Type: application/json" \
  "${PLUGINS_URL}/intellij/plugins?id=${ENCODED_ID}" \
  -d "$METADATA_JSON"
echo "Plugin deployed"
