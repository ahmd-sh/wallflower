/// What a program is doing, when it says.
public enum Activity: Sendable, Equatable {
    /// On a turn, its steps included.
    case working

    /// For the user to answer something mid-turn.
    case waiting

    /// Done until the next message.
    case idle
}

/// Who said something: the user, or the program in the pane.
public enum Party: Sendable, Equatable {
    case user, program
}

/// One part of a pane's mobile view: something said, or a step taken.
public enum Entry: Sendable, Equatable, Identifiable {
    case said(Said)
    case step(Step)
    case thought(Thought)
    case picture(Picture)
    case command(Command)
    case note(Note)
    case agent(Agent)

    public var id: String {
        switch self {
        case let .said(e): e.id
        case let .step(e): e.id
        case let .thought(e): e.id
        case let .picture(e): e.id
        case let .command(e): e.id
        case let .note(e): e.id
        case let .agent(e): e.id
        }
    }

    /// What either side said, in markdown.
    public struct Said: Sendable, Equatable {
        public var id: String
        public var by: Party
        public var text: String

        public init(id: String, by: Party, text: String) {
            self.id = id
            self.by = by
            self.text = text
        }
    }

    /// A step the program took: a tool it ran, what it was asked, what came of
    /// it, and any change it made to a file, each trimmed (`outputCut`
    /// characters, `diffCut` lines short).
    public struct Step: Sendable, Equatable {
        public var id: String
        public var name: String
        public var input: String
        public var output: String
        /// Unified diff hunks.
        public var diff: String
        public var status: Status
        public var outputCut: Int
        public var diffCut: Int

        public enum Status: Sendable, Equatable {
            case running, done, failed
        }

        public init(
            id: String, name: String, input: String, output: String = "", diff: String = "", status: Status = .running,
            outputCut: Int = 0, diffCut: Int = 0
        ) {
            self.id = id
            self.name = name
            self.input = input
            self.output = output
            self.diff = diff
            self.status = status
            self.outputCut = outputCut
            self.diffCut = diffCut
        }
    }

    /// The program thinking it through.
    public struct Thought: Sendable, Equatable {
        public var id: String
        public var text: String

        public init(id: String, text: String) {
            self.id = id
            self.text = text
        }
    }

    /// A picture either side shared, `size` bytes, which are asked for when it shows.
    public struct Picture: Sendable, Equatable {
        public var id: String
        public var by: Party
        public var mime: String
        public var size: Int

        public init(id: String, by: Party, mime: String, size: Int) {
            self.id = id
            self.by = by
            self.mime = mime
            self.size = size
        }
    }

    /// A command the user ran (`name`: /compact, or ! for a shell's) with its `args`, and what it said back.
    public struct Command: Sendable, Equatable {
        public var id: String
        public var name: String
        public var args: String
        public var output: String
        public var outputCut: Int

        public init(id: String, name: String, args: String, output: String = "", outputCut: Int = 0) {
            self.id = id
            self.name = name
            self.args = args
            self.output = output
            self.outputCut = outputCut
        }
    }

    /// Something that happened rather than was said: an interruption, a compaction, with more about it (`detail`) to open.
    public struct Note: Sendable, Equatable {
        public var id: String
        public var text: String
        public var detail: String

        public init(id: String, text: String, detail: String = "") {
            self.id = id
            self.text = text
            self.detail = detail
        }
    }

    /// A message another agent sent the program, `name`d: a subagent's report, a teammate's; what it's `about`, and the `message`, `cut` characters short.
    public struct Agent: Sendable, Equatable {
        public var id: String
        public var name: String
        public var about: String
        public var message: String
        public var cut: Int

        public init(id: String, name: String, about: String, message: String = "", cut: Int = 0) {
            self.id = id
            self.name = name
            self.about = about
            self.message = message
            self.cut = cut
        }
    }
}

extension Entry {
    var userSaid: Said? {
        if case let .said(s) = self, s.by == .user { s } else { nil }
    }
}
