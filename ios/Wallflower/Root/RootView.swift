import ScreenReading
import SessionCore
import SettingsUI
import SwiftUI
import WallflowerDesign

/** A page over the terminal: settings, and the pages it opens. */
enum Page: Equatable {
    case settings, setup, look, keyBars
    indirect case profile(ProfileRef, back: Page?)
    indirect case shortcuts(ProfileRef, back: Page?)

    /** How many pages in: a page further in slides in over the one before. */
    var depth: Int {
        switch self {
        case .settings: return 1
        case .setup, .look, .keyBars: return 2
        case .profile(_, let back), .shortcuts(_, let back): return (back?.depth ?? 0) + 1
        }
    }

    var previous: Page? {
        switch self {
        case .settings: return nil
        case .setup, .look, .keyBars: return .settings
        case .profile(_, let back), .shortcuts(_, let back): return back
        }
    }
}

/**
 * Where the user is: setting up, the session's terminals, or the computer's
 * sessions while the phone's in none, or a page over them, which slides in
 * from the side and goes back with a swipe from the edge, as iOS pages do.
 */
struct RootView: View {
    @Environment(\.colorScheme) private var scheme
    let model: AppModel
    @State private var page: Page?
    @State private var program: String?
    @State private var programId: String?
    @State private var dragged: CGFloat = 0

    var body: some View {
        let colors = model.settings.scheme.colors(dark: scheme == .dark)
        content
            .environment(\.palette, colors)
            .environment(\.look, model.settings.look)
            .environment(\.haptics, model.haptics)
            .tint(colors.orange)
            .preferredColorScheme(colors.light ? .light : .dark)
            .onChange(of: model.settings.haptics, initial: true) { _, on in model.haptics.on = on }
            .onChange(of: model.rehearsal.count) { _, _ in rehearse() }
    }

    @ViewBuilder
    private var content: some View {
        if let host = model.host, let session = model.session {
            ZStack {
                Group {
                    if session.place == nil {
                        HomeScreen(session: session, host: host, model: model, onSettings: { go(.settings) }, onExit: exit)
                            .onAppear { program = nil; programId = nil }
                            .transition(.opacity)
                    } else {
                        TerminalScreen(
                            session: session, host: host, model: model,
                            onProgram: { c, id in program = c; programId = id },
                            onSettings: { go(.settings) },
                            onEditKeys: { go(.profile(model.keyBar.bar.refFor(command: program, program: programId), back: nil)) },
                            onAddShortcut: { go(.shortcuts(model.keyBar.bar.refFor(command: program, program: programId), back: nil)) },
                            onExit: exit
                        )
                        .transition(.opacity)
                    }
                }
                .animation(Motion.effect, value: session.place == nil)
                .id(ObjectIdentifier(session))
                if let page {
                    pageView(page, host: host, session: session)
                        .offset(x: dragged)
                        .gesture(back)
                        .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .move(edge: .trailing).combined(with: .opacity)))
                        .zIndex(Double(page.depth))
                        .id(pageKey(page))
                }
            }
            .animation(Motion.spatial, value: page)
            .fullScreenCover(item: Binding(get: { model.files }, set: { model.files = $0 })) { at in
                FilesPage(at: at, session: session, onAdd: at.pane.map { pane in { path in add(path, to: pane) } }, onClose: { model.files = nil })
                    .environment(\.palette, palette)
                    .environment(\.look, model.settings.look)
                    .environment(\.haptics, model.haptics)
            }
            .fullScreenCover(item: Binding(get: { model.showing }, set: { model.showing = $0 })) { s in
                ViewerCover(showing: s, session: session, onAdd: { pane, path in add(path, to: pane) }, onClose: { model.showing = nil })
                    .environment(\.palette, palette)
                    .environment(\.look, model.settings.look)
                    .environment(\.haptics, model.haptics)
                    .tint(palette.orange)
            }
        } else if let host = model.host {
            Disconnected(machine: host.called) { model.ensure() }
        } else {
            setup(onBack: nil)
        }
    }

    /** A swipe from the left edge goes back a page. */
    private var back: some Gesture {
        DragGesture(minimumDistance: 10, coordinateSpace: .global)
            .onChanged { v in
                guard v.startLocation.x < 28, v.translation.width > 0 else { return }
                dragged = v.translation.width
            }
            .onEnded { v in
                guard v.startLocation.x < 28 else { return }
                if v.translation.width > 100 || v.predictedEndTranslation.width > 240 {
                    model.haptics.tick()
                    var t = Transaction(animation: Motion.spatial)
                    t.disablesAnimations = false
                    withTransaction(t) {
                        page = page?.previous
                        dragged = 0
                    }
                } else {
                    withAnimation(Motion.spatial) { dragged = 0 }
                }
            }
    }

    private func pageKey(_ p: Page) -> String {
        switch p {
        case .settings: return "settings"
        case .setup: return "setup"
        case .look: return "look"
        case .keyBars: return "keyBars"
        case .profile(let r, _): return "profile \(r)"
        case .shortcuts(let r, _): return "shortcuts \(r)"
        }
    }

    private func go(_ p: Page?) {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil) // the composer's keyboard goes away
        page = p
    }

    @ViewBuilder
    private func pageView(_ p: Page, host: Host, session: Session) -> some View {
        switch p {
        case .settings:
            SettingsScreen(
                host: host, store: model.keyBar, settings: model.settings, program: program, programId: programId,
                gateway: session.mux.map { m in "\(m.name) \(m.version)" + (host.machine.map { " on \($0)" } ?? "") },
                onBack: { go(nil) }, onEditHost: { go(.setup) }, onLook: { go(.look) }, onKeyBars: { go(.keyBars) }
            )
        case .setup:
            setup(onBack: { go(.settings) })
        case .look:
            LookScreen(settings: model.settings, onBack: { go(.settings) })
        case .keyBars:
            KeyBarsScreen(store: model.keyBar, program: program, programId: programId, onBack: { go(.settings) }) { go(.profile($0, back: .keyBars)) }
        case .profile(let ref, let back):
            ProfileScreen(
                store: model.keyBar, ref: ref, program: program, programId: programId,
                onBack: { go(back) }, onShortcuts: { go(.shortcuts(ref, back: p)) }
            ) { go(.profile($0, back: back)) }
        case .shortcuts(let ref, let back):
            ShortcutsScreen(store: model.keyBar, ref: ref, program: program, programId: programId, onBack: { go(back) }) { go(.shortcuts($0, back: back)) }
        }
    }

    private func setup(onBack: (() -> Void)?) -> some View {
        SetupScreen(
            host: model.host, hasKey: model.hasKey, fingerprint: model.hasKey ? model.key.fingerprint : nil,
            authorizeCommand: model.authorizeCommand, createKey: model.createKey, unlockSeconds: model.unlockSeconds, newKey: model.newKey,
            pairing: model.pairing, pairError: model.pairError, onPair: { model.pair($0) { go(nil) } },
            onBack: onBack
        ) { h in
            model.configure(h)
            go(nil)
        }
    }

    private func rehearse() {
        let r = model.rehearsal
        if r.arg("settings") != nil { go(.settings) }
        if r.arg("look") != nil { go(.look) }
        if r.arg("keybars") != nil { go(.keyBars) }
        if r.arg("setup") != nil { go(.setup) }
        if r.arg("profile") != nil { go(.profile(model.keyBar.bar.refFor(command: program, program: programId), back: nil)) }
        if r.arg("shortcuts") != nil { go(.shortcuts(model.keyBar.bar.refFor(command: program, program: programId), back: nil)) }
        if r.arg("back") != nil { go(page?.previous) }
        if let s = r.arg("scheme"), let scheme = Scheme(rawValue: s) { model.settings.scheme = scheme }
        if let l = r.arg("lookas"), let look = Look(rawValue: l) { model.settings.look = look }
        if r.arg("sessions") != nil { model.session?.detach() }
        if let f = r.arg("files") { model.files = FilesAt(start: f.isEmpty ? "~" : f, pane: nil) }
    }

    private var palette: Colors { model.settings.scheme.colors(dark: scheme == .dark) }

    /** A path from the files page or the viewer into a pane's draft, as a word of its own; back to the terminal to go on writing. */
    private func add(_ path: String, to pane: String) {
        model.drafts.insert(pane, messagePath(path))
        model.files = nil
        model.showing = nil
    }

    /** iOS apps don't quit: leaving disconnects, until the app's opened again. */
    private func exit() {
        model.disconnect()
        model.haptics.confirm()
    }
}

/** Left: the app's done with the computer until it connects again. */
private struct Disconnected: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    let machine: String
    let onConnect: () -> Void

    var body: some View {
        VStack(spacing: 14) {
            Flower(sway: false).frame(width: 96, height: 96)
            Text("disconnected from \(machine)").font(look.font(.title, weight: .semibold)).foregroundColor(palette.fg)
            WordButton("connect", primary: true, action: onConnect)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(palette.bg0.ignoresSafeArea())
    }
}
