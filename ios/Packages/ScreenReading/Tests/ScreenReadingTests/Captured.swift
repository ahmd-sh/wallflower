import Foundation
import ScreenReading

/// A screen from tmux's capture-pane -e: text, with greys (256-color 240-250, or 8) muted, backgrounds highlighted, and dim faint.
func captured(_ name: String, _ cols: Int) -> Screen {
    let url = Bundle.module.url(forResource: name, withExtension: nil, subdirectory: "Resources")!
    var whole = try! String(contentsOf: url, encoding: .utf8)
    while whole.hasSuffix("\n") { whole.removeLast() }
    let rows = whole.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
    var text = [String](repeating: "", count: cols * rows.count)
    var attrs = [Int](repeating: 0, count: cols * rows.count)
    let sgr = try! NSRegularExpression(pattern: "\u{1b}\\[([0-9;]*)m")
    for (y, row) in rows.enumerated() {
        let ns = row as NSString
        var x = 0
        var muted = false
        var lit = false
        var faint = false
        var i = 0
        while i < ns.length && x < cols {
            if let m = sgr.firstMatch(in: row, options: .anchored, range: NSRange(location: i, length: ns.length - i)) {
                let codes = ns.substring(with: m.range(at: 1)).split(separator: ";", omittingEmptySubsequences: false).map(String.init)
                func code(_ at: Int) -> String? { at < codes.count ? codes[at] : nil }
                var k = 0
                while k < codes.count {
                    switch codes[k] {
                    case "0", "": muted = false; lit = false; faint = false
                    case "2": faint = true
                    case "22": faint = false
                    case "39": muted = false
                    case "49": lit = false
                    case "7": lit = true
                    case "90": muted = true
                    case "38":
                        muted = code(k + 1) == "5" && ((240...250).contains(Int(codes[k + 2]) ?? -1) || codes[k + 2] == "8")
                        k += code(k + 1) == "5" ? 2 : 4
                    case "48":
                        lit = true
                        k += code(k + 1) == "5" ? 2 : 4
                    default:
                        if let n = Int(codes[k]), (30...37).contains(n) || (91...97).contains(n) { muted = false }
                    }
                    k += 1
                }
                i = m.range.location + m.range.length
                continue
            }
            var cp = UInt32(ns.character(at: i))
            var n = 1
            if UTF16.isLeadSurrogate(UInt16(cp)), i + 1 < ns.length, UTF16.isTrailSurrogate(ns.character(at: i + 1)) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (UInt32(ns.character(at: i + 1)) - 0xDC00)
                n = 2
            }
            text[y * cols + x] = cp == 0x20 ? "" : String(Unicode.Scalar(cp).map(Character.init) ?? "\u{FFFD}")
            attrs[y * cols + x] = (muted ? Screen.muted : 0) | (lit ? Screen.highlight : 0) | (faint ? Screen.faint : 0)
            x += 1
            i += n
        }
    }
    return Screen(cols: cols, rows: rows.count, cursorX: 0, cursorY: 0, text: text, attrs: attrs, wrapped: [Bool](repeating: false, count: rows.count))
}
