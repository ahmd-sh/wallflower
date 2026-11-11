/// Choices a program offers on its screen, to answer by touch: a numbered
/// menu with a cursor (❯ or ›) on one option, as CLIs ask for permission,
/// answers and approvals. The rows above it, up to the rule that opens its
/// box, say what it's about; boxed rows (between ╌ rules) are code. A row of
/// ☐ ☒ ✔ chips steps between questions. Rows beneath an option continue it
/// when its last word couldn't fit the row before (the program wrapped it)
/// and they're in its color; others describe it. The bottom-most menu on the
/// screen is the live one.
///
/// Or a list picked from with the cursor alone, as Claude Code's /rewind:
/// below its rule what it's about, then options indented past the cursor's
/// ❯, each a row, what the rows under it say, and a blank row; ↑ / ↓ N more
/// say it scrolls. Its foot ("Enter to continue · Esc to cancel") may be
/// past the bottom of a short pane. `quote` is what either quotes (│ rows),
/// a message it's about.
///
/// A menu can have a box beside it previewing the option under the cursor,
/// as Claude Code's questions do: the menu is what's left of it, and a
/// number only moves the cursor (`previewed`).
public struct Prompt: Hashable, Sendable {
    public var lines: [Line]
    public var options: [Option]
    public var tabs: [Tab]
    public var quote: String
    public var previewed: Bool

    public init(lines: [Line], options: [Option], tabs: [Tab], quote: String = "", previewed: Bool = false) {
        self.lines = lines
        self.options = options
        self.tabs = tabs
        self.quote = quote
        self.previewed = previewed
    }

    public struct Line: Hashable, Sendable {
        public var text: String
        public var code: Bool

        public init(text: String, code: Bool) {
            self.text = text
            self.code = code
        }
    }

    public struct Option: Hashable, Sendable {
        /// As shown; nil for an item without one, like a multiple choice's Next.
        public var number: Int?
        public var label: String
        public var detail: String
        /// A multiple choice's box; nil if it has none.
        public var checked: Bool?
        /// The cursor's on it.
        public var current: Bool
        /// Drawn in grey, as a lesser option is (Type something).
        public var muted: Bool
        /// Not an option but more of them, above (-1) or below (1) those shown.
        public var scroll: Int

        public init(number: Int?, label: String, detail: String, checked: Bool?, current: Bool, muted: Bool, scroll: Int = 0) {
            self.number = number
            self.label = label
            self.detail = detail
            self.checked = checked
            self.current = current
            self.muted = muted
            self.scroll = scroll
        }
    }

    /// A question's chip, with its mark (☐ ☒ ✔) as shown.
    public struct Tab: Hashable, Sendable {
        public var label: String
        public var current: Bool

        public init(label: String, current: Bool) {
            self.label = label
            self.current = current
        }
    }

    /// Keys that pick `option`: its number, which these menus take at once
    /// (a multiple choice's toggles) unless `previewed`, or the cursor moved
    /// there and Enter.
    public func keys(_ option: Option) -> [String] {
        if let n = option.number, (1...9).contains(n) { return previewed ? [String(n), "Enter"] : [String(n)] }
        let from = max(options.firstIndex { $0.current } ?? -1, 0)
        if option.scroll != 0 {
            // One past the last shown that way, which scrolls the list.
            let shown = options.indices.filter { options[$0].scroll == 0 }
            guard let edge = option.scroll < 0 ? shown.first : shown.last else { return [] }
            return Array(repeating: option.scroll < 0 ? "Up" : "Down", count: abs(edge - from) + 1)
        }
        let to = options.firstIndex(of: option) ?? -1
        return Array(repeating: to > from ? "Down" : "Up", count: abs(to - from)) + ["Enter"]
    }

    /// Keys that move to `tab`'s question.
    public func keys(_ tab: Tab) -> [String] {
        let from = max(tabs.firstIndex { $0.current } ?? -1, 0)
        let to = tabs.firstIndex(of: tab) ?? -1
        return Array(repeating: to > from ? "Right" : "Left", count: abs(to - from))
    }

    private static let option = Pattern(#"^(\s*)([❯›▶]\s*|[↑↓]\s*)?(\d{1,2})\.\s+(.*\S)\s*$"#)
    private static let more = Pattern(#"^([↑↓])\s*\d+ more$"#)
    private static let marker = Pattern(#"^\s*[❯›▶]\s"#)
    private static let ago = Pattern(#"^\(.*\bago\)$"#)
    private static let box = Pattern(#"^\[(.)]\s+(.*)$"#)
    private static let chips = Pattern(#"([☐☒✔☑✓])\s*([^☐☒✔☑✓←→]*)"#)
    private static let bar = Pattern(#"^\s*│(.*)$"#)
    private static let context = 24 // rows above the options at most
    private static let edge = 2

    public static func read(_ s: Screen) -> Prompt? {
        let rows = (0..<s.rows).map { Row.of(s, $0) }
        let last = rows.lastIndex { rule($0.text) } ?? -1
        for y in downTo(s.rows - 1, 0) {
            guard let m = option.matchEntire(rows[y].text) else { continue }
            if m.groupValue(3) != "1" { continue }
            guard let found = block(rows, y) else { continue }
            let (menu, end) = found
            // Numbered rows all above the last rule, with a list to pick from below it, are something said above that list: a message starting 1.
            if end <= last, let list = picker(rows) { return list }
            return menu
        }
        return picker(rows)
    }

    private static func cursor(_ m: Match) -> Bool { ["❯", "›", "▶"].contains(m.group(2)?.value.trimmed()) }

    /// A row's text, and the cell under each of its characters, with what the cells are.
    private struct Row {
        let text: String
        private let units: [UInt16]
        private let cells: [Int]
        private let muted: [Bool]
        private let lit: [Bool]

        private init(units: [UInt16], cells: [Int], muted: [Bool], lit: [Bool]) {
            self.text = String(units: units)
            self.units = units
            self.cells = cells
            self.muted = muted
            self.lit = lit
        }

        func highlighted(_ i: Int) -> Bool { i < units.count && units[i] != unit(" ") && lit[cells[i]] }

        /// Most of its characters from `from` (up to `to`) are grey.
        func mutedFrom(_ from: Int, _ to: Int? = nil) -> Bool {
            var grey = 0
            var all = 0
            for i in until(from, to ?? units.count) {
                if isWhitespace(units[i]) { continue }
                all += 1
                if muted[cells[i]] { grey += 1 }
            }
            return all > 0 && grey * 2 > all
        }

        /// Where grey words start that run to its end, after words from `from` that aren't: a description beside a label.
        func greyTail(_ from: Int) -> Int? {
            var at: Int?
            for i in downTo(units.count - 1, from) {
                if isWhitespace(units[i]) { continue }
                if !muted[cells[i]] { break }
                at = i
            }
            guard let a = at, until(from, a).contains(where: { !isWhitespace(units[$0]) && !muted[cells[$0]] }) else { return nil }
            return a
        }

        /// The character drawn from cell `x`, if one starts there.
        func at(_ x: Int) -> UInt16? { units.indices.first { cells[$0] == x }.map { units[$0] } }

        /// The cell `i`'s character is drawn from.
        func cell(_ i: Int) -> Int { cells[i] }

        /// What's left of cell `x`.
        func before(_ x: Int) -> Row {
            Row(units: Array(units[..<(units.indices.first { cells[$0] >= x } ?? units.count)]), cells: cells, muted: muted, lit: lit)
        }

        static func of(_ s: Screen, _ y: Int) -> Row {
            var units: [UInt16] = []
            var cells: [Int] = []
            units.reserveCapacity(s.cols)
            cells.reserveCapacity(s.cols)
            for x in 0..<s.cols {
                if s.isSpacer(x, y) { continue }
                let t = s.text(x, y)
                for c in (t.isEmpty ? " " : t).utf16 {
                    cells.append(x)
                    units.append(c)
                }
            }
            return Row(units: units, cells: cells, muted: (0..<s.cols).map { s.isMuted($0, y) || s.isFaint($0, y) }, lit: (0..<s.cols).map { s.isHighlighted($0, y) })
        }
    }

    /// The menu whose first option is on row `top`, if it is one, and the row it ends before.
    private static func block(_ screen: [Row], _ top: Int) -> (Prompt, Int)? {
        let beside = preview(screen, top)
        let rows = beside.map { b in screen.enumerated().map { y, row in y >= top ? row.before(b) : row } } ?? screen
        guard let first = option.matchEntire(rows[top].text), let numberAt = first.group(3)?.range.lowerBound else { return nil }
        var options: [Option] = []
        var labelAt = 0
        var labelMuted = false
        var next = 1
        var y = top
        while y < rows.count {
            let row = rows[y].text
            let m = option.matchEntire(row)
            if let m, m.groupValue(3) == String(next), m.group(3)?.range.lowerBound == numberAt {
                var label = m.groupValue(4)
                labelAt = m.group(4)!.range.lowerBound
                var checked: Bool?
                if let b = box.matchEntire(label) {
                    checked = !b.groupValue(1).isBlank
                    labelAt += label.length - b.groupValue(2).length
                    label = b.groupValue(2)
                }
                // A description can follow the label on its row, in grey after a ·.
                var detail = ""
                if let at = rows[y].greyTail(labelAt) {
                    detail = row.substring(at).trimmed().removingPrefix("·").trimmed()
                    label = row.substring(labelAt, at).trimmed().removingSuffix("·").trimmed()
                }
                labelMuted = rows[y].mutedFrom(labelAt, labelAt + label.length)
                options.append(Option(number: next, label: label, detail: detail, checked: checked, current: cursor(m), muted: labelMuted))
                next += 1
            } else if rule(row) {
                // A rule can split a menu (Chat about this, below the others); otherwise it ends it.
                let after = until(y + 1, rows.count).first { !rows[$0].text.isBlank }
                guard let after, option.matchEntire(rows[after].text)?.groupValue(3) == String(next) else { break }
            } else if row.isBlank {
                break
            } else if beside != nil && indent(row) > numberAt && !options.isEmpty {
                // Beside a preview, options say nothing more: rows under one are its label, wrapped.
                options[options.count - 1].label += " " + row.trimmed()
            } else if indent(row) >= labelAt && !options.isEmpty {
                let text = row.trimmed()
                let last = options[options.count - 1]
                let wrapped = wraps(rows[y - 1].text, text)
                if wrapped && last.detail.isEmpty && rows[y].mutedFrom(indent(row)) == labelMuted {
                    options[options.count - 1].label = last.label + " " + text
                } else {
                    options[options.count - 1].detail = last.detail.isEmpty ? text : last.detail + (wrapped ? " " : "\n") + text
                }
            } else if indent(unmarked(row)) > numberAt && !options.isEmpty {
                let current = marker.containsMatch(in: row)
                let label = row.trimmed().removingPrefix("❯").removingPrefix("›").removingPrefix("▶").trimmed()
                options.append(Option(number: nil, label: label, detail: "", checked: nil, current: current, muted: rows[y].mutedFrom(indent(row))))
            } else {
                break
            }
            y += 1
        }
        if options.filter({ $0.number != nil }).count < 2 || !options.contains(where: { $0.current }) { return nil }
        let (lines, tabs, quote) = about(rows, top)
        return (Prompt(lines: lines, options: options, tabs: tabs, quote: quote, previewed: beside != nil), y)
    }

    /// The cell a box beside the menu on row `top` starts at, if there's one: ┌ on its first option's row, └ under it.
    private static func preview(_ rows: [Row], _ top: Int) -> Int? {
        let at = rows[top].text.indexOf(" ┌")
        if at < 0 { return nil }
        let x = rows[top].cell(at + 1)
        return until(top + 1, rows.count).contains { rows[$0].at(x) == unit("└") } ? x : nil
    }

    /// A list picked from with the cursor, below the last rule.
    private static func picker(_ rows: [Row]) -> Prompt? {
        guard let rule = rows.lastIndex(where: { Self.rule($0.text) }) else { return nil }
        let foot = until(rule + 1, rows.count).first { rows[$0].text.trimmed().startsWith("Enter to ") } ?? rows.count
        guard let cursor = until(rule + 1, foot).first(where: { marker.containsMatch(in: rows[$0].text) }) else { return nil }
        let at = indent(rows[cursor].text) + 2 // where options' words start
        let top = (rule + 1...cursor).first { $0 == cursor || !rows[$0].text.isBlank && indent(rows[$0].text) >= at }!
        if top == rule + 1 { return nil } // nothing said above it: a prompt's suggestions, say
        var options: [Option] = []
        var fresh = true // the next row starts an option
        for y in until(top, foot) {
            let row = rows[y].text
            let text = row.trimmed()
            let scroll = more.matchEntire(text)
            let current = marker.containsMatch(in: row)
            if text.isEmpty {
                fresh = true
            } else if !current && indent(row) < at {
                break
            } else if let scroll {
                options.append(Option(number: nil, label: text, detail: "", checked: nil, current: false, muted: true, scroll: scroll.groupValue(1) == "↑" ? -1 : 1))
                fresh = true
            } else if fresh || current {
                let label = text.removingPrefix("❯").removingPrefix("›").removingPrefix("▶").trimmed()
                options.append(Option(number: nil, label: label, detail: "", checked: nil, current: current, muted: rows[y].mutedFrom(indent(row))))
                fresh = false
            } else {
                options[options.count - 1].detail = (options[options.count - 1].detail + "\n" + text).trimmed()
            }
        }
        if options.filter({ $0.scroll == 0 }).count < 2 || !options.contains(where: { $0.current }) { return nil }
        let (lines, tabs, quote) = about(rows, top)
        return Prompt(lines: lines, options: options, tabs: tabs, quote: quote)
    }

    /// What a menu whose options start on row `top` is about: up to the rule that opens its box.
    private static func about(_ rows: [Row], _ top: Int) -> ([Line], [Tab], String) {
        var r = top - 1
        while r >= 0 && top - r <= context && !rule(rows[r].text) { r -= 1 }
        var lines: [Line] = []
        var tabs: [Tab] = []
        var quoted: [String] = []
        var code = false
        var previous: String? // the row before, if it was words; a quoted (│) one without its bar
        var previousQuoted = false
        let chipMarks = "☐☒✔☑✓←".units
        for row in until(r + 1, top) {
            let text = rows[row].text
            let quote = bar.matchEntire(text)?.groupValue(1)
            let words = quote ?? text
            let chipped = text.trimmedStart().utf16.first.map { chipMarks.contains($0) } == true && chips.containsMatch(in: text)
            if text.isBlank {
            } else if dashed(text) {
                code.toggle()
            } else if chipped {
                tabs += self.tabs(rows[row])
            } else if code {
                lines.append(Line(text: text.trimmedEnd(), code: true))
            } else if let previous, previousQuoted == (quote != nil), wraps(previous, words.trimmed()) {
                // Words the app wrapped onto this row join the row before, quoted or not as it was.
                lines[lines.count - 1] = Line(text: lines[lines.count - 1].text + " " + words.trimmed(), code: false)
            } else {
                lines.append(Line(text: words.trimmed(), code: false))
            }
            if let quote { quoted.append(quote.trimmed()) }
            previous = !text.isBlank && !dashed(text) && !chipped && !code ? words : nil
            previousQuoted = quote != nil
        }
        return (dedent(lines), tabs, quoted.filter { !ago.matches($0) }.joined(separator: " "))
    }

    private static func tabs(_ row: Row) -> [Tab] {
        chips.findAll(row.text).map { m in
            Tab(label: (m.groupValue(1) + " " + m.groupValue(2).trimmed()).trimmed(), current: m.range.contains(where: row.highlighted))
        }
    }

    /// Code keeps its indent relative to itself.
    private static func dedent(_ lines: [Line]) -> [Line] {
        let common = lines.filter { $0.code && !$0.text.isBlank }.map { indent($0.text) }.min() ?? 0
        return lines.map { $0.code ? Line(text: $0.text.dropping(common), code: true) : $0 }
    }

    private static func indent(_ row: String) -> Int { row.length - row.trimmedStart().length }

    /// `row` with the cursor that starts it blanked, so an item under it keeps its indent (❯    Next).
    private static func unmarked(_ row: String) -> String {
        guard let m = marker.find(row), m.range.lowerBound == 0 else { return row }
        return " ".repeated(m.value.length) + row.substring(m.value.length)
    }

    /// The app wrapped `next`'s words off `row`: its first word wouldn't
    /// have fit. Apps keep a column or two clear at the edge.
    private static func wraps(_ row: String, _ next: String) -> Bool {
        row.trimmedEnd().length + 1 + next.substringBefore(" ").length > row.length - edge
    }

    /// A rule across: ─, or ▔ in Claude Code's fullscreen mode.
    private static func rule(_ row: String) -> Bool { row.count("─", "▔") >= max(8, row.trimmedEnd().length * 3 / 4) }

    private static func dashed(_ row: String) -> Bool { row.count("╌") >= max(8, row.trimmedEnd().length * 3 / 4) }
}
