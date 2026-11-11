import Foundation

/// A terminal's active screen: its text, with what each cell is, enough to
/// read what a program's input line holds, or the choices it offers. It's
/// all a terminal tells the programs that read it. Cells are row-major; a
/// wide character's second cell is an empty spacer.
public struct Screen: Sendable {
    public let cols: Int
    public let rows: Int
    public let cursorX: Int
    public let cursorY: Int
    private let cells: [String]
    private let attrs: [Int]
    private let wraps: [Bool]

    public init(cols: Int, rows: Int, cursorX: Int, cursorY: Int, text: [String], attrs: [Int], wrapped: [Bool]) {
        self.cols = cols
        self.rows = rows
        self.cursorX = cursorX
        self.cursorY = cursorY
        self.cells = text
        self.attrs = attrs
        self.wraps = wrapped
    }

    public func text(_ x: Int, _ y: Int) -> String { cells[y * cols + x] }

    /// `output`, `input` or `prompt`, as the shell marked it (OSC 133); output if it doesn't.
    public func role(_ x: Int, _ y: Int) -> Int { attrs[y * cols + x] & 3 }

    public func isFaint(_ x: Int, _ y: Int) -> Bool { attrs[y * cols + x] & Screen.faint != 0 }

    /// In palette color 8, which shells use for autosuggestions.
    public func isGrey(_ x: Int, _ y: Int) -> Bool { attrs[y * cols + x] & Screen.grey != 0 }

    public func isSpacer(_ x: Int, _ y: Int) -> Bool { attrs[y * cols + x] & Screen.spacer != 0 }

    /// In a grey, as apps write secondary text: hints, descriptions.
    public func isMuted(_ x: Int, _ y: Int) -> Bool { attrs[y * cols + x] & Screen.muted != 0 }

    /// Inverse or on a background color, as apps mark what's current.
    public func isHighlighted(_ x: Int, _ y: Int) -> Bool { attrs[y * cols + x] & Screen.highlight != 0 }

    /// The row continues on the next one (soft wrap).
    public func isWrapped(_ y: Int) -> Bool { wraps[y] }

    public static let output = 0
    public static let input = 1
    public static let prompt = 2
    public static let faint = 4
    public static let grey = 8
    public static let spacer = 16
    public static let muted = 32
    public static let highlight = 64

    /// Reads the native snapshot layout (see terminal.c); nil if it's cut short.
    public static func parse(_ data: Data) -> Screen? { parse([UInt8](data)) }

    /// Reads the native snapshot layout (see terminal.c); nil if it's cut short.
    public static func parse(_ bytes: [UInt8]) -> Screen? {
        var at = 0
        func u8() -> Int? {
            guard at < bytes.count else { return nil }
            defer { at += 1 }
            return Int(bytes[at])
        }
        func u16() -> Int? {
            guard at + 2 <= bytes.count else { return nil }
            defer { at += 2 }
            return Int(bytes[at]) | Int(bytes[at + 1]) << 8
        }
        func u32() -> UInt32? {
            guard at + 4 <= bytes.count else { return nil }
            defer { at += 4 }
            return UInt32(bytes[at]) | UInt32(bytes[at + 1]) << 8 | UInt32(bytes[at + 2]) << 16 | UInt32(bytes[at + 3]) << 24
        }
        guard let cols = u16(), let rows = u16(), let cx = u16(), let cy = u16() else { return nil }
        var text = [String](repeating: "", count: cols * rows)
        var attrs = [Int](repeating: 0, count: cols * rows)
        var wrapped = [Bool](repeating: false, count: rows)
        for y in 0..<rows {
            guard let flags = u8() else { return nil }
            wrapped[y] = flags & 1 != 0
            for x in 0..<cols {
                guard let attr = u8(), let n = u8() else { return nil }
                attrs[y * cols + x] = attr
                if n == 0 { continue }
                var s = String.UnicodeScalarView()
                for _ in 0..<n {
                    guard let cp = u32() else { return nil }
                    s.append(Unicode.Scalar(cp) ?? "\u{FFFD}")
                }
                text[y * cols + x] = String(s)
            }
        }
        return Screen(cols: cols, rows: rows, cursorX: cx, cursorY: cy, text: text, attrs: attrs, wrapped: wrapped)
    }
}
