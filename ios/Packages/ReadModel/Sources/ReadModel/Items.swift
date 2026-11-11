/// What a mobile view lists: something said (or shown, or noted), or the run of steps between.
public enum Item: Sendable, Equatable {
    /// `last`: the program's reply that ends its turn, which gets a copy button once the turn is done.
    case said(Entry, last: Bool = false)

    /// Steps and thoughts in a row, folded into one.
    case steps([Entry])

    public var key: String {
        switch self {
        case let .said(entry, _): entry.id
        case let .steps(entries): "steps " + entries[0].id
        }
    }
}

/// `entries` as a mobile view lists them: runs of steps folded, and each turn's last reply marked.
public func items(_ entries: [Entry]) -> [Item] {
    var out: [Item] = []
    var steps: [Entry] = []
    for e in entries {
        switch e {
        case .step, .thought:
            steps.append(e)
            continue
        default: break
        }
        if !steps.isEmpty { out.append(.steps(steps)) }
        steps = []
        out.append(.said(e))
    }
    if !steps.isEmpty { out.append(.steps(steps)) }
    // A turn ends where the user speaks next, or at the end.
    var ended = true
    for i in out.indices.reversed() {
        guard case let .said(.said(e), _) = out[i] else { continue }
        if e.by == .user {
            ended = true
        } else if ended {
            out[i] = .said(.said(e), last: true)
            ended = false
        }
    }
    return out
}

/// Steps as text, to copy: what each ran on, and what came of it.
public func trace(_ steps: [Entry]) -> String {
    steps.map { s in
        switch s {
        case let .step(s): [s.input.isEmpty ? s.name : "\(s.name): \(s.input)", s.diff.isEmpty ? s.output : s.diff].filter { !$0.isEmpty }.joined(separator: "\n")
        case let .thought(s): "thinking: \(s.text)"
        default: ""
        }
    }.joined(separator: "\n\n")
}
