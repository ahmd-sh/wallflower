import Foundation

/**
 * A frame packed by the native side (terminal.c), read in place without
 * allocating. Native-order int32s:
 *
 * ```
 * header  dirty cols rows cursorFlags cursorX cursorY cursorStyle
 *         background foreground cursorColor rowCount scrollTotal scrollOffset scrollLen
 * row     y extraCount, then cols × [codepoint fg bg underlineColor flags],
 *         then extraCount × [x n codepoint×n] for multi-codepoint graphemes
 * ```
 *
 * Colors are 0xRRGGBB, or [DEFAULT] for the terminal default. Only rows that
 * changed are present unless [full].
 */
public final class FrameBuffer {
    private var ints: UnsafeMutablePointer<Int32>
    private var capacity: Int // in ints
    private var length = 0
    private var rowOffsets = [Int](repeating: 0, count: 64)

    public init() {
        capacity = 16 * 1024
        ints = .allocate(capacity: capacity)
        ints.initialize(repeating: 0, count: capacity)
    }

    deinit { ints.deallocate() }

    public var full: Bool { length > 0 && ints[0] == 2 }
    public var cols: Int { Int(ints[1]) }
    public var rows: Int { Int(ints[2]) }
    public var cursorVisible: Bool { ints[3] & 1 != 0 }
    public var cursorBlinking: Bool { ints[3] & 2 != 0 }
    public var cursorWideTail: Bool { ints[3] & 4 != 0 }
    public var cursorInViewport: Bool { ints[3] & 8 != 0 }
    /** A full-screen app has the alternate screen, which keeps no scrollback. */
    public var altScreen: Bool { ints[3] & 32 != 0 }
    public var cursorX: Int { Int(ints[4]) }
    public var cursorY: Int { Int(ints[5]) }
    public var cursorStyle: Int { Int(ints[6]) }
    public var background: Int32 { ints[7] }
    public var foreground: Int32 { ints[8] }
    public var cursorColor: Int32 { ints[9] }
    public var rowCount: Int { length > 0 ? Int(ints[10]) : 0 }
    public var scrollTotal: Int { Int(ints[11]) }
    public var scrollOffset: Int { Int(ints[12]) }
    public var scrollLength: Int { Int(ints[13]) }

    public func rowY(_ row: Int) -> Int { Int(ints[rowOffsets[row]]) }

    @inline(__always) private func cell(_ row: Int, _ x: Int) -> Int { rowOffsets[row] + Self.rowHeader + x * Self.cellInts }

    public func codepoint(_ row: Int, _ x: Int) -> UInt32 { UInt32(bitPattern: ints[cell(row, x)]) }
    public func fg(_ row: Int, _ x: Int) -> Int32 { ints[cell(row, x) + 1] }
    public func bg(_ row: Int, _ x: Int) -> Int32 { ints[cell(row, x) + 2] }
    public func underlineColor(_ row: Int, _ x: Int) -> Int32 { ints[cell(row, x) + 3] }
    public func flags(_ row: Int, _ x: Int) -> Int32 { ints[cell(row, x) + 4] }

    /** The cell's text: its whole grapheme. */
    public func text(_ row: Int, _ x: Int) -> String {
        let cp = codepoint(row, x)
        guard cp != 0, let first = Unicode.Scalar(cp) else { return "" }
        var s = String.UnicodeScalarView()
        s.append(first)
        if flags(row, x) & Self.FLAG_GRAPHEME != 0 {
            var at = rowOffsets[row] + Self.rowHeader + cols * Self.cellInts
            for _ in 0..<Int(ints[rowOffsets[row] + 1]) {
                let n = Int(ints[at + 1])
                if Int(ints[at]) == x {
                    for i in 0..<n { if let u = Unicode.Scalar(UInt32(bitPattern: ints[at + 2 + i])) { s.append(u) } }
                    break
                }
                at += 2 + n
            }
        }
        return String(s)
    }

    func pack(_ body: (UnsafeMutablePointer<Int32>, Int) -> Int32) -> Int32 { body(ints, capacity) }

    func load(bytes: Int) {
        length = bytes / 4
        guard bytes > 0 else { return }
        if rowOffsets.count < rowCount { rowOffsets = [Int](repeating: 0, count: rowCount) }
        var at = Self.header
        for r in 0..<rowCount {
            rowOffsets[r] = at
            let extras = Int(ints[at + 1])
            at += Self.rowHeader + cols * Self.cellInts
            for _ in 0..<extras { at += 2 + Int(ints[at + 1]) }
        }
    }

    func grow(bytes: Int) {
        ints.deallocate()
        capacity = bytes / 4 + 1
        ints = .allocate(capacity: capacity)
        ints.initialize(repeating: 0, count: capacity)
        length = 0
    }

    public static let DEFAULT: Int32 = -1

    public static let FLAG_BOLD: Int32 = 1 << 0
    public static let FLAG_ITALIC: Int32 = 1 << 1
    public static let FLAG_FAINT: Int32 = 1 << 2
    public static let FLAG_BLINK: Int32 = 1 << 3
    public static let FLAG_INVERSE: Int32 = 1 << 4
    public static let FLAG_INVISIBLE: Int32 = 1 << 5
    public static let FLAG_STRIKETHROUGH: Int32 = 1 << 6
    public static let FLAG_OVERLINE: Int32 = 1 << 7
    public static let FLAG_SELECTED: Int32 = 1 << 13
    public static let FLAG_GRAPHEME: Int32 = 1 << 14

    /** Underline style: 0 none, 1 single, 2 double, 3 curly, 4 dotted, 5 dashed. */
    public static func underline(_ flags: Int32) -> Int { Int((flags >> 8) & 7) }

    /** Cell width: 0 narrow, 1 wide, 2 spacer after a wide cell, 3 spacer at a line wrap. */
    public static func wide(_ flags: Int32) -> Int { Int((flags >> 11) & 3) }

    public static let CURSOR_BAR = 0
    public static let CURSOR_BLOCK = 1
    public static let CURSOR_UNDERLINE = 2
    public static let CURSOR_BLOCK_HOLLOW = 3

    private static let header = 14
    private static let rowHeader = 2
    private static let cellInts = 5
}
