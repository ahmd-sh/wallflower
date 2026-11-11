import Foundation

/**
 * Text sent to a pane, drawn ahead of its echo. Each grapheme is predicted at
 * the cell it should land in, from the cursor on. A prediction is confirmed
 * when the terminal shows that grapheme in that cell, which happens left to
 * right as the echo streams in; whatever is still unconfirmed when the echo
 * stops coming (a password prompt, an app that doesn't echo) is dropped.
 */
public final class Predictions {
    public struct Cell {
        public let x: Int
        public let y: Int
        public let text: String
        public let width: Int
        let codepoint: UInt32
    }

    /** Unconfirmed predictions, in typing order. */
    public private(set) var cells: [Cell] = []
    private var deadline: TimeInterval = 0

    public init() {}

    public var isEmpty: Bool { cells.isEmpty }

    /** Where the cursor will be once the predictions land. */
    public func cursorAfter(cols: Int) -> (x: Int, y: Int)? {
        guard let last = cells.last else { return nil }
        let x = last.x + last.width
        return x >= cols ? (0, last.y + 1) : (x, last.y)
    }

    /**
     * Predicts [text] typed at the cursor, continuing after any predictions
     * still in flight. Stops at the first control character (Enter, Tab, ...):
     * where those lead is up to the app.
     */
    public func add(_ text: String, cursorX: Int, cursorY: Int, cols: Int, rows: Int, now: TimeInterval) {
        var (x, y) = cursorAfter(cols: cols) ?? (cursorX, cursorY)
        for g in text {
            let s = String(g)
            guard let cp = s.unicodeScalars.first?.value else { continue }
            if cp < 0x20 || cp == 0x7F { break }
            let w = Self.width(s)
            if x + w > cols {
                x = 0
                y += 1
            }
            if y >= rows { break } // would scroll; the echo will show it
            cells.append(Cell(x: x, y: y, text: s, width: w, codepoint: cp))
            x += w
        }
        deadline = now + Self.timeout
    }

    /**
     * Drops predictions the terminal now shows, given its cells' first
     * codepoints. Returns true if anything changed. Progress keeps the rest
     * alive; a stalled echo expires them.
     */
    public func confirm(cellAt: (Int, Int) -> UInt32, now: TimeInterval) -> Bool {
        let before = cells.count
        cells.removeAll { cellAt($0.x, $0.y) == $0.codepoint }
        if cells.count != before { deadline = now + Self.timeout }
        return cells.count != before || expire(now: now)
    }

    /** Drops everything once the echo has stalled. Returns true if it did. */
    @discardableResult
    public func expire(now: TimeInterval) -> Bool {
        if cells.isEmpty || now < deadline { return false }
        cells.removeAll()
        return true
    }

    public func clear() { cells.removeAll() }

    public static let timeout: TimeInterval = 1.0

    /** Cell width, close enough to the terminal's to place a prediction. */
    public static func width(_ g: String) -> Int {
        let scalars = Array(g.unicodeScalars)
        guard let first = scalars.first else { return 1 }
        let cp = first.value
        if scalars.count > 1 {
            // Emoji sequences (ZWJ, VS16, skin tones) and flags are one wide cell.
            if g.unicodeScalars.contains(where: { $0.value == 0x200D || $0.value == 0xFE0F }) || (0x1F1E6...0x1F1FF).contains(cp) || (0x1F000...0x1FAFF).contains(cp) { return 2 }
        }
        switch cp {
        case 0x1100...0x115F, 0x2E80...0x303E, 0x3041...0x33FF, 0x3400...0x4DBF,
             0x4E00...0x9FFF, 0xA000...0xA4CF, 0xAC00...0xD7A3, 0xF900...0xFAFF,
             0xFE30...0xFE4F, 0xFF00...0xFF60, 0xFFE0...0xFFE6,
             0x1F300...0x1F64F, 0x1F900...0x1F9FF, 0x1FA70...0x1FAFF, 0x20000...0x3FFFD: return 2
        default: return 1
        }
    }
}
