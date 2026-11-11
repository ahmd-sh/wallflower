# wallflower for iOS

The iOS app, ported from `android/`: the same protocol, session logic and
screens, in SwiftUI, with iOS's own ways where Android's would be out of
place (native menus that take a press-and-slide, a Live Activity in place of
Android 16's Live Update, the Secure Enclave in place of Android Keystore).

```
Wallflower/            the app: root, home, terminal screen, composer, services
WallflowerWidgets/     the Live Activity (Lock Screen and Dynamic Island)
Shared/                what both have: the activity's attributes
Packages/
  Wire                 the protocol (frames, messages), checked against testdata/
  ReadModel            mobile views as the app keeps them
  ScreenReading        reading a program's screen: input lines, prompts; programs, key bars
  MarkdownKit          markdown, diffs, pane header names
  TerminalCore         libghostty-vt behind a C shim (the Android JNI shim, without JNI)
  SessionCore          the link, the session, SSH (swift-nio-ssh), the phone's key
  WallflowerDesign     colors, type, haptics, glyphs, the small components
  MobileViewUI         the conversation view, prompt cards, markdown
  SettingsUI           settings, setup, key bars, connection states
```

## Build

```sh
scripts/build-libghostty.sh ios      # once: the xcframework into Packages/TerminalCore/Vendor
open ios/Wallflower.xcodeproj
ios/scripts/ipa.sh                   # an unsigned dist/wallflower.ipa
```

The pure packages test on the Mac: `cd ios/Packages/Wire && swift test` (and
ReadModel, ScreenReading, MarkdownKit, TerminalCore, SessionCore).
SessionCore's live test runs against `wallflower listen` when `WF_LISTEN`
names its port.

## Develop against the simulator

The simulator reaches the Mac's loopback as its own, so it talks to
`wallflower listen` directly. In a Debug build, `-wfDev <port>` connects to
it over TCP instead of ssh:

```sh
bin/wallflower listen -L demo -rex /tmp/wfrex-none/server.sock -addr 127.0.0.1:41778
xcrun simctl launch <device> sh.ahmd.wallflower -wfDev 41778
```

## Installing the IPA

It's unsigned: AltStore, SideStore or Sideloadly sign it with the installer's
own Apple ID (a free one works; apps signed that way last 7 days). The app
and its widget extension each need an app ID.

## Away from the app

iOS suspends an app soon after it leaves the screen, and a phone without a
paid developer account can't be woken by push. To keep the connection (and
with it notifications and the Live Activity) while away, as Android's
foreground service does, the app plays silence, mixed with anything else
playing, while it's in the background. Settings → "stay connected while
wallflower's in the background" turns it off.
