import SessionCore
import SwiftUI
import WallflowerDesign

/**
 * Setting wallflower up: what it is, in a line, then the computer to reach and
 * this phone's key to let in there. With a way back ([onBack]) in place of
 * the welcome, it edits the computer from settings.
 */
public struct SetupScreen: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    let host: Host?
    let hasKey: Bool
    let fingerprint: String?
    let authorizeCommand: () -> String
    let createKey: () -> String?
    let unlockSeconds: Int?
    let newKey: (Int) -> String?
    let onBack: (() -> Void)?
    let onConnect: (Host) -> Void

    public init(host: Host?, hasKey: Bool, fingerprint: String?, authorizeCommand: @escaping () -> String, createKey: @escaping () -> String?, unlockSeconds: Int?, newKey: @escaping (Int) -> String?, onBack: (() -> Void)?, onConnect: @escaping (Host) -> Void) {
        self.host = host
        self.hasKey = hasKey
        self.fingerprint = fingerprint
        self.authorizeCommand = authorizeCommand
        self.createKey = createKey
        self.unlockSeconds = unlockSeconds
        self.newKey = newKey
        self.onBack = onBack
        self.onConnect = onConnect
    }

    public var body: some View {
        let form = SetupForm(host: host, hasKey: hasKey, fingerprint: fingerprint, authorizeCommand: authorizeCommand, createKey: createKey, unlockSeconds: unlockSeconds, newKey: newKey, onConnect: onConnect)
        if let onBack {
            Page("mac", onBack: onBack) { form }
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    VStack(spacing: 0) {
                        Flower(sway: true).frame(width: 128, height: 128)
                        Text("wallflower")
                            .font(look.words(look.size(mono: 24, sans: 30), weight: .bold))
                            .foregroundColor(palette.fg)
                        Text("Your computer's terminals, made for your phone.")
                            .words(.body)
                            .foregroundColor(palette.grey)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 16)
                    .padding(.bottom, 8)
                    form
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 20)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(palette.bg0.ignoresSafeArea())
        }
    }
}

/** The computer (where, who), this phone's key, and connect, saying plainly what's missing. */
private struct SetupForm: View {
    @Environment(\.palette) private var palette
    let host: Host?
    let hasKey: Bool
    let fingerprint: String?
    let authorizeCommand: () -> String
    let createKey: () -> String?
    let unlockSeconds: Int?
    let newKey: (Int) -> String?
    let onConnect: (Host) -> Void
    @State private var address: String
    @State private var user: String
    @State private var port: String
    @State private var dev: Bool
    @State private var keyError: String?
    @State private var tried = false
    @State private var picked: Int?

    init(host: Host?, hasKey: Bool, fingerprint: String?, authorizeCommand: @escaping () -> String, createKey: @escaping () -> String?, unlockSeconds: Int?, newKey: @escaping (Int) -> String?, onConnect: @escaping (Host) -> Void) {
        self.host = host
        self.hasKey = hasKey
        self.fingerprint = fingerprint
        self.authorizeCommand = authorizeCommand
        self.createKey = createKey
        self.unlockSeconds = unlockSeconds
        self.newKey = newKey
        self.onConnect = onConnect
        let initial = host ?? Host()
        _address = State(initialValue: initial.address)
        _user = State(initialValue: initial.user)
        _port = State(initialValue: String(initial.port))
        _dev = State(initialValue: initial.dev)
    }

    var body: some View {
        Section("your computer")
        Field("address", value: $address, hint: "100.x.y.z, or its tailnet name", keyboard: .URL)
        Text("This phone's key only works over Tailscale, so it's the computer's Tailscale address.")
            .words(.caption)
            .foregroundColor(palette.dim)
            .fixedSize(horizontal: false, vertical: true)
        HStack(alignment: .top, spacing: 10) {
            Field("username", value: $user, hint: "yours on the computer")
                .frame(maxWidth: .infinity)
            Field("port", value: Binding(get: { port }, set: { port = String($0.filter { $0.isASCII && $0.isNumber }.prefix(5)) }), keyboard: .numberPad)
                .frame(width: 88)
        }
        #if DEBUG
        Toggle("development transport (plain TCP, no ssh)", on: dev) { dev = $0 }
        #endif

        Section("this phone's key")
        if hasKey, let fingerprint {
            Paragraph("Made and kept in this phone's secure hardware; \(Unlock.words) unlocks it.")
            Text(fingerprint).font(Fonts.mono(12)).foregroundColor(palette.aqua).textSelection(.enabled)
            Text("Let it in: run this on the computer. It can start wallflower there, and nothing else.")
                .words(.label)
                .foregroundColor(palette.fg)
                .fixedSize(horizontal: false, vertical: true)
            let command = authorizeCommand()
            Code(command)
            HStack(spacing: 8) {
                CopyButton(text: command)
                ShareButton(text: command)
            }
            if host != nil {
                Text("Unlocked for").words(.label).foregroundColor(palette.fg)
                Paragraph("How long after \(Unlock.words) the key works, so the app can reconnect on its own while you're away.")
                HStack(spacing: 6) {
                    ForEach(unlocks, id: \.self) { s in
                        Chip(unlockLabel(s), selected: (picked ?? unlockSeconds) == s) { picked = s != unlockSeconds ? s : nil }
                    }
                }
                if let s = picked {
                    Paragraph("The time is part of the key, so this makes a new one. Authorize it on the computer before this connection ends (a pane here works): until then, only the computer can let the phone back in.")
                    WordButton("make a new key, unlocked for \(unlockLabel(s))", primary: true) {
                        keyError = newKey(s)
                        picked = nil
                    }
                }
            }
        } else {
            Paragraph("wallflower signs in to the computer with a key made and kept in this phone's secure hardware, unlocked by \(Unlock.words). It never leaves the phone.")
            WordButton("make the key", primary: true) { keyError = createKey() }
        }
        if let keyError {
            Text(keyError).words(.label).foregroundColor(palette.red).fixedSize(horizontal: false, vertical: true)
        }

        Spacer().frame(height: 4)
        let need = missing
        WordButton("connect", primary: true) {
            tried = true
            guard need == nil, let p = Int(port) else { return }
            let initial = host ?? Host()
            let at = address.trimmingCharacters(in: .whitespacesAndNewlines)
            let same = at == initial.address
            onConnect(Host(
                address: at, port: p, user: user.trimmingCharacters(in: .whitespacesAndNewlines), command: initial.command, mux: initial.mux,
                session: same ? initial.session : nil, dev: dev, machine: same ? initial.machine : nil
            ))
        }
        if tried, let need {
            Text(need).words(.label).foregroundColor(palette.red)
        }
    }

    /** What connecting needs that's not there yet, in a line; nil if nothing. */
    private var missing: String? {
        if !(1 ... 65535).contains(Int(port) ?? 0) { return "The port is a number up to 65535; ssh's is 22." }
        if dev { return nil }
        if address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return "Fill in the computer's address." }
        if user.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return "Fill in your username on the computer." }
        if !hasKey { return "Make this phone's key first." }
        return nil
    }
}

private let unlocks = [5 * 60, 30 * 60, 2 * 3600, 8 * 3600]
