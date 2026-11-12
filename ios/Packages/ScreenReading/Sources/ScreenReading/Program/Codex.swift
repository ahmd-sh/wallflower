/// OpenAI's Codex CLI: its composer, the approvals and questions it asks
/// with, and what it says it's doing. What's sent while it works goes into
/// the turn at once (Tab would queue it instead), and Esc stops it; ^C quits.
public final class Codex: Program {
    public static let shared = Codex()

    private init() {}

    public let clearKeys = ["C-u"] // a row at a time

    public let newlineKeys: [String]? = ["C-j"]

    public let compact: String? = "/compact"

    public let commands = [
        Command(text: "/model", about: "switch model and reasoning effort"),
        Command(text: "/new", about: "start a new conversation"),
        Command(text: "/resume", about: "go back to an earlier conversation"),
        Command(text: "/compact", about: "summarize the conversation, freeing context"),
        Command(text: "/diff", about: "what's changed, untracked files too"),
        Command(text: "/review", about: "review the changes for issues"),
        Command(text: "/plan", about: "switch to plan mode"),
        Command(text: "/status", about: "the session's settings and tokens used"),
    ]

    public let keyBar: KeyDefaults? = KeyDefaults(
        name: "codex",
        keys: ["shortcuts", "Escape", "S-Left", "BTab", "arrows", "Tab", "1", "2", "3"],
        stop: ["Escape"]
    )

    /// A few, to show what a shortcut can be: keys (← from its empty
    /// composer brings up its agents), commands run as sent, and /side,
    /// which starts what's sent, the question to ask aside.
    public let shortcuts: [Shortcut] = [
        Shortcut(["Left"], label: "agents"),
        Shortcut(["F4"], label: "activity"),
        Shortcut(["/model", "Enter"]),
        Shortcut(["/diff", "Enter"]),
        Shortcut(["/side"]),
    ]

    /// The composer: a first row starting `› `, rows under it indented two,
    /// then a blank row and the footer, `  <model> <mode> · <directory>…`,
    /// at the foot of the screen; history's messages start `› ` too, but
    /// nothing like that comes after them. Codex wraps long lines itself, at
    /// words, three columns short of the screen's edge, and breaks a word too
    /// long for a row where the row ends. Faint text is the placeholder.
    public func line(_ screen: Screen) -> InputLine? {
        let s = screen
        guard let first = composer(s) else { return nil }
        var end = first + 1
        while end < s.rows && s.text(0, end).isBlank && s.text(1, end).isBlank && !text(s, end, 2).isBlank { end += 1 }
        let width = s.cols - 3
        var out = ""
        var previous: String?
        for r in first..<end {
            var row = ""
            for x in until(2, s.cols) where !s.isSpacer(x, r) {
                let c = s.text(x, r)
                row += c.isBlank ? " " : s.isFaint(x, r) ? "" : c
            }
            let line = row.trimmedEnd()
            if let previous {
                let word = line.substringBefore(" ").length
                if previous.length >= width && previous.substringAfterLast(" ").length + word > width {
                    // A word too long for any row is broken where the row ends.
                } else if !line.isEmpty && previous.length + 1 + word > width {
                    out += " "
                } else {
                    out += "\n"
                }
            }
            out += line
            previous = line
        }
        return InputLine(text: out.trimmed(), rows: end - first)
    }

    /// The composer's first row, if it's on screen: the lowest `› ` row with the footer below it, past a blank row.
    private func composer(_ s: Screen) -> Int? { foot(s)?.composer }

    /// The composer's first row and its footer's.
    private func foot(_ s: Screen) -> (composer: Int, footer: Int)? {
        for footer in downTo(s.rows - 1, max(2, s.rows - 3)) {
            if !s.text(0, footer).isBlank || !s.text(1, footer).isBlank || !text(s, footer, 2).contains(" · ") { continue }
            if !text(s, footer - 1, 0).isBlank { continue }
            var y = footer - 2
            while y >= 0 && s.text(0, y).isBlank && s.text(1, y).isBlank && !text(s, y, 2).isBlank { y -= 1 }
            if y >= 0 && s.text(0, y) == "›" && s.text(1, y).isBlank && !Codex.option.matches(text(s, y, 0)) { return (y, footer) }
        }
        return nil
    }

    /// In plan mode, Codex says so at its footer's end ("Plan mode"); in its default mode, nothing does. Shift+tab goes between them.
    public func mode(_ screen: Screen) -> Mode? {
        guard let (_, footer) = foot(screen) else { return nil }
        let name = Codex.planning.find(text(screen, footer, 2)) != nil ? "plan" : "default"
        return Mode(name: name, mark: "", hue: hue(name))
    }

    public let nextMode: [String]? = ["BTab"]

    public let modes = ["default", "plan"]

    public func hue(_ mode: String) -> Hue { mode == "plan" ? .aqua : .grey }

    private static let planning = Pattern("\\sPlan mode$")

    private static let option = Pattern("^›\\s+\\d{1,2}\\.\\s.*")

    /// What it asks takes the composer's place: an approval, or the picker for
    /// its questions. No rule opens it: the conversation's rows come right
    /// before, each its own • item (↪ for an answer), and aren't what it asks.
    /// Asked whether to trust a folder, a number doesn't answer: 1 only moves
    /// to trust, which Enter confirms, and 2 leaves at once.
    public func prompt(_ screen: Screen) -> Prompt? {
        if composer(screen) != nil || center(screen) != nil { return nil }
        guard var p = Prompt.read(screen) else { return nil }
        let from = (p.lines.lastIndex { let t = $0.text.trimmedStart(); return t.startsWith("•") || t.startsWith("↪") } ?? -1) + 1
        p.lines = Array(p.lines.dropFirst(from).drop { $0.text.isBlank })
        if p.lines.contains(where: { Codex.trust.find($0.text) != nil }) { p.numbers = .ignored }
        return p
    }

    private static let trust = Pattern("(?i)\\btrust\\b")

    /// While it works, Codex says so on a row of its own above the composer
    /// (or its questions): a mark, its word, then how long and how to stop
    /// it ("• Working (7s • esc to interrupt) · 1 background terminal…").
    public func status(_ screen: Screen) -> String? {
        let s = screen
        for y in downTo(s.rows - 1, max(0, s.rows - 24)) {
            if s.text(0, y) != "•" || !s.text(1, y).isBlank { continue }
            if let m = Codex.working.find(text(s, y, 2)) { return m.groupValue(1) }
        }
        return nil
    }

    private static let working = Pattern("^(\\p{L}[\\p{L} '’-]*?) \\((?:\\d+[hms] ?)+• \\S+ to interrupt\\)")

    /// The questions Codex asks while it works wait below its working row,
    /// "? 2 questions", until the user brings them up (⇧←) to answer, while
    /// it carries on.
    public func asked(_ screen: Screen) -> Asked? {
        let s = screen
        if composer(s) == nil { return nil }
        for y in downTo(s.rows - 1, max(0, s.rows - 16)) {
            if let m = Codex.questions.find(text(s, y, 0)) { return Asked(what: m.groupValue(1), keys: ["S-Left"]) }
        }
        return nil
    }

    private static let questions = Pattern("^\\s+\\?\\s+(\\d+ questions?)\\b")

    /// Codex's title is the conversation's name and the project's, after what it's doing; its name alone is the project's, until it names it.
    public func label(_ title: String) -> String? {
        let bare = Codex.needs.replace(title, with: "").trimmingStart { isWhitespace($0) || (0x2800...0x28FF).contains($0) }
        let parts = bare.components(separatedBy: " | ")
        if parts.count < 2 { return nil }
        let name = parts.dropLast().joined(separator: " | ").trimmed()
        return name.isEmpty ? nil : name
    }

    private static let needs = Pattern("^\\s*\\[[^\\]]*]\\s*Action Required(\\s*\\|\\s*|$)")

    /// Its agent command center (← from an empty composer), its own screen:
    /// a heading, the filters, a rule, then its tasks under " Tasks", in
    /// groups (faint: "~/dev/wallflower  24", "Pinned  1"), each task its
    /// mark and name, the cursor's row "› " and lit; "↑" and "↓" where more
    /// is out of sight; its keys at the foot. A filter's count is its own;
    /// the current one isn't faint, and "‹" or "›" stand for more of them:
    /// it shows those that fit, and the rest are said too, a Tab or two away.
    /// Of the marks, ! needs the user and ○ is nothing new; ● is working
    /// (green) or needs the user or is new since it was looked at (blue),
    /// told apart by the filter, or by nothing working or needing the user.
    /// Its keys (?) show over the filters, in the tasks' place.
    public func aside(_ screen: Screen) -> Aside? {
        let s = screen
        guard let top = center(s) else { return nil }
        let seen = filters(s, top + 1)
        let current = seen.first { $0.current }?.name
        let working = seen.first { $0.name == "Working" }?.count
        let needing = seen.first { $0.name == "Needs you" }?.count
        // Its keys, over the filters: a view of its own on its own.
        if top + 3 < s.rows && text(s, top + 3, 0).trimmed() == "Task shortcuts" { return .view(Aside.View(name: "agents' keys", leave: ["Escape"])) }
        var foot = s.rows - 1
        while foot > top && text(s, foot, 0).isBlank { foot -= 1 }
        if !Codex.keys.containsMatch(in: text(s, foot, 0)) { foot += 1 }
        var groups: [Aside.Tasks.Group] = []
        var name: String?
        var count = ""
        var tasks: [Aside.Tasks.Task] = []
        var above = false, below = false
        var says: String?
        func close() {
            if name != nil || !tasks.isEmpty { groups.append(Aside.Tasks.Group(name: name ?? "", count: count, tasks: tasks)) }
            name = nil
            count = ""
            tasks = []
        }
        for y in until(top + 3, foot) {
            let row = text(s, y, 0)
            if row.isBlank { continue }
            let rest = row.trimmedStart()
            let at = row.length - rest.length
            if rest.startsWith("↑") {
                above = true
            } else if rest.startsWith("↓") {
                below = true
            } else if s.isFaint(at, y) && at <= 2 && Codex.heading.matches(rest) {
                close()
                let m = Codex.gap.find(rest)!
                name = rest.substring(0, m.range.lowerBound)
                count = rest.substring(m.range.upperBound)
            } else if s.isFaint(at, y) && at > 2 {
                // " Tasks"
            } else if let m = Codex.task.find(row) {
                let mark: Aside.Tasks.Mark
                switch m.groupValue(2) {
                case "!": mark = .needsYou
                case "○": mark = .idle
                default:
                    if current == "Working" { mark = .working }
                    else if current == "Needs you" { mark = .needsYou }
                    else if working == 0 && needing == 0 { mark = .updated }
                    else { mark = .lit }
                }
                tasks.append(Aside.Tasks.Task(name: m.groupValue(3), mark: mark, current: !m.groupValue(1).isEmpty))
            } else if groups.isEmpty && tasks.isEmpty {
                says = rest
            }
        }
        close()
        return .tasks(Aside.Tasks(
            named: "agents",
            filters: Codex.every(seen),
            groups: groups,
            above: above,
            below: below,
            says: groups.isEmpty ? says : nil,
            leave: ["Escape"],
            up: ["Up"],
            down: ["Down"],
            open: ["Enter"],
            next: ["Tab"],
            previous: ["BTab"],
            pageUp: ["PageUp"],
            pageDown: ["PageDown"],
            new: ["n"]
        ))
    }

    /// The agent command center's heading row, near the top, if it's up.
    private func center(_ s: Screen) -> Int? {
        until(0, min(3, s.rows - 3)).first { text(s, $0, 0).trimmedStart().startsWith("Agent command center") }
    }

    /// The filters on row `y`: each a name, then how many, apart from the next; the one current isn't faint.
    private func filters(_ s: Screen, _ y: Int) -> [Aside.Tasks.Filter] {
        var row = ""
        var at: [Int] = []
        for x in until(0, s.cols) where !s.isSpacer(x, y) {
            let t = s.text(x, y).isEmpty ? " " : s.text(x, y)
            row += t
            at += Array(repeating: x, count: t.length)
        }
        return Codex.filter.findAll(row).map { m in
            Aside.Tasks.Filter(name: m.groupValue(1), count: Int(m.groupValue(2)) ?? 0, current: !s.isFaint(at[m.range.lowerBound], y))
        }
    }

    /// Its filters, in its order, from `seen`, those it shows: the ones out of
    /// sight, how many they hold unknown. As they are if they aren't a run of
    /// these, as another version's may not be.
    private static func every(_ seen: [Aside.Tasks.Filter]) -> [Aside.Tasks.Filter] {
        let at = seen.map { allFilters.firstIndex(of: $0.name) ?? -1 }
        guard let first = at.first, first >= 0, at.enumerated().allSatisfy({ i, n in n == first + i }) else { return seen }
        return allFilters.map { name in seen.first { $0.name == name } ?? Aside.Tasks.Filter(name: name, count: nil, current: false) }
    }

    private static let allFilters = ["All", "Needs you", "Working", "Ready", "Inactive"]

    private static let filter = Pattern("(\\p{L}[\\p{L} ]*?) (\\d+)(?= |$)")

    private static let task = Pattern("^\\s*(›)?\\s*([^\\p{L}\\p{N}\\s])\\s(.+?)\\s*$")

    private static let keys = Pattern("\\besc back\\b")

    private static let heading = Pattern("\\S.*?\\s{2,}\\d.*")

    private static let gap = Pattern("\\s{2,}")

    private func text(_ s: Screen, _ y: Int, _ from: Int) -> String {
        var b = ""
        for x in until(from, s.cols) where !s.isSpacer(x, y) {
            let t = s.text(x, y)
            b += t.isEmpty ? " " : t
        }
        return b.trimmedEnd()
    }
}
