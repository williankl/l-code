#!/usr/bin/env bash
# Prints the Homebrew cask for a release. publish.yml writes it to the tap as
# Casks/lemonade-code.rb.
#
# The Mac builds aren't notarized, so Gatekeeper would block the copy Homebrew installs: Homebrew
# quarantines every cask download. The cask removes that flag from the app (postflight_steps) and
# says so (caveats). Drop both once the DMGs are signed and notarized; only then can the cask be
# submitted to Homebrew's main repository.
#
# Usage: write-cask.sh <version> <owner/repo> <arm64 dmg> <x64 dmg>
set -euo pipefail

if [ $# -ne 4 ]; then
  echo "usage: $0 <version> <owner/repo> <arm64 dmg> <x64 dmg>" >&2
  exit 64
fi

version=$1
repo=$2
sha() { shasum -a 256 "$1" | cut -d ' ' -f 1; }
arm=$(sha "$3")
intel=$(sha "$4")

cat <<EOF
cask "lemonade-code" do
  arch arm: "arm64", intel: "x64"

  version "$version"
  sha256 arm:   "$arm",
         intel: "$intel"

  url "https://github.com/$repo/releases/download/v#{version}/Lemonade-Code-#{version}-macos-#{arch}.dmg"
  name "Lemonade Code"
  desc "Terminal IDE for reviewing work"
  homepage "https://github.com/$repo"

  livecheck do
    url :url
    strategy :github_latest
  end

  depends_on :macos

  app "Lemonade Code.app"

  # Not notarized, so Gatekeeper would block the quarantined app.
  postflight_steps do
    run "/usr/bin/xattr",
        args:         ["-dr", "com.apple.quarantine", "{{appdir}}/Lemonade Code.app"],
        must_succeed: false
  end

  zap trash: "~/Library/Application Support/Lemonade Code"

  caveats <<~EOS
    Lemonade Code isn't notarized by Apple. This cask removes macOS's quarantine flag from the
    app so it opens without a Gatekeeper prompt.
  EOS
end
EOF
