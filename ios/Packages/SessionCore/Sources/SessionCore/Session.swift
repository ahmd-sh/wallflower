import Foundation
import Observation
import ReadModel
import ScreenReading
import TerminalCore
import Wire

/** Where the phone is: a session ([session], its id) in a multiplexer ([mux], by name). */
public struct Place: Equatable, Sendable {
    public let mux: String
    public let session: String

    public init(mux: String, session: String) {
        self.mux = mux
        self.session = session
    }
}

/** A pane shown instead of its window's active one, until the session's active pane is no longer [active]. */
public struct PaneChoice: Equatable, Sendable {
    public let pane: String
    public let active: String?
}

/** Composed text the gateway never confirmed, so it may not have reached the pane. */
public struct Undelivered: Sendable {
    public let pane: String
    public let text: String
}

/**
 * Messages a rewind or stop took back, by entry id, kept past the app's
 * life: the program records it only with the next message, so a view read
 * afresh would show them again.
 */
public protocol Hidden: AnyObject {
    func all() -> Set<String>
    func add(_ id: String)
    func remove(_ id: String)
}

/** Kept in the app's defaults, the latest few. */
public final class KeptHidden: Hidden {
    private let defaults: UserDefaults
    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }
    private var ids: [String] { defaults.stringArray(forKey: "hidden") ?? [] }
    public func all() -> Set<String> { Set(ids) }
    public func add(_ id: String) { defaults.set(Array((ids.filter { $0 != id } + [id]).suffix(20)), forKey: "hidden") }
    public func remove(_ id: String) { defaults.set(ids.filter { $0 != id }, forKey: "hidden") }
}

public extension MuxWindow {
    func activePane() -> Pane? { panes.first { $0.active } }

    /** The pane the phone shows for a window: one picked on the phone, until the Mac moves on. */
    func shownPane(_ chosen: [String: PaneChoice]) -> Pane? {
        let active = activePane()
        if let c = chosen[id], c.active == active?.id, let p = panes.first(where: { $0.id == c.pane }) { return p }
        return active
    }

    /** A window's name, unless the multiplexer named it after its pane's command: then what the pane's program has it called, if it says. */
    func label() -> String {
        guard let p = panes.first(where: { $0.active }), name == p.command else { return name }
        return Programs.of(p.program).label(p.title) ?? name
    }
}

/**
 * The phone's connection to the Mac's gateway, over one [Link] at a time,
 * reconnecting until [stop], and the session it's in ([place]), in any of
 * the Mac's multiplexers, or none: then it's on the Mac's sessions
 * ([sessions]), from which it goes into one ([switchTo], [newSession]). It
 * leaves one only when the user does ([detach]), or the session goes: a
 * new connection, after a network change or with the app back, goes back
 * into the one it was in. Its state arrives pushed and is kept here, with
 * the panes' terminals and mobile views; presentation is local, and what
 * the user does goes out as intents. While the user is [away], it stays
 * connected for what the Mac's programs notify, and otherwise out of the way.
 */
@MainActor
@Observable
public final class Session {
    @ObservationIgnored private let link: Link
    @ObservationIgnored private let panes = Terminals()
    @ObservationIgnored private let transfer = Transfer()
    @ObservationIgnored private let files = Files()
    @ObservationIgnored private let hidden: Hidden?
    @ObservationIgnored private let fast: Fast

    public private(set) var status: Status = .connecting
    public private(set) var state: SessionState?
    /** The window on screen; local, so it changes the moment a tab is tapped. */
    public private(set) var viewing: String?
    /** Panes picked on the phone, by window; local, so a swipe shows the pane at once. */
    public private(set) var chosen: [String: PaneChoice] = [:]
    /** Terminals of open panes, replaced on every reset. */
    public private(set) var terminals: [String: Terminal] = [:]
    /** Mobile views of the panes [read] asked for, as they arrive. */
    public private(set) var readings: [String: Reading] = [:]
    /** The multiplexer the session is in, as the gateway last said; nil while it's in none. */
    public private(set) var mux: Mux?
    /**
     * The session the phone is in, or going into; nil while it's in none.
     * What the user picks is where it is at once; what the gateway says it's
     * attached to moves it only once the gateway has caught up with what the
     * user asked.
     */
    public private(set) var place: Place?
    /** The session a new connection goes back into; none once the user leaves for none. */
    public private(set) var back: Place? { didSet { if back != oldValue { onBack?(back) } } }
    /** The multiplexer a session's being started in, by name, until the phone's in it. */
    public private(set) var starting: String?
    /** Why the phone's in no session, when it didn't choose that. */
    public private(set) var left: String?
    /** The sessions in every multiplexer on the Mac, as last listed. */
    public private(set) var sessions: SessionList?
    /** The Mac's name, as the gateway last said. */
    public private(set) var machine: String? { didSet { if let m = machine, m != oldValue { onMachine?(m) } } }
    /** Where the gateway saves uploads: paths under it in what's sent are uploads. */
    public private(set) var uploadDir: String?
    /** The gateway shows the Mac's files ([list], [fetch]), as it last said. */
    public private(set) var canFiles = false
    /** The terminal's size, in cells, as the gateway was last told it. */
    public private(set) var size: (cols: Int, rows: Int)

    /** Desktop notifications the Mac's programs send, from any pane. */
    @ObservationIgnored public var onNotification: ((ProgramNotification) -> Void)?
    /** Text the gateway never confirmed comes back. */
    @ObservationIgnored public var onUndelivered: ((Undelivered) -> Void)?
    @ObservationIgnored public var onBack: ((Place?) -> Void)?
    @ObservationIgnored public var onMachine: ((String) -> Void)?
    @ObservationIgnored public var onSize: ((Int, Int) -> Void)?

    private enum Attachment: Equatable {
        case unknown
        case nowhere
        case at(Place)
    }
    @ObservationIgnored private var attachment: Attachment = .unknown
    /** The user's latest ask of where to be, until the gateway's caught up with it. */
    @ObservationIgnored private var asked: Int?
    @ObservationIgnored private var asks = 0
    /** This connection's had the phone in a session, or in none: another means leaving that one. */
    @ObservationIgnored private var placed = false
    @ObservationIgnored private var grid: (cols: Int, rows: Int)
    @ObservationIgnored private var started = false
    @ObservationIgnored private var resizing: Task<Void, Never>?
    @ObservationIgnored private var away = false
    @ObservationIgnored private var quieting: Task<Void, Never>?

    // Workspace.
    @ObservationIgnored private var awaitingWindow: Set<String>?
    @ObservationIgnored private var synced = false

    // Input.
    @ObservationIgnored private var pending: [(ping: Int, pane: String, text: String)] = []

    // Mobile views.
    @ObservationIgnored private var wanted: Set<String> = []
    @ObservationIgnored private var readsQuiet = false
    @ObservationIgnored private var readsAsked: Set<String> = []

    public init(transport: Transport, mux: String? = nil, session: String? = nil, hidden: Hidden? = nil, grid: (Int, Int) = (80, 24)) {
        fast = Fast()
        link = Link(transport: transport, fast: fast)
        self.hidden = hidden
        self.grid = (grid.0, grid.1)
        size = (grid.0, grid.1)
        let p = (mux != nil && session != nil) ? Place(mux: mux!, session: session!) : nil
        place = p
        back = p
        fast.panes = panes
        fast.transfer = transfer
        fast.fetches = files.fetches
        panes.link = link
        transfer.link = link
        files.link = link
        link.events = self
        link.onStatus = { [weak self] s in self?.status = s }
        panes.changed = { [weak self] in
            guard let self else { return }
            self.terminals = self.panes.snapshot
        }
    }

    /** Connects, saying the phone's size; [resize] says when it changes. */
    public func start(cols: Int, rows: Int) {
        guard !started else { return }
        started = true
        grid = (cols, rows)
        size = grid
        link.start()
    }

    /** Connects at the size last measured, before the terminal's on screen to measure it. */
    public func start() { start(cols: grid.cols, rows: grid.rows) }

    public func stop() {
        started = false
        link.stop()
    }

    /** Tries again now: after the user dealt with a [Status.needsUser], or rather than wait for the next try. */
    public func retry() { link.retry() }

    /**
     * The app is back in front: a connection that went quiet in the
     * background is checked now instead of at the next heartbeat, a waiting
     * reconnect goes right away, and the window on screen is the phone's
     * size again.
     */
    public func probe() {
        if link.linked {
            link.ping(timeout: Self.PROBE_TIMEOUT)
            reclaim()
        } else if case .disconnected = status {
            link.retry()
        }
    }

    /**
     * The user left the app ([on]) or came back. Away, the phone stays out
     * of sizing the Mac's windows at once, and after [QUIET_AFTER] stops
     * its panes and mobile views streaming, keeping state and notifications
     * coming. Back, they stream again, and the phone takes the window it shows.
     */
    public func setAway(_ on: Bool) {
        guard away != on else { return }
        away = on
        link.send(.away(on: on))
        quieting?.cancel()
        if on {
            quieting = Task { [weak self] in
                try? await Task.sleep(for: .seconds(Self.QUIET_AFTER))
                guard !Task.isCancelled else { return }
                self?.quiet(true)
            }
        } else {
            quiet(false)
            probe()
        }
    }

    private func quiet(_ on: Bool) {
        panes.quiet(on)
        readsQuiet = on
        askReads()
        if !on, let s = state { open(s) }
    }

    /** [pane]'s screen as it settles, streamed for the while if it isn't. */
    public func peek(_ pane: String) async -> Screen? {
        guard let bytes = await panes.peek(pane) else { return nil }
        return Screen.parse(bytes)
    }

    /** The pane the phone shows, if any. */
    public func shownPane() -> String? {
        state?.windows.first { $0.id == viewing }?.shownPane(chosen)?.id
    }

    /** Resizes once the size settles (the keyboard animates through every size between), or at once if it's known [ahead]. */
    public func resize(cols: Int, rows: Int, ahead: Bool = false) {
        guard grid != (cols, rows) else { return }
        grid = (cols, rows)
        resizing?.cancel()
        resizing = Task { [weak self] in
            if !ahead { try? await Task.sleep(for: .seconds(Self.RESIZE_SETTLE)) }
            guard !Task.isCancelled, let self else { return }
            self.link.send(.resize(cols: cols, rows: rows))
            self.size = (cols, rows)
            self.onSize?(cols, rows)
        }
    }

    // MARK: Workspace

    private func stateArrived(_ m: SessionState) {
        state = m
        // A pane picked on the phone gives way once the session's active pane moves on (to it, or elsewhere).
        chosen = chosen.filter { w, c in m.windows.first { $0.id == w }?.activePane()?.id == c.active }
        if !m.windows.contains(where: { $0.id == viewing }) { viewing = m.windows.first { $0.active }?.id }
        if let known = awaitingWindow, let w = m.windows.first(where: { !known.contains($0.id) }) {
            viewing = w.id
            awaitingWindow = nil
        }
        // What's shown is the phone's choice, and its pane the whole of the phone, though the Mac's layout came back while it was gone.
        if !synced {
            synced = true
            if !away, let v = viewing { link.send(.select(window: v, pane: nil)) }
        }
        open(m)
        panes.keep(Set(m.windows.flatMap { $0.panes.map(\.id) }))
    }

    /** The session's gone (the phone left it, or it went): nothing of it stays. */
    private func clearWorkspace() {
        state = nil
        viewing = nil
        chosen = [:]
        awaitingWindow = nil
        panes.keep([])
    }

    private func open(_ m: SessionState) {
        if let p = m.windows.first(where: { $0.id == viewing })?.shownPane(chosen) { panes.open(p.id, viewed: true) }
        for w in m.windows { if let p = w.shownPane(chosen) { panes.open(p.id, viewed: false, full: true) } }
        for w in m.windows { for p in w.panes.sorted(by: { $0.index < $1.index }) { panes.open(p.id, viewed: false) } }
    }

    /** Shows a window now; the Mac follows in the background (it sizes the window for the phone). */
    public func view(_ window: MuxWindow) {
        viewing = window.id
        if let p = window.activePane() { panes.open(p.id, viewed: true) }
        link.send(.select(window: window.id, pane: nil))
    }

    /** Shows a pane of a window now, and has the Mac focus it in the background, zoomed so it gets the whole screen. */
    public func show(_ window: MuxWindow, _ pane: Pane) {
        chosen[window.id] = PaneChoice(pane: pane.id, active: window.activePane()?.id)
        panes.open(pane.id, viewed: true)
        link.send(.focus(pane: pane.id))
    }

    /** The desktop may have taken the window over; showing it again takes it back. */
    private func reclaim() {
        if let v = viewing { link.send(.select(window: v, pane: nil)) }
    }

    /** Opens a window, which the phone then shows; the desktop stays where it is. */
    public func newWindow() {
        awaitingWindow = Set(state?.windows.map(\.id) ?? [])
        link.send(.new)
    }

    /** Opens a pane beside [pane], which the Mac focuses. */
    public func split(_ pane: Pane) { link.send(.split(pane: pane.id)) }
    public func kill(_ window: MuxWindow) { link.send(.kill(window: window.id, pane: nil)) }
    public func kill(_ pane: Pane) { link.send(.kill(window: nil, pane: pane.id)) }
    public func rename(_ window: MuxWindow, _ name: String) { link.send(.rename(window: window.id, name: name)) }

    // MARK: Sessions

    /** Asks for the sessions in every multiplexer, which come in [sessions]. */
    public func listSessions() { link.send(.sessions) }

    /** Goes into a session, over this link: the phone's there at once, its state once the gateway's attached. */
    public func switchTo(mux: String, session: String) {
        ask(.switchTo(mux: mux, session: session)) {
            self.place = Place(mux: mux, session: session)
            self.back = self.place
            self.left = nil
        }
    }

    /** Starts a session, named [name] or by the multiplexer, and goes into it once it's there ([starting] meanwhile). */
    public func newSession(mux: String, name: String?) {
        let n = name?.trimmingCharacters(in: .whitespaces)
        ask(.newSession(mux: mux, name: (n?.isEmpty ?? true) ? nil : n)) {
            self.starting = mux
            self.left = nil
        }
    }

    /** Leaves the session for none, as the user chose: nothing of it stays, and a new connection doesn't go back into it. */
    public func detach() {
        ask(.detach) {
            self.place = nil
            self.back = nil
            self.left = nil
        }
        clearWorkspace()
        readings = [:]
    }

    public func renameSession(mux: String, session: String, name: String) { link.send(.renameSession(mux: mux, session: session, name: name)) }

    /** Ends a session and its programs; not the one the phone is in. */
    public func killSession(mux: String, session: String) { link.send(.killSession(mux: mux, session: session)) }

    /**
     * Does what the user asked of where to be ([change], at once), and
     * sends it, then a ping: its pong says the gateway's done it, and said
     * where that left the phone. Not connected, nothing's sent: a new
     * connection's hello asks for where they mean to be.
     */
    private func ask(_ m: ClientMessage, _ change: () -> Void) {
        asks += 1
        let id = asks
        asked = id
        change()
        guard let on = link.current(), link.send(m, on: on) else {
            if asked == id { asked = nil }
            starting = nil
            return
        }
        link.ping(timeout: Self.PROBE_TIMEOUT, on: on) { [weak self] arrived in
            guard let self, arrived, self.asked == id else { return }
            self.asked = nil
            self.starting = nil // there, or it couldn't be
            self.settle()
        }
    }

    /** With nothing the user asked still on its way, the phone's where the gateway has it; a session that couldn't be had is still the one to go back into. */
    private func settle() {
        guard asked == nil else { return }
        switch attachment {
        case .unknown: break
        case .nowhere: place = nil
        case .at(let p):
            place = p
            back = p
        }
    }

    /** What was kept of the session the phone was in goes, as for a new connection, if this one's had it in one. */
    private func leave() {
        if placed {
            panes.linked()
            synced = false
            readsAsked = []
        }
        placed = true
    }

    // MARK: Mobile views

    /** Has the gateway send these panes' mobile views, and no others'. */
    public func read(_ panes: Set<String>) {
        wanted = panes
        askReads()
        if !readings.keys.allSatisfy(panes.contains) { readings = readings.filter { panes.contains($0.key) } }
    }

    private func askReads() {
        let panes = readsQuiet ? [] : wanted
        guard link.linked else { return }
        for p in readsAsked.subtracting(panes) { link.send(.read(pane: p, on: false)) }
        for p in panes.subtracting(readsAsked) { link.send(.read(pane: p, on: true)) }
        readsAsked = panes
    }

    private func entries(_ m: Entries) {
        guard wanted.contains(m.pane) else { return }
        let kept = m.reset ? (hidden?.all() ?? []) : []
        let was = readings[m.pane] ?? Reading()
        var r = was.apply(m)
        if r.rewound == nil && !kept.isEmpty {
            r.rewound = r.entries.first { kept.contains($0.id) && r.takesBack($0.id) }?.id
        }
        readings[m.pane] = r
        // The program has recorded more past it: nothing to keep hidden.
        if let ended = was.rewound, r.rewound != ended { hidden?.remove(ended) }
    }

    /** [pane]'s conversation was rewound to before the user's message [said] matches. */
    public func rewound(_ pane: String, said: @escaping (String) -> Bool, held: String? = nil) {
        change(pane) { $0.rewoundTo(said, held: held) }
    }

    /** The user stopped [pane]'s program, which may take their latest message back. */
    public func stopped(_ pane: String) {
        change(pane) { r in
            var r = r
            r.stopped = Self.millis()
            return r
        }
    }

    /** [pane]'s program has put the user's latest message back in its input [line], if that's what it holds just after a stop. */
    public func takenBack(_ pane: String, line: String?) {
        change(pane) { $0.takenBack(line, now: Self.millis()) }
    }

    private func change(_ pane: String, _ how: (Reading) -> Reading) {
        guard let was = readings[pane] else { return }
        let now = how(was)
        readings[pane] = now
        if let took = now.rewound, took != was.rewound { hidden?.add(took) }
    }

    // MARK: Input

    /** Keys by wallflower's names (Enter, C-c); with [bindings], through the multiplexer's key bindings. */
    public func keys(_ pane: String, _ keys: [String], bindings: Bool = false) {
        link.send(.keys(pane: pane, keys: keys, bindings: bindings))
    }

    /** Bytes typed as they are, as a mouse wheel's. */
    public func bytes(_ pane: String, _ bytes: Data) { link.sendData(pane, bytes) }

    /**
     * Sends composed text in place of the program's input line, emptied first
     * by [clear] keys, then Enter, unless not [enter]; the gateway types it
     * as the program takes it best. Returns false, sending nothing, while not
     * connected. Text the gateway doesn't confirm before the connection drops
     * comes back through [onUndelivered].
     */
    @discardableResult
    public func submit(_ pane: String, _ text: String, enter: Bool = true, clear: [String] = []) -> Bool {
        guard link.up, link.send(.submit(pane: pane, text: text, enter: enter, clear: clear)) else { return false }
        let id = link.ping(timeout: Link.pongTimeout)
        pending.append((id, pane, text))
        return true
    }

    private func arrived(_ id: Int) { pending.removeAll { $0.ping <= id } }

    /** The connection's gone, or the session, and with it whatever it didn't confirm. */
    private func undelivered() {
        for p in pending { onUndelivered?(Undelivered(pane: p.pane, text: p.text)) }
        pending = []
    }

    // MARK: Transfer

    /** A picture entry of [pane]'s mobile view, its bytes asked of the gateway; nil if they're gone. */
    public func picture(pane: String, id: String) async -> Data? { await transfer.picture(pane: pane, id: id) }

    /** Sends [data] to the Mac as [name]; returns where it was saved. */
    public func upload(id: String, name: String, data: Data, progress: @escaping (Int) -> Void) async throws -> String {
        try await transfer.upload(id: id, name: name, data: data, progress: progress)
    }

    // MARK: Files

    /** A directory of the Mac's (absolute, or from ~), its entries by name; throws why it couldn't be read. */
    public func list(_ path: String) async throws -> Listing { try await files.list(path) }

    /**
     * A file of the Mac's, all of it or its first [limit] bytes, written to
     * [to] as it comes, with [progress] in bytes so far of how many; what the
     * gateway said of it. Cancelled, it stops coming.
     */
    public func fetch(_ path: String, limit: Int64? = nil, to url: URL, progress: @escaping @MainActor (Int64, Int64) -> Void = { _, _ in }) async throws -> FileReply {
        try await files.fetch(path, limit: limit, to: url, progress: progress)
    }

    static func millis() -> Int { Int(Date().timeIntervalSince1970 * 1000) }

    static let PROBE_TIMEOUT: TimeInterval = 5
    static let RESIZE_SETTLE: TimeInterval = 0.12
    static let QUIET_AFTER: TimeInterval = 60
}

extension Session: Link.Events {
    func hello() -> ClientHello {
        ClientHello(cols: grid.cols, rows: grid.rows, mux: back?.mux, session: back?.session, away: away)
    }

    func linked() {
        panes.linked()
        synced = false
        readsAsked = []
        placed = false
        attachment = .unknown
        asked = nil // the new connection's hello asks for where the user means to be
        starting = nil
    }

    func up() { askReads() }

    func message(_ m: ServerMessage) {
        switch m {
        case .hello(let h):
            uploadDir = h.uploads
            canFiles = h.can.contains(ServerHello.FILES)
            if let name = h.machine { machine = name }
            Programs.supply(Dictionary(h.programs.map { ($0.id, $0.program()) }, uniquingKeysWith: { a, _ in a }))
        case .attached(let a):
            leave()
            mux = a.mux
            attachment = .unknown // until its state says which session
            starting = nil
        case .detached(let d):
            leave()
            mux = nil
            clearWorkspace()
            readings = [:]
            // The session went under what was sent to it: what it didn't confirm comes back.
            if d.reason != nil { undelivered() }
            attachment = .nowhere
            if let r = d.reason { left = r }
            settle()
        case .state(let s):
            stateArrived(s)
            if let m = mux { attachment = .at(Place(mux: m.name, session: s.session)) }
            settle()
        case .sessions(let l):
            sessions = l
        case .reset:
            terminals = panes.snapshot
        case .entries(let e):
            entries(e)
        case .pong(let p):
            arrived(p.id)
        case .uploaded(let u):
            transfer.uploaded(u)
        case .listing(let l):
            files.listing(l)
        case .notification(let n):
            onNotification?(n)
        default:
            break
        }
    }

    func down() { undelivered() }
}

/** The connection thread's way in: pane output and repaints to the terminals, pictures to their askers, fetched files to theirs. */
private final class Fast: FastPath, @unchecked Sendable {
    var panes: Terminals?
    var transfer: Transfer?
    var fetches: Fetches?

    func data(_ pane: String, _ bytes: Data) { panes?.write(pane, bytes) }
    func reset(_ m: Reset) { panes?.reset(m) }
    func picture(_ id: String, _ bytes: Data) { transfer?.picture(id, bytes) }
    func file(_ m: FileReply) { fetches?.reply(m) }
    func file(_ id: String, _ bytes: Data) -> Int64? { fetches?.bytes(id, bytes) }
}

private extension ProgramData {
    /** A plugin's phone half, as the gateway describes it. */
    func program() -> Program {
        Supplied(
            keyBar: keys.map { KeyDefaults(name: $0.name, keys: $0.keys, stop: $0.stop ?? ["C-c"]) },
            commands: commands.map { Command(text: $0.text, about: $0.about) },
            newlineKeys: newline,
            clearKeys: clear ?? [],
            shortcuts: shortcuts.map { Shortcut($0.keys, label: $0.label) }
        )
    }
}
