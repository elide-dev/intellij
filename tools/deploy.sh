#!/bin/bash
set -euo pipefail

# check if xmllint is available
if ! command -v xmllint &> /dev/null; then
    echo "Error: xmllint is required but not installed"
    exit 1
fi

PLUGINS_URL="${ELIDE_PLUGINS_URL:-${PLUGINS_URL}}"
PLUGIN_VERSION="$(cat .version)"
XML_FILE="src/main/resources/META-INF/plugin.xml"
PLUGIN_FILE="build/distributions/elide-intellij-${PLUGIN_VERSION}.zip"

if [[ ! -f "$PLUGIN_FILE" ]]; then
    echo "Error: plugin archive not found: $PLUGIN_FILE"
    exit 1
fi

# prepare metadata
PLUGIN_ID=$(xmllint --xpath "string(//idea-plugin/id)" "$XML_FILE" 2>/dev/null)
PLUGIN_NAME=$(xmllint --xpath "string(//idea-plugin/name)" "$XML_FILE" 2>/dev/null)
PLUGIN_DESCRIPTION=$(xmllint --xpath "string(//idea-plugin/description)" "$XML_FILE" 2>/dev/null)
PLUGIN_VENDOR=$(xmllint --xpath "string(//idea-plugin/vendor)" "$XML_FILE" 2>/dev/null)
PLUGIN_VENDOR_URL=$(xmllint --xpath "string(//idea-plugin/vendor/@url)" "$XML_FILE" 2>/dev/null)
SINCE_BUILD=$(xmllint --xpath "string(//idea-plugin/idea-version/@since-build)" "$XML_FILE" 2>/dev/null)
UNTIL_BUILD=$(xmllint --xpath "string(//idea-plugin/idea-version/@until-build)" "$XML_FILE" 2>/dev/null)

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

echo "Uploading plugin archive..."
curl -v --fail -# \
  -X POST \
  -H "Content-Type: application/octet-stream" \
  -H "x-api-key: ${ELIDE_PLUGINS_KEY}" \
  --data-binary "@$PLUGIN_FILE" \
  "${PLUGINS_URL}/intellij/files?id=${ENCODED_ID}&version=${ENCODED_VERSION}"
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
