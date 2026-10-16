#!/usr/bin/env bash
# Builds libghostty-vt for Android from a pinned Ghostty commit with a pinned
# Zig, into android/terminal/libghostty. Its C API is still changing, so bump
# GHOSTTY_COMMIT deliberately and rebuild the JNI shim against it.
set -euo pipefail

GHOSTTY_COMMIT=079f5d2025ef6d654989ddbadad1d72c49528bbd # 2026-10-14
ZIG_VERSION=0.16.0
ZIG_SHA256=b23d70deaa879b5c2d486ed3316f7eaa53e84acf6fc9cc747de152450d401489
NDK_VERSION=29.0.14206865
TARGETS=(arm64-v8a:aarch64-linux-android) # the phone, and emulators on Apple Silicon

root=$(cd "$(dirname "$0")/.." && pwd)
cache=${XDG_CACHE_HOME:-$HOME/.cache}/wallflower
out=$root/android/terminal/libghostty
sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
export ANDROID_NDK_HOME=$sdk/ndk/$NDK_VERSION
[ -d "$ANDROID_NDK_HOME" ] || { echo "missing NDK $NDK_VERSION: sdkmanager 'ndk;$NDK_VERSION'" >&2; exit 1; }
mkdir -p "$cache"

zig=$cache/zig-$ZIG_VERSION/zig
if [ ! -x "$zig" ]; then
  tarball=$cache/zig-$ZIG_VERSION.tar.xz
  curl -fsSL -o "$tarball" "https://ziglang.org/download/$ZIG_VERSION/zig-aarch64-macos-$ZIG_VERSION.tar.xz"
  echo "$ZIG_SHA256  $tarball" | shasum -a 256 -c -
  mkdir -p "$cache/zig-$ZIG_VERSION"
  tar -xJf "$tarball" -C "$cache/zig-$ZIG_VERSION" --strip-components 1
  rm "$tarball"
fi

src=$cache/ghostty
if [ "$(git -C "$src" rev-parse HEAD 2>/dev/null)" != "$GHOSTTY_COMMIT" ]; then
  rm -rf "$src"
  git init -q "$src"
  git -C "$src" fetch -q --depth 1 https://github.com/ghostty-org/ghostty.git "$GHOSTTY_COMMIT"
  git -C "$src" checkout -q FETCH_HEAD
fi

rm -rf "$out"
for t in "${TARGETS[@]}"; do
  abi=${t%%:*} triple=${t#*:}
  echo "building libghostty-vt for $abi ($triple)"
  (cd "$src" && "$zig" build -Demit-lib-vt -Dtarget="$triple" -Doptimize=ReleaseFast --prefix "$out/$abi")
done
echo "$GHOSTTY_COMMIT" > "$out/COMMIT"
