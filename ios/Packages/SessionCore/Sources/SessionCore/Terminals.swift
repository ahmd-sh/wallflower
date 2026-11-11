import Foundation
import TerminalCore
import Wire

/**
 * The terminals of the panes the phone has open. Every pane is kept open
 * and streaming, so switching to one, or seeing them all, needs no round
 * trip: the panes on screen (each window's shown pane) with their
 * scrollback, the rest screen only until they're looked at. Output and
 * repaints arrive on the connection's thread; the rest is the main thread's.
 */
final class Terminals: @unchecked Sendable {
    private let lock = NSLock()
    private var all: [String: Terminal] = [:]
    /** Hears, on the main thread, that the terminals changed (a reset, a pane gone). */
    var changed: (() -> Void)?
    weak var link: Link?

    /** Pane -> history lines it has, and the order they were last viewed, least recent first; what this connection opened. */
    private var opened: [String: Int] = [:]
    private var order: [String] = []
    /** Every pane stopped streaming: the user is away; terminals keep what they show until they open again. */
    private var quiet = false

    var snapshot: [String: Terminal] { lock.withLock { all } }

    /** A new connection opens panes again, from its first state. */
    @MainActor func linked() {
        opened = [:]
        order = []
    }

    @MainActor func quiet(_ on: Bool) {
        quiet = on
        guard on else { return }
        let closing = order
        opened = [:]
        order = []
        for p in closing { link?.send(.close(pane: p)) }
    }

    /**
     * Opens a pane, or reopens it with its scrollback if it only had its
     * screen. [full] panes get scrollback; the others only open while there
     * are fewer than [MAX_OPEN], and the least recently viewed go first.
     * A pane is only counted open once its open is sent.
     */
    @MainActor func open(_ pane: String, viewed: Bool, full: Bool? = nil) {
        let full = full ?? viewed
        let history = full ? DEFAULT_HISTORY : Self.PREVIEW_HISTORY
        guard !quiet else { return }
        let had = opened[pane]
        if had == nil && !full && opened.count >= Self.MAX_OPEN { return }
        if let h = had, h >= history {
            if viewed { touch(pane) }
            return
        }
        guard link?.send(.open(pane: pane, history: history == DEFAULT_HISTORY ? nil : history)) == true else { return }
        opened[pane] = history
        if viewed || had == nil { touch(pane) }
        if opened.count > Self.MAX_OPEN, let evict = order.first {
            order.removeFirst()
            opened[evict] = nil
            link?.send(.close(pane: evict))
            let gone = lock.withLock { all.removeValue(forKey: evict) }
            gone?.close()
            changed?()
        }
    }

    private func touch(_ pane: String) {
        order.removeAll { $0 == pane }
        order.append(pane)
    }

    @MainActor func isOpen(_ pane: String) -> Bool { opened[pane] != nil }

    /**
     * [pane]'s screen once its output settles, opening it for the while if
     * it isn't streaming (the user's away); nil if it can't be had.
     */
    @MainActor func peek(_ pane: String) async -> [UInt8]? {
        let before = snapshot[pane]
        let opening = opened[pane] == nil
        if opening && link?.send(.open(pane: pane, history: Self.PREVIEW_HISTORY)) != true { return nil }
        defer {
            // Unless it's been opened since, as the user came back.
            if opening && opened[pane] == nil { link?.send(.close(pane: pane)) }
        }
        var term = opening ? nil : before
        let start = Date()
        while term == nil {
            if Date().timeIntervalSince(start) > Self.PEEK_OPEN { return nil }
            try? await Task.sleep(for: .milliseconds(50))
            if let t = snapshot[pane], t !== before { term = t }
        }
        guard let t = term else { return nil }
        let last = LastOutput()
        let owner = NSObject()
        t.addListener(owner) { last.at = Date() }
        defer { t.removeListener(owner) }
        let settling = Date()
        // Opened for this, its repaint comes first; then, either way, until it's been quiet a moment.
        while opening && last.at == nil && Date().timeIntervalSince(settling) < Self.PEEK_SETTLE_MAX {
            try? await Task.sleep(for: .milliseconds(25))
        }
        while Date().timeIntervalSince(settling) < Self.PEEK_SETTLE_MAX {
            if Date().timeIntervalSince(last.at ?? settling) > Self.PEEK_QUIET { break }
            try? await Task.sleep(for: .milliseconds(25))
        }
        return t.screenBytes()
    }

    private final class LastOutput: @unchecked Sendable {
        var at: Date?
    }

    /** The gateway repaints a pane from scratch, in a new terminal. Any thread. */
    func reset(_ m: Reset) {
        let term = Terminal(cols: m.cols, rows: m.rows)
        let old = lock.withLock { () -> Terminal? in
            let o = all[m.pane]
            all[m.pane] = term
            return o
        }
        old?.close()
        DispatchQueue.main.async { [weak self] in self?.changed?() }
    }

    /** Any thread. */
    func write(_ pane: String, _ bytes: Data) {
        let t = lock.withLock { all[pane] }
        t?.write(bytes)
    }

    /** Panes that no longer exist close quietly; the gateway forgot them already. */
    @MainActor func keep(_ live: Set<String>) {
        for p in opened.keys where !live.contains(p) { opened[p] = nil }
        order.removeAll { !live.contains($0) }
        let gone = lock.withLock { () -> [Terminal] in
            let g = all.filter { !live.contains($0.key) }
            for k in g.keys { all[k] = nil }
            return Array(g.values)
        }
        guard !gone.isEmpty else { return }
        for t in gone { t.close() }
        changed?()
    }

    static let MAX_OPEN = 64
    static let PREVIEW_HISTORY = 1
    static let PEEK_OPEN: TimeInterval = 3
    static let PEEK_QUIET: TimeInterval = 0.25
    static let PEEK_SETTLE_MAX: TimeInterval = 2
}
