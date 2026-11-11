import CTerminal
import Foundation

/**
 * One pane's terminal, backed by libghostty-vt. Pane output is written from
 * the connection's thread, and listeners hear of it there; the renderer
 * pulls frames on the main thread.
 */
public final class Terminal: @unchecked Sendable {
    private let lock = NSLock() // close vs. everything else
    private var handle: OpaquePointer?
    private let listenersLock = NSLock()
    private var listeners: [ObjectIdentifier: () -> Void] = [:]
    private var lastReader: ObjectIdentifier?

    public init(cols: Int, rows: Int, scrollback: Int = 10_000) {
        handle = wf_term_new(Int32(cols), Int32(rows), Int32(scrollback))
        precondition(handle != nil, "libghostty-vt: could not create a terminal")
    }

    deinit { close() }

    /** [listener] runs, on the writing thread, after output changes the terminal; keyed by [owner]. */
    public func addListener(_ owner: AnyObject, _ listener: @escaping () -> Void) {
        listenersLock.lock()
        listeners[ObjectIdentifier(owner)] = listener
        listenersLock.unlock()
    }

    public func removeListener(_ owner: AnyObject) {
        listenersLock.lock()
        listeners[ObjectIdentifier(owner)] = nil
        listenersLock.unlock()
    }

    public func write(_ bytes: Data) {
        guard !bytes.isEmpty else { return }
        lock.lock()
        if let h = handle {
            bytes.withUnsafeBytes { raw in
                wf_term_write(h, raw.bindMemory(to: UInt8.self).baseAddress, raw.count)
            }
        }
        lock.unlock()
        listenersLock.lock()
        let all = Array(listeners.values)
        listenersLock.unlock()
        for l in all { l() }
    }

    public func resize(cols: Int, rows: Int, cellWidth: Int = 0, cellHeight: Int = 0) {
        locked { wf_term_resize($0, Int32(cols), Int32(rows), Int32(cellWidth), Int32(cellHeight)) }
    }

    /** Scrolls the viewport by [rows]; negative is towards history. */
    public func scrollBy(_ rows: Int) { locked { wf_term_scroll($0, 0, Int32(rows)) } }
    public func scrollToTop() { locked { wf_term_scroll($0, 1, 0) } }
    public func scrollToBottom() { locked { wf_term_scroll($0, 2, 0) } }

    /** How a scroll gesture reaches this terminal. */
    public enum Wheel: Equatable {
        /** The primary screen: scroll the local scrollback. */
        case local
        /** A full-screen app tracking the mouse: send it these bytes. */
        case mouse(Data)
        /** A full-screen app that doesn't track the mouse: send arrow keys. */
        case arrows
    }

    /** A wheel notch at a cell. */
    public func wheel(up: Bool, col: Int, row: Int) -> Wheel {
        lock.lock()
        defer { lock.unlock() }
        guard let h = handle else { return .local }
        var out = [UInt8](repeating: 0, count: 64)
        let n = wf_term_wheel(h, up, Int32(col), Int32(row), &out)
        switch n {
        case -1: return .local
        case 0: return .arrows
        default: return .mouse(Data(out[0..<Int(n)]))
        }
    }

    /** The active screen as it stands, in the layout Screen.parse reads; nil if the terminal is closed. */
    public func screenBytes() -> [UInt8]? {
        lock.lock()
        defer { lock.unlock() }
        guard let h = handle else { return nil }
        var len = 0
        guard let p = wf_term_screen(h, &len) else { return nil }
        defer { free(p) }
        return Array(UnsafeBufferPointer(start: p, count: len))
    }

    public func setTheme(_ theme: Theme) {
        locked { h in
            theme.palette.withUnsafeBufferPointer { p in
                wf_term_set_colors(h, theme.foreground, theme.background, theme.cursor, p.baseAddress, Int32(p.count))
            }
        }
    }

    /**
     * Packs what changed since the last call (everything if [force]) into
     * [into]. Returns false if there is nothing to draw, including while the
     * app holds a synchronized update. Changes are taken once, so a [reader]
     * other than the last one gets everything: two views can show one pane.
     */
    public func frame(into: FrameBuffer, force: Bool = false, reader: AnyObject? = nil) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let h = handle else { return false }
        let id = reader.map(ObjectIdentifier.init)
        let full = force || id != lastReader
        var n = into.pack { buf, cap in wf_term_frame(h, buf, cap, full) }
        if n < 0 {
            into.grow(bytes: Int(-n)) // nothing was consumed; the retry packs the same changes
            n = into.pack { buf, cap in wf_term_frame(h, buf, cap, full) }
        }
        into.load(bytes: max(Int(n), 0))
        if n > 0 { lastReader = id }
        return n > 0
    }

    public func close() {
        lock.lock()
        if let h = handle { wf_term_free(h) }
        handle = nil
        lock.unlock()
    }

    private func locked(_ body: (OpaquePointer) -> Void) {
        lock.lock()
        if let h = handle { body(h) }
        lock.unlock()
    }
}
