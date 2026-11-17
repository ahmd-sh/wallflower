#!/bin/sh
# Gets wallflower for this computer, once the release key's signature on
# the release checks out and the download matches it, then runs its setup:
#   curl -fsSL https://ahmd.sh/wallflower/install.sh | sh
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
command -v ssh-keygen >/dev/null || { echo "wallflower's installer checks the download with ssh-keygen: install OpenSSH first" >&2; exit 1; }

dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
url=${WALLFLOWER_URL:-https://github.com/ahmd-sh/wallflower/releases/latest/download}
get() {
	case $url in
	https://*) curl -fsSL --proto '=https' --proto-redir '=https' "$url/$1" -o "$dir/$1" ;;
	*) curl -fsSL "$url/$1" -o "$dir/$1" ;;
	esac
}

echo "Getting wallflower for ${os} ${arch}…"
get release.json
get release.json.sshsig
cat >"$dir/signers" <<'EOF'
wallflower namespaces="wallflower-release" ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBMWsnhkeZBKFPcZtfPWEZLZAkXZC+iFQk9HWDSrb+Vq
wallflower namespaces="wallflower-release" ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMfdwCSPOdzvlKy0Sg3q1Bq8THr51qI+A1bhmVi00tYQ
EOF
if ! ssh-keygen -Y verify -f "$dir/signers" -I wallflower -n wallflower-release -s "$dir/release.json.sshsig" <"$dir/release.json" >/dev/null 2>&1; then
	echo "the release didn't check out against wallflower's release key (or this ssh-keygen is older than OpenSSH 8.1): nothing installed" >&2
	exit 1
fi
want=$(sed -n "s/^ *\"wallflower-$os-$arch\": \"\([0-9a-f]\{64\}\)\",\{0,1\}\$/\1/p" "$dir/release.json")
get "wallflower-$os-$arch"
if command -v sha256sum >/dev/null; then
	got=$(sha256sum "$dir/wallflower-$os-$arch" | cut -d' ' -f1)
else
	got=$(shasum -a 256 "$dir/wallflower-$os-$arch" | cut -d' ' -f1)
fi
if [ -z "$want" ] || [ "$want" != "$got" ]; then
	echo "the download doesn't match the release: nothing installed" >&2
	exit 1
fi
chmod +x "$dir/wallflower-$os-$arch"
"$dir/wallflower-$os-$arch" setup "$@" </dev/tty
