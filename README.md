# wallflower

A shell that fits around your terminal and is designed for your phone:
your computer's tmux (or Rex) sessions, and the programs in them, from
Android or iOS, over SSH and Tailscale. Free to use. This repository holds
its releases and its site, [ahmd.sh/wallflower](https://ahmd.sh/wallflower);
the source isn't public.

## Install

On the computer (macOS or Linux):

```sh
curl -fsSL https://ahmd.sh/wallflower/install.sh | sh
```

Setup puts wallflower in `~/.local/bin`, checks for tmux, an SSH server and
Tailscale, offers to install or start what's missing, and shows a QR code.
Scan it in the app (Tailscale on the phone too, same account) and it
connects. Run it again to add another phone.

The apps are in the [latest release](https://github.com/ahmd-sh/wallflower/releases/latest):

- Android: `wallflower.apk`
- iOS: `wallflower.ipa`, to open in SideStore or AltStore

## Updates

The app keeps the computer's wallflower up to date, by itself or when you
tap, as you choose, and itself. On the computer, `wallflower update` does
the same.

## What's checked

Each release's `release.json` lists its files and their SHA-256s, and is
signed with wallflower's release key, which never leaves the developer's
Mac. The installer, `wallflower update` and the apps take nothing until
that signature checks out, use a file only once it matches, and only ever
install a newer version. The keys, Ed25519, base64:

- `ExayeGR5kEoU9xm189YRktkCRdkL6IVCT0dYNKtv5Wo=`, which signs releases
- `x93AJI853O+UrLRKDerUGrxMevnWoj4DVuGZWLTS1hA=`, a backup kept offline, in case that one is lost

`wallflower.apk` is signed by the certificate with SHA-256
`4A:6F:FF:06:1B:DA:63:3C:FF:F8:F0:D1:34:80:D8:5F:99:7D:8B:C1:10:59:0B:1B:DB:7A:31:B8:18:3C:33:10`,
and Android only updates it with an APK signed the same way. Each release's
`checksums.txt` has every file's SHA-256, to check by hand.

What can't be checked is the first download: `install.sh` comes from this
site over HTTPS.

## Licenses

wallflower © 2026 Ahmed Shaikh, all rights reserved, free to use. What the
apps bundle keeps its own license:

- Bricolage Grotesque, Noto Sans Symbols and Noto Sans Symbols 2: SIL Open Font License 1.1
- JetBrains Mono, as a Nerd Font: SIL Open Font License 1.1, its added icons their projects' licenses
- libghostty (Ghostty): MIT
- Apple's Swift packages (SwiftNIO, NIO SSH, Swift Crypto and the rest), AndroidX, Jetpack Compose, kotlinx.coroutines, kotlinx.serialization: Apache 2.0
- JSch: BSD; Bouncy Castle: MIT
- Google's code scanner: the Android SDK License
- Go's standard library, in the computer's binary: BSD
