import SessionCore
import SettingsUI
import SwiftUI
import WallflowerDesign
import Wire

/**
 * The Mac, while the phone's in none of its sessions: how the connection
 * is, and the sessions in every multiplexer it has, each saying whether it
 * runs and what can be done (the multiplexer it was last in first). A tap
 * goes into one; holding one offers rename and close; new starts one, and
 * the multiplexer with it where it can. The list keeps up with the Mac on
 * its own. While the connection's down, it says how it's coming back, and
 * when it's waiting for the user, what to do.
 */
struct HomeScreen: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    @Environment(\.scenePhase) private var phase
    let session: Session
    let host: Host
    let model: AppModel
    let onSettings: () -> Void
    let onExit: () -> Void

    @State private var naming: String?
    @State private var renaming: (String, SessionInfo)?
    @State private var closing: (String, SessionInfo)?
    @State private var name = ""
    @State private var saysDown = false

    var body: some View {
        let status = session.status
        let machine = host.called
        let address = host.dev ? "development transport" : "\(host.user)@\(host.address):\(host.port)"
        VStack(spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(machine)
                        .font(look.words(look.size(mono: 16, sans: 22), weight: .bold))
                        .foregroundColor(palette.fg)
                        .lineLimit(1)
                    Text(statusLine(status, session: nil)).words(.label).foregroundColor(statusColor(status, palette))
                }
                Spacer()
                ConnectionButton(status: status, machine: machine, address: address, session: nil, onRetry: { session.retry() }, onSessions: {}, onSettings: onSettings, onExit: onExit)
            }
            .padding(.leading, 20)
            .padding(.top, 10)
            .padding(.bottom, 4)
            if case .needsUser(let blocked) = status {
                BlockedCard(
                    blocked: blocked, machine: machine, unlockSeconds: model.unlockSeconds, authorizeCommand: model.authorizeCommand,
                    onUnlock: { model.unlock() },
                    onTrust: { h, k in model.hostKeys.trust(host: h, key: k); session.retry() },
                    onForget: { h in model.hostKeys.forget(host: h); session.retry() },
                    onRetry: { session.retry() },
                    onSetup: onSettings
                )
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
            if let muxes = session.sessions?.muxes {
                if saysDown {
                    Reconnecting(status: status, machine: machine, onRetry: { session.retry() })
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        if let left = session.left { Text(left).words(.caption).foregroundColor(palette.grey) }
                        ForEach(muxes.sorted { a, _ in a.name == host.mux }, id: \.name) { m in
                            MuxSection(
                                mux: m, machine: machine, starting: session.starting == m.name,
                                onOpen: { session.switchTo(mux: m.name, session: $0.id) },
                                onNew: { name = ""; naming = m.name },
                                onRename: { s in name = s.name; renaming = (m.name, s) },
                                onClose: { closing = (m.name, $0) }
                            )
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 8)
                    .padding(.bottom, 24)
                }
                .opacity(saysDown || status.isBlocked ? 0.45 : 1)
            } else {
                ZStack {
                    if status == .connected {
                        Text("asking \(machine)…").words(.body).foregroundColor(palette.grey)
                    } else {
                        Connecting(status: status, machine: machine, address: address, onRetry: { session.retry() })
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(palette.bg0.ignoresSafeArea())
        .animation(Motion.spatial, value: saysDown)
        .animation(Motion.spatial, value: status.isBlocked)
        .onAppear { session.start() }
        .onChange(of: model.rehearsal.count) { _, _ in
            if model.rehearsal.arg("trust") != nil, case .needsUser(.unknownHost(let h, let k)) = session.status {
                model.hostKeys.trust(host: h, key: k)
                session.retry()
            }
            if model.rehearsal.arg("retry") != nil { session.retry() }
            if let name = model.rehearsal.arg("open"), let m = session.sessions?.muxes.first(where: { $0.sessions.contains { $0.name == name } }), let s = m.sessions.first(where: { $0.name == name }) {
                session.switchTo(mux: m.name, session: s.id)
            }
        }
        .task(id: status.isDown) {
            // Down a moment, as across a network change, it isn't worth saying.
            let down = status.isDown
            if down { try? await Task.sleep(for: .seconds(1.5)) }
            guard !Task.isCancelled else { return }
            saysDown = down
        }
        .task(id: status.isKeyLocked) {
            if status.isKeyLocked && phase == .active { model.unlock() }
        }
        .alert(naming.map { "new \($0) session" } ?? "", isPresented: Binding(get: { naming != nil }, set: { if !$0 { naming = nil } })) {
            TextField("name (optional)", text: $name)
            Button("start") {
                if let m = naming { session.newSession(mux: m, name: name) }
                naming = nil
            }
            Button("cancel", role: .cancel) { naming = nil }
        }
        .alert("rename session", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("name", text: $name)
            Button("rename") {
                let n = name.trimmingCharacters(in: .whitespaces)
                if let (m, s) = renaming, !n.isEmpty { session.renameSession(mux: m, session: s.id, name: n) }
                renaming = nil
            }
            Button("cancel", role: .cancel) { renaming = nil }
        }
        .confirmationDialog(closing.map { "close \($0.1.name)?" } ?? "", isPresented: Binding(get: { closing != nil }, set: { if !$0 { closing = nil } }), titleVisibility: .visible) {
            Button("close", role: .destructive) {
                if let (m, s) = closing { session.killSession(mux: m, session: s.id) }
                closing = nil
            }
            Button("cancel", role: .cancel) { closing = nil }
        } message: {
            if let (_, s) = closing {
                Text(s.windows == 1 ? "Its tab closes, and the programs running in it end." : "Its \(s.windows) tabs close, and the programs running in them end.")
            }
        }
    }
}

/**
 * A multiplexer's sessions: whether it runs, and what can be done, as it
 * says. The app knows none of them by name: what one can do is its own.
 */
private struct MuxSection: View {
    let mux: MuxSessions
    let machine: String
    let starting: Bool
    let onOpen: (SessionInfo) -> Void
    let onNew: () -> Void
    let onRename: (SessionInfo) -> Void
    let onClose: (SessionInfo) -> Void

    var body: some View {
        let can = { (what: String) in mux.can.contains(what) && mux.error == nil }
        VStack(alignment: .leading, spacing: 12) {
            WallflowerDesign.Section(mux.name)
            if let error = mux.error {
                WallflowerDesign.Body(error)
            } else if !mux.running && can(MuxSessions.CAN_NEW) {
                WallflowerDesign.Body("not running: a new session starts it")
            } else if !mux.running {
                WallflowerDesign.Body("not running on \(machine)")
            } else if mux.sessions.isEmpty {
                WallflowerDesign.Body("no sessions")
            }
            ForEach(mux.sessions, id: \.id) { s in
                SessionRow(info: s, onClick: { onOpen(s) }, onRename: can(MuxSessions.CAN_RENAME) ? { onRename(s) } : nil, onClose: can(MuxSessions.CAN_KILL) ? { onClose(s) } : nil)
            }
            if can(MuxSessions.CAN_NEW) { NewSession(starting: starting, onNew: onNew) }
        }
    }
}

private struct SessionRow: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    @Environment(\.haptics) private var haptics
    let info: SessionInfo
    let onClick: () -> Void
    let onRename: (() -> Void)?
    let onClose: (() -> Void)?

    var body: some View {
        Button {
            haptics.tick()
            onClick()
        } label: {
            HStack(spacing: 12) {
                Text(info.name)
                    .font(look.words(look.size(mono: 13, sans: 16), weight: look == .grotesque ? .semibold : .bold))
                    .foregroundColor(palette.fg)
                    .lineLimit(1)
                    .frame(maxWidth: 220, alignment: .leading)
                    .fixedSize(horizontal: true, vertical: false)
                Text(info.windows == 1 ? "1 tab" : "\(info.windows) tabs").words(.label).foregroundColor(palette.grey).lineLimit(1)
                Spacer()
                Text("›").font(Fonts.mono(14)).foregroundColor(palette.grey)
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 12).fill(palette.bg1))
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(Pressable())
        .contextMenu {
            if let onRename { Button("rename", action: onRename) }
            if let onClose { Button("close", role: .destructive, action: onClose) }
        }
    }
}

/**
 * Starts a session: a row as big as a session's, with nothing behind it,
 * its dashed edge creeping round clockwise, from a standstill up to a slow
 * walk as it comes in.
 */
private struct NewSession: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    @Environment(\.haptics) private var haptics
    @Environment(\.accessibilityReduceMotion) private var still
    let starting: Bool
    let onNew: () -> Void
    @State private var start = Date()

    var body: some View {
        Button {
            haptics.tick()
            onNew()
        } label: {
            HStack(spacing: 8) {
                GlyphIcon(.plus, palette.fg, size: 11)
                Text(starting ? "starting…" : "new session")
                    .font(look.words(look.size(mono: 13, sans: 16)))
                    .foregroundColor(palette.fg)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .padding(14)
            .background {
                TimelineView(.animation(minimumInterval: 1.0 / 30, paused: still)) { t in
                    let crept = still ? 0 : Self.crept(t.date.timeIntervalSince(start))
                    GeometryReader { geo in
                        let w: CGFloat = 1.5
                        let r: CGFloat = 12 - w / 2
                        let box = CGRect(origin: .zero, size: geo.size).insetBy(dx: w / 2, dy: w / 2)
                        // Dashes that fit the edge evenly, so there's no seam where it starts.
                        let round = 2 * (box.width + box.height) - (8 - 2 * .pi) * r
                        let period = round / max((round / 10).rounded(), 1)
                        let phase = period - crept.truncatingRemainder(dividingBy: period)
                        RoundedRectangle(cornerRadius: r)
                            .inset(by: w / 2)
                            .stroke(palette.grey, style: StrokeStyle(lineWidth: w, dash: [period * 0.55, period * 0.45], dashPhase: phase))
                    }
                }
            }
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(Pressable())
        .disabled(starting)
        .onAppear { start = Date() }
    }

    /** How far round the dashes have crept after [t] seconds: up to 3pt a second over 0.7s, eased. */
    private static func crept(_ t: Double) -> CGFloat {
        let up = 0.7
        if t < up {
            // The integral of 3·smoothstep(t/up).
            let u = t / up
            return CGFloat(3 * up * (u * u * u - u * u * u * u / 2))
        }
        return CGFloat(3 * up * 0.5 + 3 * (t - up))
    }
}
