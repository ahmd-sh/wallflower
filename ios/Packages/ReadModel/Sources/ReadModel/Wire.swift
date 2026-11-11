import Wire

// The mobile view as the wire has it, into the read model.

extension Reading {
    /// With `m` applied: a reset replaces everything; otherwise its entries come in, and the context and title it says replace what was.
    /// What was `rewound` stays so through a reset that still has its message and nothing the user said after: the program records a rewind with the next one.
    public func apply(_ m: Entries) -> Reading {
        if m.reset {
            let entries = m.entries.map(entryOf)
            var next = Reading(
                entries: Array(entries.suffix(Self.MAX_ENTRIES)), more: m.more || entries.count > Self.MAX_ENTRIES, problem: m.error,
                loaded: true, context: m.context ?? 0, title: m.title, queued: m.queued ?? []
            )
            next.rewound = rewound.flatMap { next.takesBack($0) ? $0 : nil }
            return next
        }
        var next = with(m.entries.map(entryOf))
        next.problem = m.entries.isEmpty ? m.error ?? problem : m.error
        next.loaded = true
        next.context = m.context ?? context
        next.title = m.title ?? title
        next.queued = m.queued ?? queued
        return next
    }
}

/// What a pane's program is doing, if it says.
public func activityOf(_ pane: Pane?) -> Activity? {
    switch pane?.activity {
    case Pane.WORKING?: .working
    case Pane.WAITING?: .waiting
    case Pane.IDLE?: .idle
    default: nil
    }
}

private func partyOf(_ from: String) -> Party {
    from == "user" ? .user : .program
}

/// An entry of a kind this client doesn't know shows as a note of its text.
func entryOf(_ e: WireEntry) -> Entry {
    switch e.kind {
    case "said":
        return .said(Entry.Said(id: e.id, by: partyOf(e.from), text: e.text))
    case "step":
        if e.from == "user" { return .command(Entry.Command(id: e.id, name: e.name, args: e.text, output: e.output, outputCut: e.outputCut)) }
        let status: Entry.Step.Status = switch e.status {
        case "failed": .failed
        case "done": .done
        default: .running
        }
        return .step(Entry.Step(id: e.id, name: e.name, input: e.text, output: e.output, diff: e.diff, status: status, outputCut: e.outputCut, diffCut: e.diffCut))
    case "thought":
        return .thought(Entry.Thought(id: e.id, text: e.text))
    case "picture":
        return .picture(Entry.Picture(id: e.id, by: partyOf(e.from), mime: e.mime, size: e.size))
    case "note":
        if !e.name.isEmpty { return .agent(Entry.Agent(id: e.id, name: e.name, about: e.text, message: e.output, cut: e.outputCut)) }
        return .note(Entry.Note(id: e.id, text: e.text, detail: e.output))
    default:
        return .note(Entry.Note(id: e.id, text: e.text, detail: e.output))
    }
}
