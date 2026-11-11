import Foundation
import os
import Wire

public enum Status: Equatable, Sendable {
    case connecting
    case connected
    /**
     * Retrying on its own: [trying] now, or next at [retryAt]; down since
     * [since], its last try ended for [reason].
     */
    case disconnected(reason: String, since: Date, retryAt: Date, trying: Bool)
    /** Waiting for the user, then [Session.retry]. */
    case needsUser(Blocked)

    public var isConnected: Bool { self == .connected }
    public var isDown: Bool {
        switch self {
        case .connecting, .disconnected: return true
        default: return false
        }
    }
}

let log = Logger(subsystem: "sh.ahmd.wallflower", category: "session")

/** What arrives on a connection's own thread and can't wait for the main one: pane output, repaints, pictures. */
protocol FastPath: AnyObject, Sendable {
    func data(_ pane: String, _ bytes: Data)
    func reset(_ m: Reset)
    func picture(_ id: String, _ bytes: Data)
}

/**
 * The link to the gateway: one connection at a time, made again whenever it
 * drops, backing off, until [stop]. Messages go out in order on the live
 * connection; with none live, nothing goes. Pings check it's alive, and
 * mark what has arrived: a pong means everything sent before its ping
 * reached the multiplexer.
 *
 * What a connection keeps lives in one [Live], thrown away whole when it
 * ends. What the rest of the session keeps about a connection is reset in
 * [Events.linked], with the new one not yet carrying anything, so nothing
 * sent on it is from the last.
 */
@MainActor
final class Link {
    protocol Events: AnyObject {
        /** The first message, saying the phone's size. */
        func hello() -> ClientHello
        /** A connection is about to carry messages: forget what was kept of the last. */
        func linked()
        /** It does now: send what a new connection needs. */
        func up()
        func message(_ m: ServerMessage)
        /** It's gone. */
        func down()
    }

    private let transport: Transport
    private let fast: FastPath
    weak var events: Events?
    var onStatus: ((Status) -> Void)?

    private(set) var status: Status = .connecting {
        didSet { if status != oldValue { onStatus?(status) } }
    }

    private var live: Live?
    private var task: Task<Void, Never>?
    private var pings = 0
    private var retryAsked = false
    private var waiting: (id: Int, resume: CheckedContinuation<Void, Never>)?
    private var waits = 0

    init(transport: Transport, fast: FastPath) {
        self.transport = transport
        self.fast = fast
    }

    /** A connection is live, though the gateway may not have said hello yet. */
    var linked: Bool { live != nil }

    /** Connected, and the gateway said hello. */
    var up: Bool { live != nil && status == .connected }

    func start() {
        guard task == nil else { return }
        task = Task { [weak self] in await self?.run() }
    }

    func stop() {
        task?.cancel()
        task = nil
        live?.conn?.close()
        wake()
    }

    /** Tries again now: after the user dealt with a [Status.needsUser], or while it waits to. */
    func retry() {
        if !wake() { retryAsked = true }
    }

    @discardableResult
    private func wake() -> Bool {
        guard let w = waiting else { return false }
        waiting = nil
        w.resume.resume()
        return true
    }

    /** Until [retry], or [seconds] pass if given. */
    private func pause(_ seconds: Double?) async {
        if Task.isCancelled { return }
        if retryAsked {
            retryAsked = false
            return
        }
        waits += 1
        let id = waits
        await withCheckedContinuation { c in
            waiting = (id, c)
            if let s = seconds {
                Task { [weak self] in
                    try? await Task.sleep(for: .seconds(s))
                    guard let self, let w = self.waiting, w.id == id else { return }
                    self.waiting = nil
                    w.resume.resume()
                }
            }
        }
    }

    private func run() async {
        var backoff = 0.5
        while !Task.isCancelled {
            if case .disconnected(let r, let since, let at, _) = status {
                status = .disconnected(reason: r, since: since, retryAt: at, trying: true)
            } else {
                status = .connecting
            }
            retryAsked = false // a retry asked for before this attempt is this attempt
            let l = Live(link: self, fast: fast)
            var reason: String
            do {
                reason = try await connect(l)
            } catch let b as Blocked {
                if Task.isCancelled { break }
                status = .needsUser(b)
                await pause(nil)
                continue
            } catch {
                reason = (error as? LocalizedError)?.errorDescription ?? String(describing: error)
            }
            if Task.isCancelled { break }
            if l.greeted { backoff = 0.5 } // it worked a while; a gateway that won't start doesn't get hammered
            let now = Date()
            var since = now
            if case .disconnected(_, let s, _, _) = status { since = s }
            status = .disconnected(reason: reason, since: since, retryAt: now.addingTimeInterval(backoff), trying: false)
            await pause(backoff)
            backoff = min(backoff * 2, 5)
        }
    }

    /** One connection, until it ends; returns why it did. */
    private func connect(_ l: Live) async throws -> String {
        let conn = try await transport.open(l.sink)
        if Task.isCancelled {
            conn.close()
            return "stopped"
        }
        l.conn = conn
        l.sink.attach(conn)
        guard let events else { return "stopped" }
        conn.write(FrameEncoder.message(.hello(events.hello())))
        l.lastPing = Date()
        events.linked()
        live = l
        events.up()
        l.running = true
        let held = l.pending
        l.pending = []
        for m in held { handle(l, m) }
        let heartbeat = Task { [weak self] in await self?.heartbeat(l) }
        let error = await l.waitForEnd()
        heartbeat.cancel()
        if live === l {
            live = nil
            events.down()
        }
        l.finish()
        if let b = l.blocked { throw b }
        if let e = error { throw e }
        return "connection closed"
    }

    /** From the connection's thread, in order. */
    fileprivate func handle(_ l: Live, _ m: ServerMessage) {
        guard !l.ended else { return }
        guard l.running else {
            l.pending.append(m)
            return
        }
        switch m {
        case .hello(let h):
            if h.v != VERSION {
                l.blocked = .outdated(gateway: h.v, app: VERSION)
                l.conn?.close()
                return
            }
            l.greeted = true
            status = .connected
        case .pong(let p):
            // Any pong says the link is alive; only the newest leaves nothing outstanding. (Uploads keep pings in flight.)
            l.deadline = p.id == pings ? nil : Date().addingTimeInterval(Self.pongTimeout)
            for id in l.arrivals.keys.sorted() where id <= p.id {
                l.arrivals.removeValue(forKey: id)?(true)
            }
        case .bye(let b):
            l.blocked = l.greeted ? nil : refusal(b.reason)
            if l.greeted { l.byeReason = b.reason }
            l.conn?.close()
            return
        case .error(let e):
            log.warning("gateway: \(e.message, privacy: .public)")
        default:
            break
        }
        events?.message(m)
    }

    fileprivate func closed(_ l: Live, _ error: Error?) {
        l.end(error)
    }

    /** Why the gateway turned the connection down: a protocol version it doesn't speak says which it does. */
    private func refusal(_ reason: String) -> Blocked {
        if let r = reason.range(of: #"speaks (\d+)"#, options: .regularExpression) {
            let digits = reason[r].filter(\.isNumber)
            if let v = Int(digits) { return .outdated(gateway: v, app: VERSION) }
        }
        return .refused(reason)
    }

    /** Mobile connections die silently; an unanswered ping closes the socket so it reconnects. */
    private func heartbeat(_ l: Live) async {
        while !Task.isCancelled && !l.ended {
            try? await Task.sleep(for: .seconds(Self.tick))
            let now = Date()
            if let d = l.deadline, now > d {
                l.conn?.close()
                return
            }
            if now.timeIntervalSince(l.lastPing) >= Self.heartbeatInterval { ping(timeout: Self.pongTimeout) }
        }
    }

    /**
     * Pings, so the link counts as dead if no pong comes within [timeout];
     * [arrived] hears once one says everything sent before it arrived (true),
     * or the connection ended first (false). Returns its id. With [on], only
     * on that connection.
     */
    @discardableResult
    func ping(timeout: TimeInterval, on: Live? = nil, arrived: ((Bool) -> Void)? = nil) -> Int {
        pings += 1
        let id = pings
        guard let l = current(on), let conn = l.conn else {
            arrived?(false)
            return id
        }
        let now = Date()
        l.lastPing = now
        let d = now.addingTimeInterval(timeout)
        if l.deadline == nil || d < l.deadline! { l.deadline = d }
        if let arrived { l.arrivals[id] = arrived }
        conn.write(FrameEncoder.message(.ping(id: id)))
        return id
    }

    /** Sends [m] on the live connection, or only on [on]; false if there's none. */
    @discardableResult
    func send(_ m: ClientMessage, on: Live? = nil) -> Bool {
        guard let conn = current(on)?.conn else { return false }
        conn.write(FrameEncoder.message(m))
        return true
    }

    /** Types [bytes] into [pane]. */
    @discardableResult
    func sendData(_ pane: String, _ bytes: Data) -> Bool {
        guard let conn = live?.conn else { return false }
        conn.write(FrameEncoder.data(pane: pane, bytes: bytes))
        return true
    }

    /** The next of upload [id]'s bytes, on [on]. */
    @discardableResult
    func sendUpload(_ id: String, _ bytes: Data, on: Live) -> Bool {
        guard let conn = current(on)?.conn else { return false }
        conn.write(FrameEncoder.upload(id: id, bytes: bytes))
        return true
    }

    /** The live connection, which stands for itself: what's sent [on] it goes nowhere else. */
    func current() -> Live? { live }

    private func current(_ on: Live?) -> Live? {
        guard let l = live else { return nil }
        return on == nil || on === l ? l : nil
    }

    static let tick: TimeInterval = 1
    static let heartbeatInterval: TimeInterval = 15
    static let pongTimeout: TimeInterval = 10
}

/** One connection's worth: thrown away whole when it ends. */
@MainActor
final class Live {
    nonisolated let sink: Sink
    var conn: Connection?
    var running = false
    var pending: [ServerMessage] = []
    var greeted = false
    var blocked: Blocked?
    var byeReason: String?
    /** By ping: hears once its pong says everything sent before it arrived. */
    var arrivals: [Int: (Bool) -> Void] = [:]
    var lastPing = Date()
    /** When the connection counts as dead unless a pong arrives. */
    var deadline: Date?
    private(set) var ended = false
    private var endError: Error?
    private var endWaiters: [CheckedContinuation<Error?, Never>] = []
    private var onEnd: [Int: () -> Void] = [:]
    private var ends = 0

    init(link: Link, fast: FastPath) {
        sink = Sink(fast: fast)
        sink.live = self
        sink.link = link
    }

    func waitForEnd() async -> Error? {
        if ended { return endError }
        return await withCheckedContinuation { endWaiters.append($0) }
    }

    /** [action] runs when it ends, or now if it has; [forget] the id it returns once it no longer matters. */
    @discardableResult
    func whenEnded(_ action: @escaping () -> Void) -> Int {
        ends += 1
        if ended { action() } else { onEnd[ends] = action }
        return ends
    }

    func forget(_ id: Int) { onEnd[id] = nil }

    func end(_ error: Error?) {
        guard !ended else { return }
        ended = true
        endError = byeReason.map { GatewayBye(reason: $0) } ?? error
        for w in endWaiters { w.resume(returning: endError) }
        endWaiters = []
    }

    func finish() {
        for (_, a) in arrivals { a(false) }
        arrivals = [:]
        let actions = onEnd.values
        onEnd = [:]
        for a in actions { a() }
    }
}

struct GatewayBye: Error, LocalizedError {
    let reason: String
    var errorDescription: String? { reason }
}

/** A connection's bytes, read into frames on its own thread. */
final class Sink: ConnectionSink, @unchecked Sendable {
    private var decoder = FrameDecoder()
    private let fast: FastPath
    weak var live: Live?
    weak var link: Link?
    private var conn: Connection?
    private var early: [Data] = []
    private let lock = NSLock()

    init(fast: FastPath) {
        self.fast = fast
    }

    func attach(_ conn: Connection) {
        lock.withLock { self.conn = conn }
    }

    func received(_ bytes: Data) {
        lock.lock()
        defer { lock.unlock() }
        decoder.append(bytes)
        do {
            while let f = try decoder.next() {
                switch f {
                case .data(let pane, let b):
                    fast.data(pane, b)
                case .picture(let id, let b):
                    fast.picture(id, b)
                case .message(let m):
                    if case .reset(let r) = m { fast.reset(r) }
                    deliver(m)
                case .unknown(let json, let problem):
                    if let problem { log.warning("unread message (\(problem, privacy: .public)): \(json.prefix(200), privacy: .public)") }
                }
            }
        } catch {
            log.error("bad frame: \(String(describing: error), privacy: .public)")
            conn?.close()
        }
    }

    private func deliver(_ m: ServerMessage) {
        DispatchQueue.main.async { [weak self] in
            MainActor.assumeIsolated {
                guard let self, let live = self.live, let link = self.link else { return }
                link.handle(live, m)
            }
        }
    }

    func closed(_ error: Error?) {
        DispatchQueue.main.async { [weak self] in
            MainActor.assumeIsolated {
                guard let self, let live = self.live, let link = self.link else { return }
                link.closed(live, error)
            }
        }
    }
}
