/// Claude Code: its prompt box, the menus it asks with, and its own ways:
/// vim mode, /compact, /rewind, which takes its conversation back, its
/// permission modes, its transcript, and /btw's answers aside.
public final class Claude: Program {
    public static let shared = Claude()

    private init() {}

    public let clearKeys = ["C-u"] // a row at a time

    public let newlineKeys: [String]? = ["C-j"] // one key: ⌥⏎ comes as Escape then Enter, which vim mode can take apart

    public let compact: String? = "/compact"

    public let queues = true

    public let acceptSuggestion: [String]? = ["Tab"]

    public let commands = [
        Command(text: "/compact", about: "summarize the conversation, freeing context"),
        Command(text: "/rewind", about: "go back to an earlier message"),
        Command(text: "/clear", about: "start a new conversation"),
        Command(text: "/model", about: "switch model"),
        Command(text: "/btw", about: "ask a side question, answered apart from the conversation"),
    ]

    public let keyBar: KeyDefaults? = KeyDefaults(
        name: "claude code",
        keys: ["Escape", "BTab", "Up", "Down", "Enter", "1", "2", "3", "C-o", "C-c"],
        stop: ["C-c"] // vim mode's INSERT takes Esc to leave it, not to stop Claude
    )

    /// The prompt box: between two rules, a first row starting `❯ ` (or `! `
    /// in shell mode) and rows indented to match. Claude wraps long lines
    /// itself, at words, two columns short of the screen's edge (and a
    /// column shorter for the cursor after the last word), so a row that
    /// couldn't fit the next one's first word was wrapped rather than ended;
    /// a word too long for a row is broken at its end.
    /// Faint text is the placeholder or a suggestion, not input: in an empty
    /// box, it's the suggestion (the placeholder's `Try "…"` suggests what's
    /// quoted), cut short with … if it's wider than the box.
    public func line(_ screen: Screen) -> InputLine? {
        let s = screen
        for y in downTo(s.rows - 2, 1) {
            let mark = s.text(0, y)
            if (mark != "❯" && mark != "!") || !s.text(1, y).isBlank || !rule(s, y - 1) { continue }
            guard let end = until(y + 1, s.rows).first(where: { rule(s, $0) }) else { continue }
            let width = s.cols - 4
            var out = mark == "!" ? "!" : ""
            var ghost = ""
            var previous: String?
            for r in y..<end {
                var row = ""
                for x in until(2, s.cols) {
                    if s.isSpacer(x, r) { continue }
                    let c = s.text(x, r)
                    // Blanks go with both: apps draw the spaces between faint words plain.
                    if c.isBlank {
                        row += " "
                        ghost += " "
                    } else if s.isFaint(x, r) {
                        ghost += c
                    } else {
                        row += c
                    }
                }
                ghost += " "
                let line = row.trimmedEnd()
                if let previous {
                    let word = line.substringBefore(" ").length
                    let cursor = r == end - 1 && word == line.length ? 1 : 0
                    if previous.length >= width && previous.substringAfterLast(" ").length + word > width {
                        // A word too long for any row (a path, say) is broken where the row ends.
                    } else if !line.isEmpty && previous.length + 1 + word + cursor > width {
                        out += " "
                    } else {
                        out += "\n"
                    }
                }
                out += line
                previous = line
            }
            let text = out.trimmedEnd()
            let faint = Claude.spaces.replace(ghost.trimmed(), with: " ")
            let suggestion = text.isEmpty && !Claude.placeholders.contains(faint) ? Claude.tried.matchEntire(faint)?.groupValue(1) ?? faint : ""
            return InputLine(text: text, rows: end - y, suggestion: suggestion)
        }
        return nil
    }

    /// What Claude asks takes its prompt box's place: with the box there, a
    /// numbered list on screen is something said (a message starting 1.), not
    /// a menu; nor is one in its transcript, or in an answer aside.
    public func prompt(_ screen: Screen) -> Prompt? { line(screen) != nil || aside(screen) != nil ? nil : Prompt.read(screen) }

    /// Above its prompt box while it works, Claude says what it's doing: a
    /// mark that spins, its word for it ("Compacting conversation", or one of
    /// its whims), then how long. What it's queued, and tips, can come between.
    /// An answer aside takes the box's place, and it says so above that.
    public func status(_ screen: Screen) -> String? {
        let s = screen
        guard let top = box(s).map({ $0 - 1 }) ?? answered(s) else { return nil }
        for y in downTo(top - 1, max(0, top - 11)) {
            if !Claude.spins.contains(s.text(0, y)) || !s.text(1, y).isBlank { continue }
            return Claude.working.find(row(s, y, from: 2))?.groupValue(1)
        }
        return nil
    }

    /// Below its prompt box Claude says which permission mode it's in, after
    /// vim mode's -- INSERT -- while that's on: a mark (⏵⏵, ⏸), the mode's
    /// name and "on" ("⏸ plan mode on", "⏵⏵ accept edits on"), then how to
    /// change it. Shift+Tab steps to the next, round the modes it's set up
    /// with (here manual, accept edits, plan, auto).
    public func mode(_ screen: Screen) -> Mode? {
        let s = screen
        guard let box = box(s), let end = until(box + 1, s.rows).first(where: { rule(s, $0) }) else { return nil }
        for y in until(end + 1, s.rows) {
            guard let m = Claude.moded.find(row(s, y, from: 0)) else { continue }
            return Mode(name: m.groupValue(2).removingSuffix(" mode"), mark: m.groupValue(1))
        }
        return nil
    }

    public let nextMode: [String]? = ["BTab"]

    public let modes = ["manual", "accept edits", "plan", "auto"]

    private static let moded = Pattern("^\\s*(?:-- [A-Z]+ --\\s+)?([\u{2300}-\u{23ff}]+)\\s+(\\p{L}[\\p{L} ]*?) on\\b")

    /// Claude's own views in its prompt box's place, where keys are theirs:
    /// its detailed transcript (ctrl+o), which says so below its last rule, a
    /// faint one (or its keys there, after ?, or a search, after /); ctrl+o
    /// leaves it from any of those. And an answer to /btw (see `answer`).
    public func aside(_ screen: Screen) -> Aside? {
        let s = screen
        if box(s) != nil { return nil }
        if let last = downTo(s.rows - 1, 0).first(where: { rule(s, $0) }) {
            let below = until(last + 1, s.rows).map { row(s, $0, from: 0).trimmed() }.filter { !$0.isEmpty }
            let first = below.first ?? ""
            let faint = (0..<s.cols).filter { s.isFaint($0, last) }.count >= s.cols / 2
            if first.startsWith("Showing detailed transcript") || below.contains(where: { $0.contains("ctrl+o toggle") }) || faint && first.startsWith("/") {
                return .view(Aside.View(name: "transcript", leave: ["C-o"]))
            }
        }
        return answer(s).map { .answer($0) }
    }

    /// /btw's answer: under a rule of ▔, the questions asked aside so far, a
    /// row each (/btw …), the one answered below in bold; a blank row, then
    /// the answer, indented past them (✽ Answering… while it comes), wrapped
    /// eight columns short of the screen's edge; a blank row, and grey hints,
    /// which say Esc closes it. Taller than the screen has room for, it takes
    /// all but the top seven rows, and the arrows move it three rows at a time.
    private func answer(_ s: Screen) -> Aside.Answer? {
        guard let top = answered(s), let asked = until(top + 1, s.rows).first(where: { !row(s, $0, from: 0).isBlank }) else { return nil }
        var questions: [Int] = []
        for y in until(asked, s.rows) {
            guard Claude.btw.matches(row(s, y, from: 0)) else { break }
            questions.append(y)
        }
        guard let last = questions.last, let foot = downTo(s.rows - 1, 0).first(where: { !row(s, $0, from: 0).isBlank }) else { return nil }
        let shown = questions.first { !s.isMuted(4, $0) } ?? last
        var hint = foot
        while hint - 1 > last && indent(row(s, hint - 1, from: 0)) == 4 && s.isMuted(4, hint - 1) { hint -= 1 }
        if indent(row(s, hint, from: 0)) != 4 || !s.isMuted(4, hint) { return nil }
        let rows = until(last + 2, hint - 1).map { row(s, $0, from: 0).dropping(Claude.answerIndent).trimmedEnd() }
        let answering = rows.count == 1 && rows[0].first.map { Claude.spins.contains(String($0)) } == true && rows[0].endsWith("Answering…")
        return Aside.Answer(
            question: Claude.btw.matchEntire(row(s, shown, from: 0))!.groupValue(1).trimmed(),
            rows: answering ? nil : rows,
            width: s.cols - 8,
            taller: top <= Claude.aboveTallest,
            leave: ["Escape"],
            up: ["Up"],
            down: ["Down"],
            step: 3
        )
    }

    /// The ▔ rule over an answer aside, the bottom-most on screen, with a question asked aside below it (fullscreen mode rules its menus with ▔ too).
    private func answered(_ s: Screen) -> Int? {
        guard let top = downTo(s.rows - 1, 0).first(where: { y in s.text(0, y) == "▔" && (0..<s.cols).filter { s.text($0, y) == "▔" }.count >= s.cols / 2 }),
              let asked = until(top + 1, s.rows).first(where: { !row(s, $0, from: 0).isBlank }) else { return nil }
        return Claude.btw.matches(row(s, asked, from: 0)) ? top : nil
    }

    private static let btw = Pattern("^ {4}/btw(?: (.*))?$")

    /// How many of the screen's rows an answer aside leaves above it at most, to the conversation.
    private static let aboveTallest = 7

    /// Where an answer aside's rows start.
    private static let answerIndent = 6

    private func indent(_ row: String) -> Int { row.length - row.trimmedStart().length }

    /// The first row of Claude's prompt box, if it's on screen.
    private func box(_ s: Screen) -> Int? {
        downTo(s.rows - 2, 1).first { y in
            (s.text(0, y) == "❯" || s.text(0, y) == "!") && s.text(1, y).isBlank && rule(s, y - 1)
        }
    }

    /// Row `y`'s text from column `from`, a blank for each empty cell.
    private func row(_ s: Screen, _ y: Int, from: Int) -> String {
        var row = ""
        for x in until(from, s.cols) where !s.isSpacer(x, y) {
            let t = s.text(x, y)
            row += t.isEmpty ? " " : t
        }
        return row
    }

    /// The marks Claude's working line spins through.
    private static let spins: Set<String> = ["·", "✢", "✳", "✶", "✻", "✽", "*"]

    private static let working = Pattern("^(\\p{L}[\\p{L} '’-]*)…")

    /// Faint words in an empty box that aren't a suggestion to take.
    private static let placeholders: Set<String> = ["Press up to edit queued messages"]

    /// Claude's command is its version; its title is the conversation's name, after a mark that spins while it works.
    public func label(_ title: String) -> String? {
        let name = title.trimmingStart { !isLetterOrDigit($0) }.trimmed()
        return name.isEmpty ? nil : name
    }

    public let readsScreenToNotify = true

    /// Claude notifies of whatever it asks (a permission, a question, a plan,
    /// a server's form) once it's gone a few seconds unanswered, and of more
    /// besides (done and waiting, an agent finished, a push), each its own
    /// way, or with only the bell, as it's set to. What it asks always takes
    /// its prompt box's place: so one notice while the box is gone, or the
    /// screen couldn't be read; none while it's there, or a view of Claude's
    /// own is in its place, as that's asking nothing.
    public func notice(title: String?, body: String?, screen: Screen?) -> Notice? {
        if let screen, line(screen) != nil || aside(screen) != nil { return nil }
        return Notice(title: "Claude Code", body: "Claude needs your input")
    }

    /// Claude runs in vim mode, and where it can't be seen it may be in
    /// NORMAL (after an Esc), where keys are commands: A first goes back to
    /// INSERT at the row's end. In INSERT it's typed, and the first ^U takes it.
    public func clear(_ line: InputLine, keys: [String], unseen: Bool) -> [String] {
        (unseen && keys == clearKeys ? ["A"] : []) + clearingRows(line, keys: keys)
    }

    /// Stopped before it replies, Claude puts the message back in its box. Any
    /// message comes back by a /rewind to before it, picked from Claude's list.
    public func recall(latest: Bool, working: Bool) -> Recall? {
        latest && working ? .keys(["C-c"]) : .command("/rewind")
    }

    /// /rewind lists the user's messages, its cursor on (current) below the
    /// latest, a few at a time, with how many more there are above and below
    /// (↑ / ↓ N more): up goes to the one before, and Enter asks to confirm,
    /// quoting the message, which is the user's to answer; Esc goes back to
    /// the list. (Arrows that come in a burst, it takes for a scroll wheel,
    /// and says so over the list's rule for a while, so the list is walked
    /// by the confirmations it asks.)
    public func walk(_ prompt: Prompt, said: String) -> Walk? {
        if !prompt.quote.isEmpty {
            if !prompt.options.contains(where: { Claude.restores.contains($0.label) }) { return nil }
            return .picked(Walk.Picked(on: Claude.quotes(prompt.quote.removingWhitespace(), said.removingWhitespace()), back: ["Escape"]))
        }
        if prompt.lines.first?.text != "Rewind" { return nil }
        let shown = prompt.options.filter { $0.scroll == 0 }
        guard let at = shown.firstIndex(where: { $0.current }) else { return nil }
        func more(_ way: Int) -> Int {
            prompt.options.first { $0.scroll == way }.flatMap { Claude.count.find($0.label).flatMap { Int($0.value) } } ?? 0
        }
        let back = more(1) + shown.count - 1 - at - 1 // past (current), at the foot
        return .listed(Walk.Listed(back: back, last: back + at + more(-1), up: ["Up"], down: ["Down"], pick: ["Enter"]))
    }

    private static let count = Pattern("\\d+")

    /// Restoring the conversation from /rewind takes it back to before the message the prompt quotes.
    public func picked(_ prompt: Prompt, option: Prompt.Option) -> Effect? {
        if !Claude.restores.contains(option.label) { return nil }
        let quote = prompt.quote.removingWhitespace()
        if quote.isEmpty { return nil }
        return .rewound { said in Claude.quotes(quote, said.removingWhitespace()) }
    }

    private static let restores: Set<String> = ["Restore conversation", "Restore code and conversation"]

    /// Restored, wherever it was picked, the message Claude quoted is back in its box.
    public func settled(_ prompt: Prompt, line: InputLine?) -> Effect? {
        guard let restore = prompt.options.first(where: { Claude.restores.contains($0.label) }) else { return nil }
        let quote = prompt.quote.removingWhitespace()
        let text = line?.text.removingWhitespace() ?? ""
        if quote.isEmpty || text.isEmpty || !Claude.quotes(quote, text) { return nil }
        return picked(prompt, option: restore)
    }

    /// Whether `message` is what Claude quotes as `quote`: its words from the start, to the end unless cut short with ….
    private static func quotes(_ quote: String, _ message: String) -> Bool {
        let parts = quote.split("…")
        if !message.startsWith(parts[0]) { return false }
        var at = parts[0].length
        for part in parts.dropFirst() {
            let i = message.indexOf(part, from: at)
            if i < 0 { return false }
            at = i + part.length
        }
        return quote.endsWith("…") || message.endsWith(parts[parts.count - 1]) && (parts.count > 1 || message.length == quote.length)
    }

    private static let spaces = Pattern(" {2,}")
    private static let tried = Pattern("^Try \"(.+)\"$")

    private func rule(_ s: Screen, _ y: Int) -> Bool {
        s.text(0, y) == "─" && (0..<s.cols).filter { s.text($0, y) == "─" }.count >= s.cols / 2
    }
}
