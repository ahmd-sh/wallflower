#!/usr/bin/env bash
# Builds an unsigned wallflower.ipa into dist/, for sideloading: AltStore,
# SideStore or Sideloadly sign it with the installer's own Apple ID. Needs
# the libghostty xcframework (scripts/build-libghostty.sh ios) first.
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
build=${TMPDIR:-/tmp}/wallflower-ipa
rm -rf "$build"
xcodebuild -project "$root/ios/Wallflower.xcodeproj" -scheme Wallflower -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath "$build/dd" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY= build -quiet
app=$(find "$build/dd/Build/Products/Release-iphoneos" -maxdepth 1 -name 'Wallflower.app' | head -1)
mkdir -p "$build/Payload" "$root/dist"
cp -R "$app" "$build/Payload/"
(cd "$build" && rm -f wallflower.ipa && zip -qry wallflower.ipa Payload)
cp "$build/wallflower.ipa" "$root/dist/wallflower.ipa"
echo "$root/dist/wallflower.ipa"
