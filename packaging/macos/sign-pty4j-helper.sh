#!/usr/bin/env bash
# Signs pty4j's macOS spawn helper inside the pty4j jar in Gradle's cache.
#
# Apple's notary service rejects any unsigned program it finds, including inside jars. Compose
# signs the .dylib and .jnilib files in jars, but pty4j's helper has no extension, so Compose
# skips it and notarization fails. Run this after Gradle has downloaded pty4j, before packaging.
#
# Usage: sign-pty4j-helper.sh <signing identity> <keychain>
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "usage: $0 <signing identity> <keychain>" >&2
  exit 64
fi

identity=$1
keychain=$2
case "$identity" in
  "Developer ID Application: "*) ;;
  *) identity="Developer ID Application: $identity" ;;
esac

entry=resources/com/pty4j/native/darwin/pty4j-unix-spawn-helper
cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.jetbrains.pty4j/pty4j"
jars=$(find "$cache" -name 'pty4j-*.jar' ! -name '*-sources.jar' 2>/dev/null || true)
if [ -z "$jars" ]; then
  echo "No pty4j jar under $cache; run a Gradle build first." >&2
  exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
for jar in $jars; do
  rm -rf "${work:?}/resources"
  (cd "$work" && unzip -q "$jar" "$entry")
  codesign --force --timestamp --options runtime --keychain "$keychain" --sign "$identity" "$work/$entry"
  (cd "$work" && zip -q "$jar" "$entry")
  echo "Signed the spawn helper in $jar"
done
