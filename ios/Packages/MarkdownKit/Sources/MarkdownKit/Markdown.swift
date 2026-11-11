import Foundation

/// The markdown chat CLIs write, parsed for rendering: headings, paragraphs,
/// fenced code, quotes, lists (nested), tables and rules, with bold, italic,
/// strikethrough, code and links inside. Not CommonMark: a line break in a
/// paragraph stays a line break, as chat replies mean it.
public indirect enum Block: Hashable, Sendable {
    case heading(level: Int, text: String)
    case paragraph(String)
    case code(language: String, text: String)
    case quote([Block])
    case list(ordered: Bool, start: Int, items: [[Block]])
    case table(header: [String], rows: [[String]])
    case rule
}

public struct Span: Hashable, Sendable {
    public var text: String
    public var bold: Bool
    public var italic: Bool
    public var strike: Bool
    public var code: Bool
    public var link: String?

    public init(_ text: String, bold: Bool = false, italic: Bool = false, strike: Bool = false, code: Bool = false, link: String? = nil) {
        self.text = text
        self.bold = bold
        self.italic = italic
        self.strike = strike
        self.code = code
        self.link = link
    }

    func with(text: String) -> Span {
        var span = self
        span.text = text
        return span
    }
}

public enum Markdown {
    public static func parse(_ text: String) -> [Block] {
        let units = Array(text.utf16)
        var lines: [String] = []
        var line: [UInt16] = []
        for (i, c) in units.enumerated() {
            if c == cr && i + 1 < units.count && units[i + 1] == lf { continue }
            if c == lf {
                lines.append(String(utf16: line))
                line.removeAll()
            } else {
                line.append(c)
            }
        }
        lines.append(String(utf16: line))
        return blocks(lines)
    }

    private static let heading = pattern(#"^(#{1,6})\s+(.*?)\s*#*\s*$"#)
    private static let rule = pattern(#"^\s{0,3}([-*_])(\s*\1){2,}\s*$"#)
    private static let item = pattern(#"^(\s*)([-*+]|(\d{1,9})[.)])(\s+|$)(.*)$"#)
    private static let tableSeparator = pattern(#"^\s*\|?(\s*:?-+:?\s*\|)+\s*(:?-+:?\s*)?$"#)
    private static let cellBar = pattern(#"(?<!\\)\|"#)

    private static func blocks(_ lines: [String]) -> [Block] {
        var out: [Block] = []
        var paragraph: [String] = []
        func flush() {
            if !paragraph.isEmpty { out.append(.paragraph(paragraph.joined(separator: "\n"))) }
            paragraph.removeAll()
        }
        var i = 0
        while i < lines.count {
            let line = lines[i]
            let t = line.trimmingLeadingWhitespace()
            if t.utf16HasPrefix("```") || t.utf16HasPrefix("~~~") {
                flush()
                let first = t.utf16.first
                let fence = String(utf16: t.utf16.prefix { $0 == first })
                let indent = line.utf16.count - t.utf16.count
                var body: [String] = []
                i += 1
                while i < lines.count && !lines[i].trimmingLeadingWhitespace().utf16HasPrefix(fence) {
                    body.append(lines[i].utf16Dropping(min(indent, indentOf(lines[i]))))
                    i += 1
                }
                out.append(.code(language: t.utf16Dropping(fence.utf16.count).trimmingWhitespace(), text: body.joined(separator: "\n")))
            } else if t.isEmpty {
                flush()
            } else if heading.matchesWhole(t) {
                flush()
                let m = heading.find(t)!
                out.append(.heading(level: m[1].utf16.count, text: m[2]))
            } else if rule.matchesWhole(line) {
                flush()
                out.append(.rule)
            } else if t.utf16HasPrefix(">") {
                flush()
                var quoted: [String] = []
                while i < lines.count && lines[i].trimmingLeadingWhitespace().utf16HasPrefix(">") {
                    let q = lines[i].trimmingLeadingWhitespace().utf16Dropping(1)
                    quoted.append(q.utf16HasPrefix(" ") ? q.utf16Dropping(1) : q)
                    i += 1
                }
                out.append(.quote(blocks(quoted)))
                continue
            } else if item.matchesWhole(line) {
                flush()
                i = list(lines, from: i, into: &out)
                continue
            } else if t.utf16HasPrefix("|") && i + 1 < lines.count && tableSeparator.matchesWhole(lines[i + 1]) {
                flush()
                let header = cells(t)
                var rows: [[String]] = []
                i += 2
                while i < lines.count && lines[i].trimmingLeadingWhitespace().utf16HasPrefix("|") {
                    let r = cells(lines[i])
                    rows.append(header.indices.map { $0 < r.count ? r[$0] : "" })
                    i += 1
                }
                out.append(.table(header: header, rows: rows))
                continue
            } else {
                paragraph.append(line.trimmingWhitespace())
            }
            i += 1
        }
        flush()
        return out
    }

    /// Parses the list starting at `start` into `out`; returns the line after it.
    private static func list(_ lines: [String], from start: Int, into out: inout [Block]) -> Int {
        let first = item.find(lines[start])!
        let indent = first[1].utf16.count
        let ordered = !first[3].isEmpty
        var items: [[Block]] = []
        var i = start
        while i < lines.count {
            guard let m = item.find(lines[i]), m[1].utf16.count == indent, !m[3].isEmpty == ordered else { break }
            let content = indent + m[2].utf16.count + max(m[4].utf16.count, 1)
            var body = [m[5]]
            i += 1
            while i < lines.count {
                let l = lines[i]
                if l.isBlank {
                    let next = (i + 1 ..< lines.count).first { !lines[$0].isBlank }
                    guard let next, indentOf(lines[next]) > indent else { break }
                    body.append("")
                } else if indentOf(l) > indent {
                    body.append(l.utf16Dropping(min(indentOf(l), content)))
                } else {
                    break
                }
                i += 1
            }
            items.append(blocks(body))
        }
        out.append(.list(ordered: ordered, start: Int(first[3]) ?? 1, items: items))
        return i
    }

    private static func indentOf(_ line: String) -> Int {
        line.utf16.prefix(while: isWhitespace).count
    }

    private static func cells(_ line: String) -> [String] {
        var s = line.trimmingWhitespace()
        if s.utf16HasPrefix("|") { s = s.utf16Dropping(1) }
        if s.utf16.last == bar { s = String(utf16: s.utf16.dropLast()) }
        return cellBar.split(s).map { $0.trimmingWhitespace().replacingOccurrences(of: #"\|"#, with: "|", options: .literal) }
    }

    /// A line of inline markdown as runs of one style each.
    public static func inline(_ text: String) -> [Span] {
        let parser = Inline(Array(text.utf16))
        parser.run(0, parser.s.count, Span(""))
        return parser.out
    }

    private final class Inline {
        let s: [UInt16]
        var out: [Span] = []

        init(_ s: [UInt16]) {
            self.s = s
        }

        func run(_ from: Int, _ to: Int, _ style: Span) {
            var buf: [UInt16] = []
            func emit() {
                if buf.isEmpty { return }
                if let last = out.last, last.with(text: "") == style {
                    out[out.count - 1].text += String(utf16: buf)
                } else {
                    out.append(style.with(text: String(utf16: buf)))
                }
                buf.removeAll()
            }
            var i = from
            while i < to {
                let c = s[i]
                if c == backslash && i + 1 < to && punctuation.contains(s[i + 1]) {
                    buf.append(s[i + 1])
                    i += 2
                    continue
                }
                if c == backtick {
                    let ticks = Array(s[i..<to].prefix { $0 == backtick })
                    if let close = index(of: ticks, from: i + ticks.count), close < to {
                        emit()
                        buf.append(contentsOf: s[(i + ticks.count)..<close])
                        let code = buf.count > 2 && buf.first == space && buf.last == space ? String(utf16: buf.dropFirst().dropLast()) : String(utf16: buf)
                        buf.removeAll()
                        var span = style.with(text: code)
                        span.code = true
                        out.append(span)
                        i = close + ticks.count
                        continue
                    }
                }
                let delim: [UInt16]?
                if starts(with: "**", at: i) || starts(with: "__", at: i) || starts(with: "~~", at: i) {
                    delim = Array(s[i..<(i + 2)])
                } else if c == star || c == underscore {
                    delim = [c]
                } else {
                    delim = nil
                }
                if let delim {
                    if let close = closing(delim, i, to) {
                        emit()
                        var inner = style
                        if delim.count == 2 && delim[0] == tilde {
                            inner.strike = true
                        } else if delim.count == 2 {
                            inner.bold = true
                        } else {
                            inner.italic = true
                        }
                        run(i + delim.count, close, inner)
                        i = close + delim.count
                        continue
                    }
                    buf.append(contentsOf: delim)
                    i += delim.count
                    continue
                }
                if c == openBracket,
                   let end = index(of: Array("](".utf16), from: i), (i + 1 ..< to).contains(end),
                   let close = index(of: [closeParen], from: end + 2), close < to {
                    emit()
                    var linked = style
                    linked.link = String(utf16: s[(end + 2)..<close]).trimmingWhitespace()
                    run(i + 1, end, linked)
                    i = close + 1
                    continue
                }
                if (starts(with: "https://", at: i) || starts(with: "http://", at: i)) && (i == 0 || !isLetterOrDigit(s[i - 1])) {
                    var end = i
                    while end < to && !isWhitespace(s[end]) && s[end] != lessThan && s[end] != greaterThan { end += 1 }
                    while end > i && urlTrail.contains(s[end - 1]) { end -= 1 }
                    emit()
                    let url = String(utf16: s[i..<end])
                    var linked = style.with(text: url)
                    linked.link = url
                    out.append(linked)
                    i = end
                    continue
                }
                buf.append(c)
                i += 1
            }
            emit()
        }

        /// Where `delim` opened at `at` closes: not right after it, not after
        /// a space, and for _ not inside a word (snake_case stays as is).
        func closing(_ delim: [UInt16], _ at: Int, _ to: Int) -> Int? {
            let start = at + delim.count
            if start >= to || isWhitespace(s[start]) { return nil }
            if delim[0] == underscore && at > 0 && isLetterOrDigit(s[at - 1]) { return nil }
            var j = start + 1
            while j <= to - delim.count {
                if starts(with: delim, at: j) && !isWhitespace(s[j - 1]) {
                    if delim.count == 2 { // ***x***: the double closes last, around the single
                        var run = j
                        while run < to && s[run] == delim[0] { run += 1 }
                        return run - delim.count
                    }
                    let doubled = j + 1 < to && s[j + 1] == delim[0]
                    let wordAfter = delim[0] == underscore && j + delim.count < to && isLetterOrDigit(s[j + delim.count])
                    if !doubled && !wordAfter { return j }
                    if doubled { j += 1 }
                }
                if s[j] == backtick { // code spans hide delimiters
                    let ticks = Array(s[j..<to].prefix { $0 == backtick })
                    if let close = index(of: ticks, from: j + ticks.count), close < to { j = close + ticks.count - 1 }
                }
                j += 1
            }
            return nil
        }

        private func starts(with prefix: some Collection<UInt16>, at i: Int) -> Bool {
            i + prefix.count <= s.count && s[i..<(i + prefix.count)].elementsEqual(prefix)
        }

        private func starts(with prefix: String, at i: Int) -> Bool {
            starts(with: prefix.utf16, at: i)
        }

        private func index(of needle: [UInt16], from start: Int) -> Int? {
            var j = max(start, 0)
            while j + needle.count <= s.count {
                if starts(with: needle, at: j) { return j }
                j += 1
            }
            return nil
        }
    }

    private static let punctuation = Set(#"\`*_{}[]()#+-.!|~>"#.utf16)
    private static let urlTrail = Set(#".,:;!?)'""#.utf16)
}

private let lf = UInt16(ascii: "\n")
private let cr = UInt16(ascii: "\r")
private let space = UInt16(ascii: " ")
private let backslash = UInt16(ascii: "\\")
private let backtick = UInt16(ascii: "`")
private let star = UInt16(ascii: "*")
private let underscore = UInt16(ascii: "_")
private let tilde = UInt16(ascii: "~")
private let bar = UInt16(ascii: "|")
private let openBracket = UInt16(ascii: "[")
private let closeParen = UInt16(ascii: ")")
private let lessThan = UInt16(ascii: "<")
private let greaterThan = UInt16(ascii: ">")
