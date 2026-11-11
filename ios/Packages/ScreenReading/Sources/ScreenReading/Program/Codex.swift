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
        keys: ["Escape", "S-Left", "BTab", "arrows", "Tab", "1", "2", "3", "C-t"],
        stop: ["Escape"]
    )

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
    private func composer(_ s: Screen) -> Int? {
        for footer in downTo(s.rows - 1, max(2, s.rows - 3)) {
            if !s.text(0, footer).isBlank || !s.text(1, footer).isBlank || !text(s, footer, 2).contains(" · ") { continue }
            if !text(s, footer - 1, 0).isBlank { continue }
            var y = footer - 2
            while y >= 0 && s.text(0, y).isBlank && s.text(1, y).isBlank && !text(s, y, 2).isBlank { y -= 1 }
            if y >= 0 && s.text(0, y) == "›" && s.text(1, y).isBlank && !Codex.option.matches(text(s, y, 0)) { return y }
        }
        return nil
    }

    private static let option = Pattern("^›\\s+\\d{1,2}\\.\\s.*")

    /// What it asks takes the composer's place: an approval, or the picker for its questions.
    public func prompt(_ screen: Screen) -> Prompt? { composer(screen) != nil ? nil : Prompt.read(screen) }

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

    private func text(_ s: Screen, _ y: Int, _ from: Int) -> String {
        var b = ""
        for x in until(from, s.cols) where !s.isSpacer(x, y) {
            let t = s.text(x, y)
            b += t.isEmpty ? " " : t
        }
        return b.trimmedEnd()
    }
}
