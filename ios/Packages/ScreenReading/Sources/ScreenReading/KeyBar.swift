/// A key on the bar: what it shows, and the keys it sends by wallflower's
/// names (`Escape`, `C-c`, or literal text). A `sticky` key waits for the next
/// key, from the bar or the keyboard, and goes with it. With `bindings`, the
/// multiplexer's key bindings apply, as if typed on a keyboard attached to it;
/// `prefix` is its prefix key. A `cluster` gathers keys, each its own, laid
/// out as arrow keys with two more above: `keys` up, left, the one top left,
/// right, down, and the one top right. A `shortcuts` key sends none of its
/// own: it offers the pane's shortcuts (`KeyProfile.shortcuts(for:)`).
public struct BarKey: Codable, Hashable, Sendable {
    public var label: String
    public var keys: [String]
    public var sticky: Bool
    public var bindings: Bool
    public var cluster: Bool
    public var shortcuts: Bool

    public init(label: String, keys: [String], sticky: Bool = false, bindings: Bool = false, cluster: Bool = false, shortcuts: Bool = false) {
        self.label = label
        self.keys = keys
        self.sticky = sticky
        self.bindings = bindings
        self.cluster = cluster
        self.shortcuts = shortcuts
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        label = try c.decode(String.self, forKey: .label)
        keys = try c.decode([String].self, forKey: .keys)
        sticky = try c.decodeIfPresent(Bool.self, forKey: .sticky) ?? false
        bindings = try c.decodeIfPresent(Bool.self, forKey: .bindings) ?? false
        cluster = try c.decodeIfPresent(Bool.self, forKey: .cluster) ?? false
        shortcuts = try c.decodeIfPresent(Bool.self, forKey: .shortcuts) ?? false
    }

    /// This sticky key with the next one: a modifier (`C-`, `M-`) joins it, anything else goes first.
    public func then(_ next: BarKey) -> BarKey {
        let first = keys.first ?? ""
        let joined: [String] = first.endsWith("-") && !next.keys.isEmpty ? [first + next.keys[0]] + next.keys.dropFirst() : keys + next.keys
        return BarKey(label: "\(label) \(next.label)", keys: joined, bindings: bindings || next.bindings)
    }

    /// Whether `label` is the one wallflower gives these keys, rather than one the user wrote.
    public var hasOwnLabel: Bool { label == KeyBar.label(of: keys) || KeyBar.common.contains { $0.label == label && $0.keys == keys } }

    /// A character typed on the keyboard, as a key.
    public static func typed(_ c: Character) -> BarKey {
        let key = switch c {
        case " ": "Space"
        case "\n": "Enter"
        default: String(c)
        }
        return BarKey(label: String(c), keys: [key])
    }
}

/// The bar for some programs.
public struct KeyProfile: Codable, Hashable, Sendable {
    public var name: String
    /// As panes report them (claude, zsh, hntui), or as the gateway names their program (shell).
    public var programs: [String]
    public var keys: [BarKey]
    /// What the send button sends when the box is empty.
    public var stop: [String]
    /// What empties a row of the app's input line, as the user set it; nil leaves it to the program (Program.clearKeys).
    public var clear: [String]?
    /// What the send button presses where the keyboard types straight into the pane, as the user set it; nil is Enter.
    public var send: [String]?
    /// What the keyboard's return key presses there, as the user set it; nil leaves it to the program.
    public var returnKey: [String]?
    /// The user's own shortcuts, after the program's.
    public var shortcuts: [BarKey]
    /// The program's shortcuts the user hid, by label.
    public var hidden: [String]

    public init(
        name: String, programs: [String] = [], keys: [BarKey], stop: [String] = ["C-c"], clear: [String]? = nil, send: [String]? = nil, returnKey: [String]? = nil,
        shortcuts: [BarKey] = [], hidden: [String] = []
    ) {
        self.name = name
        self.programs = programs
        self.keys = keys
        self.stop = stop
        self.clear = clear
        self.send = send
        self.returnKey = returnKey
        self.shortcuts = shortcuts
        self.hidden = hidden
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        programs = try c.decodeIfPresent([String].self, forKey: .programs) ?? []
        keys = try c.decode([BarKey].self, forKey: .keys)
        stop = try c.decodeIfPresent([String].self, forKey: .stop) ?? ["C-c"]
        clear = try c.decodeIfPresent([String].self, forKey: .clear)
        send = try c.decodeIfPresent([String].self, forKey: .send)
        returnKey = try c.decodeIfPresent([String].self, forKey: .returnKey)
        shortcuts = try c.decodeIfPresent([BarKey].self, forKey: .shortcuts) ?? []
        hidden = try c.decodeIfPresent([String].self, forKey: .hidden) ?? []
    }

    public func sendKeys() -> [String] { send ?? enter }

    /// Whether it's the bar for a pane running `command`, or the program the gateway named `program`.
    public func matches(_ command: String?, program: String? = nil) -> Bool {
        programs.contains { equalsIgnoringCase($0, command) || equalsIgnoringCase($0, program) }
    }

    /// A new line, in a program that takes several (Program.newlineKeys); else Enter.
    public func returnKeys(_ program: any Program) -> [String] { returnKey ?? program.newlineKeys ?? enter }

    /// What held ctrl, or the shortcuts key, offers in a pane running `program`: its shortcuts the user didn't hide, then theirs.
    public func shortcuts(for program: any Program) -> [BarKey] {
        program.shortcuts.map(KeyBar.shortcut).filter { !hidden.contains($0.label) } + shortcuts
    }
}

/// A shortcut's keys as they go: keys pressed, or text typed, with Enter a moment after it if `enter`.
public enum Step: Hashable, Sendable {
    case keys([String])
    case text(String, enter: Bool)
}

private let enter = ["Enter"]

private func equalsIgnoringCase(_ a: String, _ b: String?) -> Bool { b.map { a.lowercased() == $0.lowercased() } ?? false }

/// Which keys sit next to the composer. The focused pane's program picks the
/// profile, first match wins, else `default`. The programs wallflower ships
/// bring their own (`Program.keyBar`) until the user changes them.
public struct KeyBar: Codable, Hashable, Sendable {
    public var profiles: [KeyProfile]
    public var `default`: KeyProfile

    public init(profiles: [KeyProfile], default: KeyProfile) {
        self.profiles = profiles
        self.default = `default`
    }

    public func profile(for command: String, program: String? = nil) -> KeyProfile {
        profiles.first { $0.matches(command, program: program) }
            ?? Programs.supplied(program)?.keyBar.map { d in
                // Its shortcuts are the default's, which its settings are until the user makes it a profile.
                KeyProfile(
                    name: d.name, programs: program.map { [$0] } ?? [], keys: d.keys.map(KeyBar.key), stop: d.stop,
                    shortcuts: self.default.shortcuts, hidden: self.default.hidden
                )
            }
            ?? self.default
    }

    /// The arrows, with enter above left and backspace above right, in one key; `arrows` by name (the order is saved with a bar).
    public static let arrows = BarKey(label: "arrows", keys: ["Up", "Left", "Enter", "Right", "Down", "BSpace"], cluster: true)

    /// The pane's shortcuts, for a bar without ctrl to hold; `shortcuts` by name.
    public static let shortcutsKey = BarKey(label: "shortcuts", keys: [], shortcuts: true)

    /// Keys offered when editing a bar.
    public static let common: [BarKey] = ([
        ("esc", "Escape"), ("tab", "Tab"), ("⇧tab", "BTab"), ("⏎", "Enter"),
        ("↑", "Up"), ("↓", "Down"), ("←", "Left"), ("→", "Right"),
        ("home", "Home"), ("end", "End"), ("pgup", "PageUp"), ("pgdn", "PageDown"),
        ("⌫", "BSpace"), ("space", "Space"), ("⌥⏎", "M-Enter"),
        ("^c", "C-c"), ("^d", "C-d"), ("^z", "C-z"), ("^r", "C-r"), ("^l", "C-l"),
        ("^a", "C-a"), ("^e", "C-e"), ("^u", "C-u"), ("^w", "C-w"), ("^o", "C-o"),
        ("^t", "C-t"), ("^b", "C-b"),
        ("y", "y"), ("n", "n"), ("q", "q"), ("1", "1"), ("2", "2"), ("3", "3"),
        ("/", "/"), ("|", "|"), ("~", "~"), ("-", "-"), ("`", "`"),
    ] as [(String, String)]).map { label, key in BarKey(label: label, keys: [key]) } + [
        arrows,
        shortcutsKey,
        BarKey(label: "pfx", keys: ["prefix"], sticky: true, bindings: true),
        BarKey(label: "ctrl", keys: ["C-"], sticky: true),
        BarKey(label: "alt", keys: ["M-"], sticky: true),
    ]

    /// How `keys` show on a key: ^c, ⌥⏎, ⇧↑, esc :w ⏎.
    public static func label(of keys: [String]) -> String {
        keys.map { key in
            let (held, rest) = KeyBar.held(key)
            return held.map { modifiers[$0]! }.joined() + (named[rest] ?? rest)
        }.joined(separator: " ")
    }

    /// What `key` sends, in words; a sticky one, with a key.
    public static func name(of key: BarKey) -> String {
        if key.cluster { return "arrows, enter, backspace" }
        if key.shortcuts { return "shortcuts" }
        return name(of: key.keys) + (key.sticky ? " + key" : "")
    }

    /// `keys` in words: ctrl c, shift tab, escape :w enter.
    public static func name(of keys: [String]) -> String {
        keys.map { key in
            let (held, rest) = KeyBar.held(key)
            return (held.map { said["\($0)-"]! } + [said[rest] ?? rest]).joined(separator: " ")
        }.joined(separator: " ")
    }

    /// The modifiers held with a key (C, M, S) and the key: C-M-Left is C, M and Left.
    public static func held(_ key: String) -> (held: [Character], rest: String) {
        var rest = Array(key.utf16)
        var held: [Character] = []
        while rest.count > 2 && rest[1] == unit("-"), let m = Unicode.Scalar(rest[0]).map(Character.init), modifiers[m] != nil {
            held.append(m)
            rest.removeFirst(2)
        }
        return (held, String(units: rest))
    }

    private static let modifiers: [Character: String] = ["C": "^", "M": "⌥", "S": "⇧"]
    private static let named = [
        "Enter": "⏎", "Escape": "esc", "Tab": "tab", "BTab": "⇧tab", "BSpace": "⌫", "Space": "space",
        "Up": "↑", "Down": "↓", "Left": "←", "Right": "→", "Home": "home", "End": "end",
        "PageUp": "pgup", "PageDown": "pgdn", "DC": "del", "IC": "ins", "prefix": "pfx",
    ]
    private static let said = [
        "C-": "ctrl", "M-": "alt", "S-": "shift",
        "Enter": "enter", "Escape": "escape", "Tab": "tab", "BTab": "shift tab", "BSpace": "backspace", "Space": "space",
        "Up": "up", "Down": "down", "Left": "left", "Right": "right", "Home": "home", "End": "end",
        "PageUp": "page up", "PageDown": "page down", "DC": "delete", "IC": "insert", "prefix": "prefix",
    ]

    /// Whether `name` is a key by wallflower's names (Enter, C-c, F5, prefix), rather than text to type.
    public static func isKey(_ name: String) -> Bool {
        let (held, rest) = KeyBar.held(name)
        return keyNames.contains(rest) || !held.isEmpty && rest.utf16.count == 1
    }

    private static let keyNames = Set(named.keys).union((1...12).map { "F\($0)" })

    /// Keys as the user writes them, space-separated: words that are
    /// keys by name go as they are, and the words between them as one
    /// text: `/model opus Enter` types /model opus, then presses Enter.
    public static func keys(of sends: String) -> [String] {
        var out: [String] = []
        var text: String?
        for word in sends.split(separator: " ").map(String.init) {
            if isKey(word) {
                if let t = text { out.append(t) }
                text = nil
                out.append(word)
            } else {
                text = text.map { "\($0) \(word)" } ?? word
            }
        }
        if let t = text { out.append(t) }
        return out
    }

    /// How `keys` go: keys pressed together, and text typed, Enter after
    /// it pressed a moment later, as the box's text is sent, so a program
    /// that reads them at once (Claude Code) doesn't take a long line and
    /// its Enter for a paste.
    public static func steps(_ keys: [String]) -> [Step] {
        var out: [Step] = []
        var pressed: [String] = []
        var i = 0
        while i < keys.count {
            let key = keys[i]
            if isKey(key) {
                pressed.append(key)
                i += 1
                continue
            }
            if !pressed.isEmpty { out.append(.keys(pressed)) }
            pressed = []
            let enter = i + 1 < keys.count && keys[i + 1] == "Enter"
            out.append(.text(key, enter: enter))
            i += enter ? 2 : 1
        }
        if !pressed.isEmpty { out.append(.keys(pressed)) }
        return out
    }

    /// A program's shortcut, as the bar offers it.
    public static func shortcut(_ s: Shortcut) -> BarKey { BarKey(label: s.label ?? label(of: s.keys), keys: s.keys) }

    /// Held down, these repeat.
    public static let repeating: Set<String> = ["Up", "Down", "Left", "Right", "BSpace", "PageUp", "PageDown"]

    private static func keys(_ labels: String...) -> [BarKey] { labels.map { l in common.first { $0.label == l }! } }

    /// A key by name, as the bar shows it: `arrows`, `shortcuts` and the sticky ones (`ctrl`, `alt`, `pfx`) by theirs.
    private static func key(_ name: String) -> BarKey {
        common.first { ($0.sticky || $0.cluster || $0.shortcuts) && $0.label == name }
            ?? common.first { !$0.sticky && !$0.cluster && $0.keys == [name] } ?? BarKey(label: label(of: [name]), keys: [name])
    }

    public static let defaults: KeyBar = {
        var groups: [(defaults: KeyDefaults, ids: [String])] = []
        for (id, program) in Programs.builtin {
            guard let d = program.keyBar else { continue }
            if let i = groups.firstIndex(where: { $0.defaults == d }) { groups[i].ids.append(id) } else { groups.append((d, [id])) }
        }
        return KeyBar(
            profiles: groups.map { d, ids in KeyProfile(name: d.name, programs: ids, keys: d.keys.map(KeyBar.key), stop: d.stop) },
            default: KeyProfile(name: "default", keys: KeyBar.keys("esc", "tab", "⇧tab", "^c", "arrows"))
        )
    }()
}
