import MarkdownKit
import MobileViewUI
import ReadModel
import ScreenReading
import SessionCore
import SettingsUI
import SwiftUI
import WallflowerDesign
import Wire

/**
 * The session's terminals: tabs, the shown window's panes to swipe between
 * (each its terminal, or its mobile view), and the composer. What's shown is
 * the phone's own; the computer follows. A terminal is typed into straight from
 * the keyboard, which a tap on it brings up; a mobile view is written for in
 * the composer's box. While the connection to the computer is down, it says so,
 * and how it's coming back. [onSessions] leaves the session for the computer's
 * sessions.
 */
struct TerminalScreen: View {
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    @Environment(\.scenePhase) private var phase
    let session: Session
    let host: Host
    let model: AppModel
    let onProgram: (String?, String?) -> Void
    let onSettings: () -> Void
    let onEditKeys: () -> Void
    var onAddShortcut: () -> Void = {}
    let onExit: () -> Void

    @State private var screen = PaneScreen()
    @State private var sticky = StickyKeys()
    @State private var handle = TerminalHandle()
    @State private var pad = ArrowPad()
    @State private var position = PanePosition()
    @State private var saysDown = false
    @State private var renaming: MuxWindow?
    @State private var newName = ""
    @State private var closing: (MuxWindow, Pane?)?
    @State private var typingActive = false
    @State private var typed = false
    @State private var nudged = 0
    @State private var overview = false
    @State private var seeker = Seeker()
    /** The mode picked, being stepped to. */
    @State private var moding: String?

    var body: some View {
        screenContent
            .overlay { ArrowPadUp(pad: pad, onKey: press) }
            .coordinateSpace(.named(ArrowPad.space))
    }

    @ViewBuilder private var screenContent: some View {
        let place = session.place
        let state = session.state.flatMap { $0.session == place?.session ? $0 : nil } // not the last session's, on the way into another
        let windows = state?.windows ?? []
        let window = windows.first { $0.id == session.viewing } ?? windows.first { $0.active }
        let pane = window?.shownPane(session.chosen)
        let bar = model.keyBar.bar
        let mobileViews = mobileViewPanes(window: window, pane: pane)
        let mobile = pane.map { mobileViews.contains($0.id) } ?? false
        let actions = pane.map { actionsOf($0, bar) }
        let activity = activityOf(pane)
        let status = session.status
        let _ = route(actions, mobile: mobile)

        VStack(spacing: 0) {
            TabBar(
                windows: windows,
                selected: window?.id,
                status: status,
                machine: host.called,
                address: address,
                session: [state?.name, session.mux.map { "\($0.name) \($0.version)" }].compactMap { $0 }.joined(separator: " in ").nilIfEmpty,
                onSelect: { session.view($0) },
                onNew: { session.newWindow() },
                onRename: { w in newName = w.name; renaming = w },
                onClose: { close($0, nil) },
                onRetry: { session.retry() },
                onSessions: { session.detach() },
                onSettings: onSettings,
                onExit: onExit,
                onFiles: session.canFiles ? { model.files = FilesAt(start: pane.map(\.path).flatMap(\.nilIfEmpty) ?? "~", pane: pane?.id) } : nil
            )
            if saysDown && state != nil {
                Reconnecting(status: status, machine: host.called, onRetry: { session.retry() })
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
            if case .needsUser(let blocked) = status {
                BlockedCard(
                    blocked: blocked, machine: host.called, unlockSeconds: model.unlockSeconds, authorizeCommand: model.authorizeCommand,
                    onUnlock: { model.unlock() },
                    onTrust: { h, k in model.hostKeys.trust(host: h, key: k); session.retry() },
                    onForget: { h in model.hostKeys.forget(host: h); session.retry() },
                    onRetry: { session.retry() },
                    onSetup: onSettings
                )
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
            ZStack(alignment: .topTrailing) {
                panes(windows: windows, window: window, pane: pane, bar: bar, mobileViews: mobileViews)
                    .opacity(saysDown && state != nil ? 0.45 : 1)
                if state == nil {
                    Connecting(status: status, machine: host.called, address: address, onRetry: { session.retry() }, opening: opening(place))
                        .transition(.opacity)
                }
                if overview, let window {
                    PaneOverview(
                        window: window, shown: pane?.id, terminals: session.terminals,
                        onPick: { p in
                            session.show(window, p)
                            withAnimation(Motion.spatial) { overview = false }
                        },
                        onNew: {
                            if let pane { session.split(pane) }
                            withAnimation(Motion.spatial) { overview = false }
                        },
                        onClose: { close(window, $0) },
                        onFiles: session.canFiles ? { p in
                            withAnimation(Motion.spatial) { overview = false }
                            model.files = FilesAt(start: p.path.nilIfEmpty ?? "~", pane: p.id)
                        } : nil
                    )
                    .transition(.scale(scale: 1.08).combined(with: .opacity))
                    .zIndex(2)
                }
                if let window, let pane, !overview {
                    HStack(spacing: 0) {
                        if pane.view {
                            ViewToggle(mobile: mobile) {
                                haptics.toggle(!mobile)
                                model.settings.mobileView = !mobile
                            }
                            .transition(.opacity.combined(with: .scale))
                        }
                        PaneDots(count: window.panes.count, position: position) {
                            handle.hideKeyboard()
                            withAnimation(Motion.spatial) { overview = true }
                        }
                    }
                }
            }
            .frame(maxHeight: .infinity)
            if let pane, let actions {
                composer(pane: pane, actions: actions, mobile: mobile, activity: activity, bar: bar, connected: status == .connected)
                    .environment(\.viewing, viewing(pane))
            }
        }
        .background(palette.bg0.ignoresSafeArea())
        .animation(Motion.spatial, value: saysDown)
        .animation(Motion.spatial, value: status.isBlocked)
        .animation(Motion.effect, value: state == nil)
        .task(id: status.isDown) {
            // Down a moment, as across a network change, it isn't worth saying.
            let down = status.isDown
            if down { try? await Task.sleep(for: .seconds(1.5)) }
            guard !Task.isCancelled else { return }
            saysDown = down
        }
        .task(id: status.isKeyLocked) {
            // Connecting is the moment to ask for Face ID, so ask right away.
            if status.isKeyLocked && phase == .active { model.unlock() }
        }
        .onChange(of: "\(pane?.command ?? "")|\(pane?.program ?? "")", initial: true) { _, _ in onProgram(pane?.command, pane?.program) }
        .onChange(of: phase) { _, p in
            // Back in the app, what's on screen has been seen, as Android has it.
            if p == .active, let id = pane?.id, model.opening == nil { model.seen(id) }
        }
        .onChange(of: pane?.id, initial: true) { _, id in
            if let id, phase == .active, model.opening == nil { model.seen(id) }
            typed = false
            seeker.reset()
            moding = nil
        }
        .onChange(of: screen.mode) { _, m in
            if let m, let program = actions?.program, program.nextMode != nil { Cycles.of(program).saw(m) }
        }
        .task(id: moding) {
            guard let to = moding else { return }
            await walk(to: to, actions: actions)
            moding = nil
        }
        .onChange(of: screen.prompt) { _, p in
            seeker.prompt(p, actions: actions)
            seeker.settle(prompt: p, held: screen.line?.text, line: screen.line, actions: actions)
        }
        .onChange(of: seeker.seeking) { _, _ in seeker.prompt(screen.prompt, actions: actions) }
        .onChange(of: screen.line?.text) { _, held in
            seeker.settle(prompt: screen.prompt, held: held, line: screen.line, actions: actions)
        }
        .onChange(of: mobileViews) { _, v in session.read(v) }
        .onChange(of: window == nil) { _, gone in if gone { overview = false } }
        .onChange(of: model.rehearsal.count) { _, _ in rehearse(windows: windows, window: window, pane: pane, actions: actions, mobile: mobile) }
        .onAppear { session.read(mobileViews) }
        .onChange(of: model.opening) { _, _ in open() }
        .onChange(of: windows.map(\.id)) { _, _ in open() }
        .onChange(of: screen.line?.text) { _, held in
            // A message the program puts back in its line stays taken back, whatever the line holds next.
            if mobile, let pane { session.takenBack(pane.id, line: held) }
        }
        .modifier(Watch(screen: screen, pane: pane, terminal: pane.flatMap { session.terminals[$0.id] }, program: actions?.program ?? Programs.of(nil), asks: mobile && activity != .working))
        .alert("rename tab", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("name", text: $newName)
            Button("rename") {
                let n = newName.trimmingCharacters(in: .whitespaces)
                if let w = renaming, !n.isEmpty { session.rename(w, n) }
                renaming = nil
            }
            Button("cancel", role: .cancel) { renaming = nil }
        }
        .confirmationDialog(closeTitle, isPresented: Binding(get: { closing != nil }, set: { if !$0 { closing = nil } }), titleVisibility: .visible) {
            Button(closeAction, role: .destructive) { confirmClose(never: false) }
            Button("\(closeAction), and don't ask again", role: .destructive) { confirmClose(never: true) }
            Button("cancel", role: .cancel) { closing = nil }
        } message: {
            Text(closeBody(windows))
        }
    }

    /** Where the bar's keys go, sticky ones with the key after: the shown pane, as it is now. */
    private func route(_ actions: PaneActions?, mobile: Bool) {
        sticky.onKey = { [handle] key in
            guard let actions else { return }
            if !mobile { handle.scrollToBottom() }
            actions.keys(key.keys, bindings: key.bindings)
        }
    }

    /** A key from the bar, or the arrows' pad. */
    private func press(_ key: BarKey) {
        typed = key.keys.last != "Enter"
        sticky.press(key)
    }

    private var address: String { host.dev ? "development transport" : "\(host.user)@\(host.address):\(host.port)" }

    private func opening(_ place: Place?) -> String {
        session.sessions?.muxes.first { $0.name == place?.mux }?.sessions.first { $0.id == place?.session }?.name ?? "the session"
    }

    /**
     * Panes whose program has a mobile view show it while it's on: the shown
     * pane's, and its neighbours', so a swipe brings theirs in already loaded.
     */
    private func mobileViewPanes(window: MuxWindow?, pane: Pane?) -> Set<String> {
        guard model.settings.mobileView, let window, let pane else { return [] }
        let panes = window.panes.sorted { $0.index < $1.index }
        guard let i = panes.firstIndex(where: { $0.id == pane.id }) else { return [] }
        let near = [-1, 0, 1].map { panes[(i + $0 + panes.count) % panes.count] }
        return Set(near.filter(\.view).map(\.id))
    }

    private func actionsOf(_ p: Pane, _ bar: KeyBar) -> PaneActions {
        let program = Programs.of(p.program)
        let profile = bar.profile(for: p.command, program: p.program)
        return PaneActions(session: session, pane: p, program: program, clearKeys: profile.clear ?? program.clearKeys, stopKeys: profile.stop, sendKeys: profile.sendKeys(), returnKeys: profile.returnKeys(program))
    }

    @ViewBuilder
    private func panes(windows: [MuxWindow], window: MuxWindow?, pane: Pane?, bar: KeyBar, mobileViews: Set<String>) -> some View {
        if let window, let pane {
            PanePager(panes: window.panes.sorted { $0.index < $1.index }, shown: pane, onShow: { session.show(window, $0) }, onPosition: { position.at = $0 }, onNew: { session.split(pane) }) { p, current, swipe in
                page(p, current: current, swipe: swipe, bar: bar, mobileViews: mobileViews, tab: window.label())
            }
            .id(window.id)
            .transition(.asymmetric(insertion: .opacity.combined(with: .offset(x: 40)), removal: .opacity))
        } else {
            page(nil, current: true, swipe: nil, bar: bar, mobileViews: [], tab: nil) // measures the grid until there are panes
        }
    }

    private func page(_ p: Pane?, current: Bool, swipe: Swipe?, bar: KeyBar, mobileViews: Set<String>, tab: String?) -> some View {
        let actions = p.map { actionsOf($0, bar) }
        let mobile = p.map { mobileViews.contains($0.id) } ?? false
        return PanePage(
            actions: actions,
            terminal: p.flatMap { session.terminals[$0.id] },
            reading: mobile ? (session.readings[p!.id] ?? Reading()) : nil,
            current: current,
            swipe: swipe,
            covered: overview,
            prompt: current ? screen.prompt : nil,
            line: { screen.line },
            status: current ? screen.status : nil,
            asked: current ? screen.asked : nil,
            aside: current ? screen.aside : nil,
            answer: screen.answer,
            mode: current ? screen.mode : nil,
            modes: current ? modes(actions) : [],
            onMode: { moding = $0 },
            nudged: nudged,
            held: seeker.editing,
            seeking: current && seeker.seeking != nil,
            handle: handle,
            typing: !mobile,
            onGridSize: { cols, rows in
                session.start(cols: cols, rows: rows)
                // iOS gives the view its new size as the keyboard starts to move, not frame by frame: tmux is told at once, and catches up while it slides.
                session.resize(cols: cols, rows: rows, ahead: true)
            },
            onTap: nil,
            onTyped: { t in
                guard let actions else { return }
                sticky.type(t, returnKeys: actions.returnKeys) { text in
                    typed = true
                    handle.scrollToBottom()
                    if screen.line != nil { handle.predict(text) } // at a prompt, which echoes
                    actions.type(text)
                }
            },
            onTypingChanged: { typingActive = $0 },
            onEdit: { id, text in
                guard let actions, let r = session.readings[actions.pane.id] else { return }
                seeker.edit(id, text, actions: actions, reading: r, line: screen.line)
            },
            onPrompt: { seeker.handled() },
            tab: tab
        )
        .environment(\.viewing, p.map(viewing) ?? Viewing())
    }

    /** What a pane's pictures, and the files it mentions (from where it is), open in: the viewer, its path going into the pane's draft. */
    private func viewing(_ p: Pane) -> Viewing {
        Viewing(
            picture: { id, mime in model.showing = Showing(viewed: .picture(pane: p.id, id: id, mime: mime), pane: nil) },
            path: session.canFiles ? { path in model.showing = Showing(viewed: .file(path: ImagePaths.resolve(path, in: p.path)), pane: p.id) } : nil
        )
    }

    private func composer(pane: Pane, actions: PaneActions, mobile: Bool, activity: Activity?, bar: KeyBar, connected: Bool) -> some View {
        // A menu the program's waiting on would take what's typed as picks, and Enter as its choice: what's sent waits, unless it's for writing in.
        // So would what it shows aside of its line, whose keys are its own.
        let holding = mobile && screen.aside != nil ||
            mobile && activity == .waiting && (screen.prompt.map { p in !p.options.contains { $0.current && $0.muted && $0.scroll == 0 } } ?? false)
        let profile = bar.profile(for: pane.command, program: pane.program)
        return Composer(
            text: Binding(get: { model.drafts.text(pane.id) }, set: { model.drafts.set(pane.id, $0) }),
            program: pane.command,
            keys: profile.keys,
            connected: connected,
            line: seeker.seeking == nil ? screen.line : nil, // what's typed to ask a message back isn't the user's
            recalled: seeker.recalled,
            uploads: model.uploads.items.filter { $0.pane == pane.id },
            uploadDir: model.uploads.dir,
            thumbnail: { model.uploads.thumbnail($0) },
            onAttach: { model.uploads.add(session, pane: pane.id, $0, drafts: model.drafts) },
            onDismissUpload: { model.uploads.dismiss($0) },
            onClear: actions.clears ? {
                if let line = screen.line { actions.clear(line, unseen: mobile) }
                screen.forgetLine()
            } : nil,
            onKey: press,
            typing: !mobile,
            typingActive: typingActive,
            onHideKeyboard: { handle.hideKeyboard() },
            sticky: sticky,
            sendKeys: actions.sendKeys,
            commands: actions.program.commands,
            onAcceptSuggestion: actions.acceptSuggestion,
            stoppable: actions.busy,
            working: activity.map { $0 == .working } ?? actions.busy,
            queues: activity == .working && actions.program.queues,
            holds: holding,
            typed: typed,
            onSend: { text, enter in
                if holding {
                    haptics.reject()
                    nudged += 1
                    return false
                }
                // The box replaces whatever the program's line holds.
                let known = screen.line
                let sent = actions.send(text, enter: enter, line: known, unseen: mobile)
                if sent && !text.contains("\n") && (known?.text.isEmpty ?? true) { handle.predict(text) }
                return sent
            },
            onEnter: {
                typed = false
                if mobile {
                    actions.enter()
                } else {
                    handle.scrollToBottom()
                    actions.press()
                }
            },
            onStop: {
                typed = false
                actions.stop()
            },
            onEditKeys: onEditKeys,
            pad: pad,
            shortcuts: profile.shortcuts(for: actions.program),
            onShortcut: { key in
                // What a menu's waiting on would take a shortcut's text as picks (a hotkey's letter), and its Enter or shift+tab as an answer.
                if holding {
                    haptics.reject()
                    nudged += 1
                    return
                }
                typed = key.keys.last != "Enter"
                if !mobile { handle.scrollToBottom() }
                actions.shortcut(key, line: screen.line)
            },
            onAddShortcut: onAddShortcut
        )
    }

    private func rehearse(windows: [MuxWindow], window: MuxWindow?, pane: Pane?, actions: PaneActions?, mobile: Bool) {
        let r = model.rehearsal
        if let i = r.arg("tab").flatMap(Int.init), let w = windows.first(where: { $0.index == i }) { session.view(w) }
        if let i = r.arg("pane").flatMap(Int.init), let window, let p = window.panes.first(where: { $0.index == i }) { session.show(window, p) }
        if let t = r.arg("type"), let actions {
            sticky.type(.text(t), returnKeys: actions.returnKeys) { text in
                handle.scrollToBottom()
                if screen.line != nil { handle.predict(text) }
                actions.type(text)
            }
        }
        if let k = r.arg("key"), let actions { actions.keys([k]) }
        if let t = r.arg("send"), let actions { _ = actions.send(t, enter: true, line: screen.line, unseen: mobile) }
        if let t = r.arg("draft"), let pane { model.drafts.set(pane.id, t) }
        if r.arg("overview") != nil { withAnimation(Motion.spatial) { overview = true } }
        if r.arg("nooverview") != nil { withAnimation(Motion.spatial) { overview = false } }
        if let m = r.arg("mobile") { model.settings.mobileView = m == "on" }
        if r.arg("keyboard") != nil { handle.type() }
        if r.arg("nokeyboard") != nil { handle.hideKeyboard() }
        if r.arg("split") != nil, let pane { session.split(pane) }
        if r.arg("newtab") != nil { session.newWindow() }
        if r.arg("arrows") != nil { pad.press(KeyBar.arrows) }
    }

    // MARK: Modes

    /** The modes the pane's program goes round, from the one it's in, as far as they've been seen. */
    private func modes(_ actions: PaneActions?) -> [Mode] {
        guard let mode = screen.mode, let program = actions?.program, program.nextMode != nil else { return [] }
        return Cycles.of(program).from(mode)
    }

    /** A mode picked is stepped to a key at a time, each step as far as the screen shows it went, and no further round than where it started. */
    private func walk(to: String, actions: PaneActions?) async {
        guard let actions, let keys = actions.program.nextMode, let from = screen.mode else { return }
        let cycle = Cycles.of(actions.program)
        var at = from
        for _ in 0..<modeSteps {
            // Only at its line: a menu, or what's aside of it, would take the key as its own (shift+tab allows edits, in some).
            if at.name == to || screen.line == nil || screen.aside != nil { break }
            actions.keys(keys)
            // Not moved: something's over its line, which takes the key.
            guard let next = await moved(from: at) else { break }
            cycle.stepped(from: at, to: next)
            at = next
            if at == from {
                cycle.missed(to) // round, and it wasn't there
                break
            }
            if at.name != to { haptics.repeating() }
        }
        if Task.isCancelled { return }
        if at.name == to { haptics.confirm() } else { haptics.reject() }
    }

    /** The mode once it's moved on from [at]; nil if it hasn't in a while. */
    private func moved(from at: Mode) async -> Mode? {
        let until = Date().addingTimeInterval(modeStepSeconds)
        while Date() < until && !Task.isCancelled {
            if let m = screen.mode, m != at { return m }
            try? await Task.sleep(for: .milliseconds(40))
        }
        return nil
    }

    /** A notification tapped shows its pane. */
    private func open() {
        guard let id = model.opening, let windows = session.state?.windows, let w = windows.first(where: { w in w.panes.contains { $0.id == id } }) else { return }
        model.opening = nil
        session.view(w)
        if w.activePane()?.id != id, let p = w.panes.first(where: { $0.id == id }) { session.show(w, p) }
    }

    // MARK: Closing

    /** Closing panes and windows asks first, unless "don't ask again" said not to; a window's last pane closes the window. */
    private func close(_ w: MuxWindow, _ p: Pane?) {
        let whole = p == nil || w.panes.count == 1
        let ask = whole ? model.settings.askClosingWindow : model.settings.askClosingPane
        if ask {
            closing = (w, p)
        } else {
            kill(w, p, whole: whole)
        }
    }

    private func confirmClose(never: Bool) {
        guard let (w, p) = closing else { return }
        let whole = p == nil || w.panes.count == 1
        if never {
            if whole { model.settings.askClosingWindow = false } else { model.settings.askClosingPane = false }
        }
        kill(w, p, whole: whole)
        closing = nil
    }

    private func kill(_ w: MuxWindow, _ p: Pane?, whole: Bool) {
        haptics.confirm()
        if let p, !whole { session.kill(p) } else { session.kill(w) }
    }

    private var closeTitle: String {
        guard let (w, p) = closing else { return "" }
        return p == nil || w.panes.count == 1 ? "close \(w.name)?" : "close pane \(p!.index)?"
    }

    private var closeAction: String {
        guard let (w, p) = closing else { return "close" }
        return p == nil || w.panes.count == 1 ? "close tab" : "close pane"
    }

    private func closeBody(_ windows: [MuxWindow]) -> String {
        guard let (w, p) = closing else { return "" }
        if p == nil || w.panes.count == 1 {
            return (w.panes.count == 1 ? "Its pane, and whatever runs in it," : "Its \(w.panes.count) panes, and whatever runs in them,") + " will be killed." + (windows.count == 1 ? " It's the last tab, so the session ends too." : "")
        }
        return "\(p!.command) in pane \(p!.index) will be killed."
    }
}

/** How many steps a mode picked is gone round to at most, and how long each may take to show. */
private let modeSteps = 8
private let modeStepSeconds: TimeInterval = 2

/** Keeps the shown pane's screen read. */
private struct Watch: ViewModifier {
    let screen: PaneScreen
    let pane: Pane?
    let terminal: TerminalCore.Terminal?
    let program: any Program
    let asks: Bool

    func body(content: Content) -> some View {
        content
            .onChange(of: pane?.id, initial: true) { _, _ in update() }
            .onChange(of: pane.map { "\($0.command)|\($0.program ?? "")" }) { _, _ in update() } // another program in the pane reads its screen its own way
            .onChange(of: terminal.map(ObjectIdentifier.init), initial: true) { _, _ in update() }
            .onChange(of: asks, initial: true) { _, _ in update() }
            .onChange(of: pane?.activity) { _, _ in screen.reread() }
    }

    private func update() { screen.watch(pane: pane?.id, terminal: terminal, program: program, asks: asks) }
}

import TerminalCore

extension Status {
    var isBlocked: Bool { if case .needsUser = self { return true } else { return false } }
    var isKeyLocked: Bool { self == .needsUser(.keyLocked) }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
