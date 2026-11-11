import Foundation

/// The phone's half of a program's plugin: what wallflower can read off the
/// program's screen, and how to drive it beyond typing. The Mac's half says
/// which panes run it, and names it by the same id (see `Programs`).
public protocol Program: AnyObject, Sendable {
    /// The program's input line, if wallflower can see one on its screen.
    func line(_ screen: Screen) -> InputLine?

    /// Choices the program offers on its screen, to answer by touch.
    func prompt(_ screen: Screen) -> Prompt?

    /// What the program says on its screen it's doing, in its own words, while it works; nil if it doesn't say.
    func status(_ screen: Screen) -> String?

    /// Questions the program asked that wait on its screen for the user to bring them up; nil if none do.
    func asked(_ screen: Screen) -> Asked?

    /// What the program's notification says on the phone: a desktop
    /// notification (`title`, `body`), or its terminal's bell (no `body`),
    /// given its `screen` as it came if the program `readsScreenToNotify`
    /// (nil if it couldn't be read); nil: nothing. A bell says nothing
    /// unless the program has it say something: shells and editors ring it
    /// at a mistyped key.
    func notice(title: String?, body: String?, screen: Screen?) -> Notice?

    /// Whether `notice` needs the program's screen, which takes opening its pane a moment.
    var readsScreenToNotify: Bool { get }

    /// What empties a row of the input line; empty if nothing does, and the box adds to the line.
    var clearKeys: [String] { get }

    /// Keys that empty `line`, `keys` being what empties a row (the
    /// program's, or the user's own): once per row it takes, and once more.
    /// `unseen`: the program can't be seen, as under its mobile view.
    func clear(_ line: InputLine, keys: [String], unseen: Bool) -> [String]

    /// What starts a new line of what's being typed without sending it, if the program takes several; a shell runs the line.
    var newlineKeys: [String]? { get }

    /// Whether what's sent while it works waits for it to take later (queued), rather than going to what it's doing.
    var queues: Bool { get }

    /// What the program is sent to compact its conversation, if it can.
    var compact: String? { get }

    /// What takes the suggestion in the program's empty input line into the
    /// line, whole, if it shows suggestions: cut short to fit the screen
    /// ("…"), what the screen says isn't all of one.
    var acceptSuggestion: [String]? { get }

    /// Commands the composer's + offers for the program's panes, which start what's sent.
    var commands: [Command] { get }

    /// What brings a message the user sent back into the input line, to edit
    /// and send again, if the program can: `latest` being their newest, which
    /// the program is still on while `working`.
    func recall(latest: Bool, working: Bool) -> Recall?

    /// Walking `prompt`, opened by `recall`'s command, to the user's message
    /// that `said`: where the list of their messages has its cursor, or
    /// whether what picking one asks is about that message; nil if it's neither.
    func walk(_ prompt: Prompt, said: String) -> Walk?

    /// What picking `option` of `prompt` does besides answering it, if wallflower needs to know.
    func picked(_ prompt: Prompt, option: Prompt.Option) -> Effect?

    /// What answering `prompt` did besides, whoever answered it (at the Mac,
    /// say), as the input `line` the program shows once it's gone tells; nil
    /// if nothing wallflower needs to know.
    func settled(_ prompt: Prompt, line: InputLine?) -> Effect?

    /// What a tab shows for the program's window, from the terminal title it
    /// sets (`title`), in place of a name the multiplexer gave the window
    /// after the program's command; nil keeps that name.
    func label(_ title: String) -> String?

    /// The key bar its panes get until the user sets them one; programs with the same one share it.
    var keyBar: KeyDefaults? { get }

    /// Shortcuts its panes offer, on the bar's shortcuts key, besides the user's own.
    var shortcuts: [Shortcut] { get }

    /// What the program shows in its input line's place that it isn't asking,
    /// where keys are its own rather than typed (a view of its own, an answer
    /// it gives aside); nil while there's nothing.
    func aside(_ screen: Screen) -> Aside?

    /// The mode the program says on its screen it's in; nil if it doesn't say, or it can't be seen.
    func mode(_ screen: Screen) -> Mode?

    /// What steps the program on to its next mode, if it goes round modes with a key.
    var nextMode: [String]? { get }

    /// Modes the program may have, by the names `mode` reads, in no order that means anything: which it has depends on how it's set up.
    var modes: [String] { get }
}

extension Program {
    public func prompt(_ screen: Screen) -> Prompt? { nil }

    public func status(_ screen: Screen) -> String? { nil }

    public func asked(_ screen: Screen) -> Asked? { nil }

    public func notice(title: String?, body: String?, screen: Screen?) -> Notice? { body.map { Notice(title: title, body: $0) } }

    public var readsScreenToNotify: Bool { false }

    public func clear(_ line: InputLine, keys: [String], unseen: Bool) -> [String] { clearingRows(line, keys: keys) }

    /// `clear`, with the program's `clearKeys`.
    public func clear(_ line: InputLine, unseen: Bool = false) -> [String] { clear(line, keys: clearKeys, unseen: unseen) }

    /// `clear`, the program in view.
    public func clear(_ line: InputLine, keys: [String]) -> [String] { clear(line, keys: keys, unseen: false) }

    /// `keys` once per row `line` takes, and once more.
    func clearingRows(_ line: InputLine, keys: [String]) -> [String] {
        Array(repeating: keys, count: max(line.rows * 2 + 1, 0)).flatMap { $0 }
    }

    public var newlineKeys: [String]? { nil }

    public var queues: Bool { false }

    public var compact: String? { nil }

    public var acceptSuggestion: [String]? { nil }

    public var commands: [Command] { [] }

    public func recall(latest: Bool, working: Bool) -> Recall? { nil }

    public func walk(_ prompt: Prompt, said: String) -> Walk? { nil }

    public func picked(_ prompt: Prompt, option: Prompt.Option) -> Effect? { nil }

    public func settled(_ prompt: Prompt, line: InputLine?) -> Effect? { nil }

    public func label(_ title: String) -> String? { nil }

    public var keyBar: KeyDefaults? { nil }

    public var shortcuts: [Shortcut] { [] }

    public func aside(_ screen: Screen) -> Aside? { nil }

    public func mode(_ screen: Screen) -> Mode? { nil }

    public var nextMode: [String]? { nil }

    public var modes: [String] { [] }
}

/// A key bar a program ships: its `name`, its `keys` by wallflower's names
/// (`arrows`: the arrows around enter, in one key; `ctrl`, `alt`: sticky;
/// `shortcuts`: the shortcuts, first if it's left out), and
/// what the send button presses when there's nothing to send (`stop`).
public struct KeyDefaults: Hashable, Sendable {
    public var name: String
    public var keys: [String]
    public var stop: [String]

    public init(name: String, keys: [String], stop: [String] = ["C-c"]) {
        self.name = name
        self.keys = keys
        self.stop = stop
    }
}

/// A shortcut a program ships: the `keys` it sends by wallflower's names, text
/// typed as it is (`/compact` then `Enter` runs it, `/btw` alone starts what's
/// sent), and its `label`, if not the keys as the bar shows them.
public struct Shortcut: Hashable, Sendable {
    public var keys: [String]
    public var label: String?

    public init(_ keys: [String], label: String? = nil) {
        self.keys = keys
        self.label = label
    }
}

/// A command a program takes, as the + offers it: its `text`, and what it's `about`.
public struct Command: Hashable, Sendable {
    public var text: String
    public var about: String

    public init(text: String, about: String) {
        self.text = text
        self.about = about
    }
}

/// Questions waiting to be answered ("2 questions"), and the keys that bring them up.
public struct Asked: Hashable, Sendable {
    public var what: String
    public var keys: [String]

    public init(what: String, keys: [String]) {
        self.what = what
        self.keys = keys
    }
}

/// What brings back a message the user sent: `keys`, or a `command` sent as if typed.
public enum Recall: Hashable, Sendable {
    case keys([String])

    case command(String)
}

/// A prompt on the way to one of the user's messages, from the list of them, oldest first.
public enum Walk: Hashable, Sendable {
    case listed(Listed)

    case picked(Picked)

    /// The list: its cursor on the message with `back` of theirs newer than
    /// it (-1 below the newest), going `last` back at most. The keys that
    /// move the cursor `up` a message (back), or `down`, and that `pick` the
    /// one under it.
    public struct Listed: Hashable, Sendable {
        public var back: Int
        public var last: Int
        public var up: [String]
        public var down: [String]
        public var pick: [String]

        public init(back: Int, last: Int, up: [String], down: [String], pick: [String]) {
            self.back = back
            self.last = last
            self.up = up
            self.down = down
            self.pick = pick
        }
    }

    /// What picking one asks: whether it's `on` the message walked to, and the keys `back` to the list, its cursor where it was.
    public struct Picked: Hashable, Sendable {
        public var on: Bool
        public var back: [String]

        public init(on: Bool, back: [String]) {
            self.on = on
            self.back = back
        }
    }
}

/// What a program shows in its input line's place besides asking something, where keys are its own; `leave` puts it away.
public enum Aside: Hashable, Sendable {
    case view(View)

    case answer(Answer)

    public var leave: [String] {
        switch self {
        case .view(let v): v.leave
        case .answer(let a): a.leave
        }
    }

    /// A view of its own, as Claude's detailed transcript: what it is (`name`).
    public struct View: Hashable, Sendable {
        public var name: String
        public var leave: [String]

        public init(name: String, leave: [String]) {
            self.name = name
            self.leave = leave
        }
    }

    /// An answer to a question asked aside, as with Claude's /btw: the
    /// `question`, and the `rows` of its answer that show (nil while it's
    /// being answered), wrapped at `width` and scrolled `step` rows at a time
    /// by `up` and `down` when it's `taller` than its room.
    public struct Answer: Hashable, Sendable {
        public var question: String
        public var rows: [String]?
        public var width: Int
        public var taller: Bool
        public var leave: [String]
        public var up: [String]
        public var down: [String]
        public var step: Int

        public init(question: String, rows: [String]?, width: Int, taller: Bool, leave: [String], up: [String], down: [String], step: Int) {
            self.question = question
            self.rows = rows
            self.width = width
            self.taller = taller
            self.leave = leave
            self.up = up
            self.down = down
            self.step = step
        }

        /// What's been read of the answer: `kept`, read before (scrolled
        /// elsewhere, or less of it written), with the rows showing now laid
        /// over where they meet. Rows that don't meet it are another stretch,
        /// or another answer, and stand alone.
        public func over(_ kept: [String]) -> [String] {
            guard let shown = rows else { return kept }
            if kept.isEmpty || shown.isEmpty { return shown }
            if contains(kept, shown) { return kept }
            // A row or two alike (blanks between paragraphs) isn't enough to say they meet, unless it's all that was kept.
            func meets(_ k: Int) -> Bool { k >= 2 || k == kept.count }
            for k in downTo(min(kept.count, shown.count), 1) where meets(k) && Array(kept.suffix(k)) == Array(shown.prefix(k)) {
                return kept + shown.dropFirst(k)
            }
            for k in downTo(min(kept.count, shown.count), 1) where meets(k) && Array(shown.suffix(k)) == Array(kept.prefix(k)) {
                return shown + kept.dropFirst(k)
            }
            return shown
        }

        /// `rows` of the answer as text: each row the program wrapped (the next one's first word wouldn't fit beside it) joined to the next.
        public func text(_ rows: [String]) -> String {
            var out = ""
            var previous: String?
            for row in rows {
                var next = row
                if let previous {
                    let word = row.trimmedStart().substringBefore(" ")
                    if !previous.isEmpty && !word.isEmpty && previous.length + 1 + word.length > width {
                        out += " "
                        next = row.trimmedStart()
                    } else {
                        out += "\n"
                    }
                }
                out += next
                previous = row
            }
            return out.trimming("\n")
        }

        private func contains(_ all: [String], _ part: [String]) -> Bool {
            guard part.count <= all.count else { return false }
            return (0...(all.count - part.count)).contains { Array(all[$0..<($0 + part.count)]) == part }
        }
    }
}

/// A mode a program says it's in: its `name`, and the `mark` it shows with it (empty if it's not been seen).
public struct Mode: Hashable, Sendable {
    public var name: String
    public var mark: String

    public init(name: String, mark: String) {
        self.name = name
        self.mark = mark
    }
}

/// The modes a program goes round, a `Program.nextMode` at a time, as far as
/// they've been seen: which came after which, and how each is marked. Those
/// the program may have (`Program.modes`) stand in for those not seen yet, in
/// no order; one a whole round didn't come to goes, till it's seen.
public final class Cycle {
    private let known: [String]
    private var after: [String: String] = [:]
    private var marks: [(name: String, mark: String)] = []
    private var missed: Set<String> = []

    public init(known: [String]) {
        self.known = known
    }

    /// `mode` is on screen.
    public func saw(_ mode: Mode) {
        if let i = marks.firstIndex(where: { $0.name == mode.name }) {
            marks[i].mark = mode.mark
        } else {
            marks.append((mode.name, mode.mark))
        }
        missed.remove(mode.name)
    }

    /// `to` came up one step on from `from`.
    public func stepped(from: Mode, to: Mode) {
        saw(from)
        saw(to)
        after[from.name] = to.name
    }

    /// A whole round didn't come to `name`: what was seen around it is out of date.
    public func missed(_ name: String) {
        missed.insert(name)
        after[name] = nil
        after = after.filter { $0.value != name }
    }

    /// The modes from `at`, as far round as they've been seen to follow, then the rest, unordered.
    public func from(_ at: Mode) -> [Mode] {
        var out = [at]
        var name = at.name
        while let next = after[name], !out.contains(where: { $0.name == next }) {
            out.append(Mode(name: next, mark: mark(next)))
            name = next
        }
        for n in marks.map(\.name) + known where !missed.contains(n) && !out.contains(where: { $0.name == n }) {
            out.append(Mode(name: n, mark: mark(n)))
        }
        return out
    }

    private func mark(_ name: String) -> String { marks.first { $0.name == name }?.mark ?? "" }
}

/// A notification as the phone shows it.
public struct Notice: Hashable, Sendable {
    public var title: String?
    public var body: String

    public init(title: String?, body: String) {
        self.title = title
        self.body = body
    }
}

/// Something picking an option did that the program won't record until later.
public enum Effect: Sendable {
    /// Its conversation went back to before the latest of the user's messages that `said` matches.
    case rewound(said: @Sendable (String) -> Bool)
}

/// A program the phone has no half of its own for, as a plugin the user gave
/// the gateway describes it, in data, since the phone loads no code: its key
/// bar, commands, shortcuts, and the keys that start a new line and empty
/// one. It reads a line only from prompt marks (OSC 133), if it makes them.
public final class Supplied: Program {
    public let keyBar: KeyDefaults?
    public let commands: [Command]
    public let newlineKeys: [String]?
    public let clearKeys: [String]
    public let shortcuts: [Shortcut]
    private let shell: Shell

    public init(keyBar: KeyDefaults?, commands: [Command], newlineKeys: [String]?, clearKeys: [String], shortcuts: [Shortcut] = []) {
        self.keyBar = keyBar
        self.commands = commands
        self.newlineKeys = newlineKeys
        self.clearKeys = clearKeys
        self.shortcuts = shortcuts
        self.shell = Shell(clearKeys: clearKeys, keyBar: keyBar)
    }

    public func line(_ screen: Screen) -> InputLine? { shell.line(screen) }
}

/// The programs wallflower ships, and those the gateway's plugins describe, by the id the gateway names a pane's program by.
public enum Programs {
    private static let shells = KeyDefaults(name: "shell", keys: ["shortcuts", "ctrl", "Tab", "arrows", "C-r", "C-c", "C-d", "Escape"])

    /// Commands more than keys, as a shell's keys are its own; these do the same in any.
    private static let shellShortcuts = [
        Shortcut(["clear", "Enter"]),
        Shortcut(["cd -", "Enter"]),
        Shortcut(["!!", "Enter"]),
        Shortcut(["C-z"], label: "suspend"),
    ]
    private static let other = Shell()

    public static let builtin: [(id: String, program: any Program)] = [
        ("claude", Claude.shared),
        ("codex", Codex.shared),
        ("zsh", Shell(clearKeys: ["C-x", "C-k"], keyBar: shells, shortcuts: shellShortcuts)), // kill-buffer, as zsh binds it for emacs mode
        ("shell", Shell(keyBar: shells, shortcuts: shellShortcuts)),
    ]

    private static let builtinById = Dictionary(uniqueKeysWithValues: builtin.map { ($0.id, $0.program) })

    /// A program wallflower ships, by its id; nil if it's not one.
    public static func builtin(id: String) -> (any Program)? { builtinById[id.lowercased()] }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var current: [String: any Program] = [:]

    private static var table: [String: any Program] { lock.withLock { current } }

    /// The programs the gateway's plugins describe, by id, in place of those before.
    public static func supply(_ programs: [String: any Program]) {
        let byId = Dictionary(programs.map { ($0.key.lowercased(), $0.value) }, uniquingKeysWith: { _, last in last })
        lock.withLock { current = byId }
    }

    /// The ids of the programs the gateway's plugins describe.
    public static var suppliedIds: Set<String> { Set(table.keys).subtracting(builtinById.keys) }

    /// A program a plugin of the user's describes, by its id; nil if it's one wallflower ships, or none is.
    public static func supplied(_ id: String?) -> (any Program)? {
        guard let id = id?.lowercased(), builtinById[id] == nil else { return nil }
        return table[id]
    }

    /// A pane's program, by its id; one nothing describes (or none) is a plain terminal's.
    public static func of(_ id: String?) -> any Program {
        guard let id = id?.lowercased() else { return other }
        return builtinById[id] ?? table[id] ?? other
    }
}
