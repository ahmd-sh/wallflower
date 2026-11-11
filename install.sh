#!/bin/sh
# Gets wallflower for this computer, then runs its setup:
#   curl -fsSL https://github.com/ahmd-sh/wallflower/releases/latest/download/install.sh | sh
set -eu

case $(uname -s) in
Linux) os=linux ;;
Darwin) os=darwin ;;
*) echo "wallflower runs on Linux and macOS" >&2; exit 1 ;;
esac
case $(uname -m) in
x86_64 | amd64) arch=amd64 ;;
aarch64 | arm64) arch=arm64 ;;
*) echo "there's no wallflower for $(uname -m) yet" >&2; exit 1 ;;
esac

dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
url=${WALLFLOWER_URL:-https://github.com/ahmd-sh/wallflower/releases/latest/download}
echo "Getting wallflower for $os $arch…"
curl -fsSL "$url/wallflower-$os-$arch" -o "$dir/wallflower"
curl -fsSL "$url/checksums.txt" -o "$dir/checksums.txt"
want=$(grep " wallflower-$os-$arch\$" "$dir/checksums.txt" | cut -d' ' -f1)
if command -v sha256sum >/dev/null; then
	got=$(sha256sum "$dir/wallflower" | cut -d' ' -f1)
else
	got=$(shasum -a 256 "$dir/wallflower" | cut -d' ' -f1)
fi
if [ -z "$want" ] || [ "$want" != "$got" ]; then
	echo "the download doesn't match its checksum: try again" >&2
	exit 1
fi
chmod +x "$dir/wallflower"
"$dir/wallflower" setup "$@" </dev/tty
