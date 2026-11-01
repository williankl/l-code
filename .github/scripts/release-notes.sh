#!/usr/bin/env bash
# Prints the top of a release's notes: a download table with one row per platform, then how to
# install. release.yml puts it in the draft release and in the run's summary.
#
# Usage: release-notes.sh <version> <folder with the files> [<download URL prefix>]
# Without a URL prefix (a dry run) the file names aren't links.
# HOMEBREW=true and SNAP_STORE=true add the Homebrew and Snap Store install lines.
set -euo pipefail

if [ $# -lt 2 ]; then
  echo "usage: $0 <version> <folder> [<download URL prefix>]" >&2
  exit 64
fi

version=$1
folder=$2
url=${3:-}

row() {
  local platform=$1 file=$2 bytes name
  [ -f "$folder/$file" ] || return 0
  bytes=$(wc -c < "$folder/$file" | tr -d ' ')
  if [ -n "$url" ]; then name="[$file]($url/$file)"; else name="\`$file\`"; fi
  printf '| %s | %s | %s MB |\n' "$platform" "$name" "$(awk "BEGIN { printf \"%.0f\", $bytes / 1000000 }")"
}

cat <<EOF
## Download

| Platform | File | Size |
|---|---|--:|
EOF
row 'macOS · Apple Silicon' "Lemonade-Code-$version-macos-arm64.dmg"
row 'macOS · Intel' "Lemonade-Code-$version-macos-x64.dmg"
row 'Windows · x64' "Lemonade-Code-$version-windows-x64.msi"
row 'Ubuntu, Debian · x64' "lemonade-code_${version}_amd64.deb"
row 'Linux, any distribution · x64' "lemonade-code-$version-linux-x64.tar.gz"
row 'Linux snap · x64' "lemonade-code_${version}_amd64.snap"

echo
echo '## Install'
echo
if [ "${HOMEBREW:-}" = true ]; then
  echo '- **macOS:** `brew install --cask williankl/tap/lemonade-code`, or open the DMG for your Mac and drag Lemonade Code to Applications.'
else
  echo '- **macOS:** open the DMG for your Mac and drag Lemonade Code to Applications.'
fi
echo '- **Windows:** run the MSI. It installs for your user, no administrator needed.'
if [ "${SNAP_STORE:-}" = true ]; then
  echo '- **Ubuntu:** `sudo snap install lemonade-code --classic`, or `sudo apt install ./lemonade-code_'"$version"'_amd64.deb`.'
else
  echo '- **Ubuntu, Debian:** `sudo apt install ./lemonade-code_'"$version"'_amd64.deb`.'
fi
echo '- **Any Linux:** unpack the tar.gz anywhere you can write to and run `lemonade-code/bin/Lemonade Code`.'
echo
echo 'Installed from the DMG, MSI, .deb or tar.gz, Lemonade Code updates itself: an Update button shows up next to Settings when a newer version is out. Homebrew and the Snap Store update it their own way.'
echo
echo '`SHA256SUMS` lists every file'"'"'s checksum.'
