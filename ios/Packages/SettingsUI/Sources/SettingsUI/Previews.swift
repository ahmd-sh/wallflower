import SessionCore
import SwiftUI
import WallflowerDesign
import Wire

private let defaults = UserDefaults(suiteName: "wallflower.preview")!
private let computer = Host(address: "100.101.102.103", user: "me", machine: "studio")
private let command = "~/go/bin/wallflower authorize 'ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBA== wallflower@iPhone'"

#Preview("settings") {
    let _ = Fonts.register()
    SettingsScreen(host: computer, store: KeyBarStore(defaults: defaults), settings: Preferences(defaults: defaults), program: "claude", programId: "claude", gateway: "gateway 0.9 · tmux 3.5a", onBack: {}, onEditHost: {}, onLook: {}, onKeyBars: {})
}

#Preview("look") {
    let _ = Fonts.register()
    LookScreen(settings: Preferences(defaults: defaults), onBack: {})
}

#Preview("key bars") {
    let _ = Fonts.register()
    KeyBarsScreen(store: KeyBarStore(defaults: defaults), program: "hntui", programId: nil, onBack: {}, onOpen: { _ in })
}

#Preview("profile") {
    let _ = Fonts.register()
    ProfileScreen(store: KeyBarStore(defaults: defaults), ref: .at(0), program: "claude", programId: "claude", onBack: {}, onShortcuts: {}, onOpen: { _ in })
}

#Preview("shortcuts") {
    let _ = Fonts.register()
    ShortcutsScreen(store: KeyBarStore(defaults: defaults), ref: .at(0), program: "claude", programId: "claude", onBack: {}, onOpen: { _ in })
}

#Preview("default keys") {
    let _ = Fonts.register()
    ProfileScreen(store: KeyBarStore(defaults: defaults), ref: .default, program: "hntui", programId: nil, onBack: {}, onShortcuts: {}, onOpen: { _ in })
}

#Preview("welcome") {
    let _ = Fonts.register()
    SetupScreen(host: nil, hasKey: false, fingerprint: nil, authorizeCommand: { command }, createKey: { nil }, unlockSeconds: nil, newKey: { _ in nil }, onBack: nil, onConnect: { _ in })
}

#Preview("computer") {
    let _ = Fonts.register()
    SetupScreen(host: computer, hasKey: true, fingerprint: "SHA256:q3MrS9fZ0v4nWbGQ1yQ7c2eHk8p0tLxR5uJm6aDdV1E", authorizeCommand: { command }, createKey: { nil }, unlockSeconds: 300, newKey: { _ in nil }, onBack: {}, onConnect: { _ in })
}

#Preview("blocked") {
    let _ = Fonts.register()
    ScrollView {
        ForEach([
            Blocked.keyLocked,
            .unknownHost(host: "100.101.102.103", key: "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl"),
            .hostKeyChanged(host: "100.101.102.103", fingerprint: "SHA256:q3MrS9fZ0v4nWbGQ1yQ7c2eHk8p0tLxR5uJm6aDdV1E"),
            .notAuthorized,
            .outdated(gateway: 5, app: 6),
            .outdated(gateway: 7, app: 6),
            .refused("no rex here"),
        ], id: \.message) { b in
            BlockedCard(blocked: b, machine: "studio", unlockSeconds: 1800, authorizeCommand: { command }, onUnlock: {}, onTrust: { _, _ in }, onForget: { _ in }, onRetry: {}, onSetup: {})
        }
    }
    .background(Themes.everforestDarkHard.bg0)
}

#Preview("connection") {
    let _ = Fonts.register()
    let down = Status.disconnected(reason: "connection refused", since: Date().addingTimeInterval(-30), retryAt: Date().addingTimeInterval(8), trying: false)
    VStack(spacing: 24) {
        HStack {
            Spacer()
            ConnectionButton(status: down, machine: "studio", address: "100.101.102.103", session: nil, onRetry: {}, onSessions: {}, onSettings: {}, onExit: {})
            ConnectionButton(status: .connecting, machine: "studio", address: "100.101.102.103", session: nil, onRetry: {}, onSessions: {}, onSettings: {}, onExit: {})
            ConnectionButton(status: .connected, machine: "studio", address: "100.101.102.103", session: "main · tmux", onRetry: {}, onSessions: {}, onSettings: {}, onExit: {})
        }
        Text(statusLine(down, session: nil)).foregroundColor(statusColor(down, Themes.everforestDarkHard))
        Reconnecting(status: down, machine: "studio", onRetry: {})
        Connecting(status: .connecting, machine: "studio", address: "100.101.102.103", onRetry: {})
    }
    .background(Themes.everforestDarkHard.bg0)
}

#Preview("flower") {
    HStack(spacing: 24) {
        Flower(sway: true).frame(width: 128, height: 128)
        Flower(sway: false).frame(width: 64, height: 64)
    }
}
