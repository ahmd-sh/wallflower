#!/bin/sh
# Builds wallflower for each computer it runs on, with checksums and the
# installer, into dist/release; with -publish, makes a GitHub release of
# them, tagged with the version given.
set -eu
cd "$(dirname "$0")/.."

publish=
if [ "${1:-}" = -publish ]; then
	publish=1
	shift
fi
version=${1:?usage: scripts/release.sh [-publish] vX.Y.Z}

out=dist/release
rm -rf "$out"
mkdir -p "$out"
for target in darwin/arm64 darwin/amd64 linux/amd64 linux/arm64; do
	os=${target%/*}
	arch=${target#*/}
	CGO_ENABLED=0 GOOS=$os GOARCH=$arch go build -trimpath -ldflags="-s -w" -o "$out/wallflower-$os-$arch" ./cmd/wallflower
done
cp install.sh "$out/"
(cd "$out" && shasum -a 256 wallflower-* install.sh >checksums.txt)
ls -l "$out"

if [ -n "$publish" ]; then
	gh release create "$version" --repo "${WALLFLOWER_REPO:-ahmd-sh/wallflower}" --title "$version" --notes "" "$out"/*
fi
