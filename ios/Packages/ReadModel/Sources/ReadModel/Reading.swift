/// A pane's mobile view, as its program has it. `loaded` once the gateway has
/// sent it; `more` if there were earlier entries than these; `problem` when
/// there's nothing to read. `context` is how many tokens it takes of the
/// program's context, 0 while that's unknown. `rewound`: the user's message
/// a rewind went back to before, which the program doesn't record until the
/// next message; it and what followed aren't `shown` till then. What the
/// program is doing is its pane's (`activityOf`). `title`: what the
/// conversation is called, if it's named. `stopped`: when the user last
/// stopped the program, by the session's clock (0: not lately). `queued`:
/// what the user sent that waits for the program to take it.
public struct Reading: Sendable, Equatable {
    public var entries: [Entry]
    public var more: Bool
    public var problem: String?
    public var loaded: Bool
    public var context: Int
    public var rewound: String?
    public var title: String?
    public var stopped: Int
    public var queued: [String]

    public static let MAX_ENTRIES = 1000

    /// How long after a stop the program can take the user's message back.
    private static let TAKES_BACK_MS = 5_000

    public init(
        entries: [Entry] = [], more: Bool = false, problem: String? = nil, loaded: Bool = false, context: Int = 0,
        rewound: String? = nil, title: String? = nil, stopped: Int = 0, queued: [String] = []
    ) {
        self.entries = entries
        self.more = more
        self.problem = problem
        self.loaded = loaded
        self.context = context
        self.rewound = rewound
        self.title = title
        self.stopped = stopped
        self.queued = queued
    }

    public var shown: [Entry] {
        guard let r = rewound else { return entries }
        return Array(entries.prefix { $0.id != r })
    }

    /// Rewound to before the user's message that `said` matches: the one `held` to edit, if it's one; else, of
    /// messages it could be, the latest: what was taken back may partly show till the next message, but nothing still said is hidden.
    public func rewoundTo(_ said: (String) -> Bool, held: String? = nil) -> Reading {
        let matches = entries.filter { $0.userSaid.map { said($0.text) } ?? false }
        guard let message = matches.first(where: { $0.id == held }) ?? matches.last else { return self }
        var next = self
        next.rewound = message.id
        return next
    }

    /// Less the user's latest message, if the program has put it back in its
    /// input line (`line`) unsent just after they stopped it (as of `now`), as
    /// Claude Code does with one stopped before its reply: taken back, though
    /// it's recorded till the next. Only then: the same text there otherwise is
    /// history brought back (↑), not taken back.
    public func takenBack(_ line: String?, now: Int) -> Reading {
        guard let line, !bare(line).isEmpty, rewound == nil, stopped != 0, now - stopped <= Self.TAKES_BACK_MS else { return self }
        guard let last = entries.last(where: { $0.userSaid != nil })?.userSaid else { return self }
        guard bare(last.text) == bare(line) else { return self }
        var next = self
        next.rewound = last.id
        next.stopped = 0
        return next
    }

    private func bare(_ text: String) -> String {
        String(String.UnicodeScalarView(text.unicodeScalars.filter { !isWhitespace($0) }))
    }

    /// Whether the entry `id` could still be taken back: it's here, and nothing the user said comes after it, which the program records only past a rewind.
    public func takesBack(_ id: String) -> Bool {
        guard let at = entries.firstIndex(where: { $0.id == id }) else { return false }
        return !entries[(at + 1)...].contains { $0.userSaid != nil }
    }

    /// With `new` entries: each replaces the one with its id, or comes last; the oldest go past `MAX_ENTRIES`.
    /// A message of the user's new among them ends what was `rewound`: the program has recorded more.
    public func with(_ new: [Entry]) -> Reading {
        if new.isEmpty { return self }
        var at = [String: Int](minimumCapacity: entries.count)
        for (i, e) in entries.enumerated() { at[e.id] = i }
        let spoke = new.contains { $0.userSaid != nil && at[$0.id] == nil }
        var entries = self.entries
        for e in new {
            if let i = at[e.id] {
                entries[i] = e
            } else {
                at[e.id] = entries.count
                entries.append(e)
            }
        }
        let dropped = max(entries.count - Self.MAX_ENTRIES, 0)
        var next = self
        next.entries = dropped > 0 ? Array(entries.dropFirst(dropped)) : entries
        next.more = more || dropped > 0
        next.rewound = spoke ? nil : rewound
        return next
    }
}

private func isWhitespace(_ c: Unicode.Scalar) -> Bool {
    switch c.properties.generalCategory {
    case .spaceSeparator, .lineSeparator, .paragraphSeparator: true
    default: (0x09...0x0d).contains(c.value) || (0x1c...0x1f).contains(c.value)
    }
}
