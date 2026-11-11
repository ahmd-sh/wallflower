import Foundation

// The wire format is defined in docs/protocol.md. Field order matches the Go
// structs so both sides encode identical bytes (checked by the golden tests).

public let VERSION = 6
public let DEFAULT_HISTORY = 1000

public enum ClientMessage: Sendable, Equatable {
    case hello(ClientHello)

    case resize(cols: Int, rows: Int)

    case open(pane: String, history: Int? = nil)

    case close(pane: String)

    /// Keys by wallflower's names (Enter, Escape, Up, C-c, M-Enter, BTab, ...),
    /// anything else typed as it is; bytes go in data frames, composed text in
    /// `.submit`. With `bindings`, as if typed on a keyboard attached to the
    /// multiplexer, so its key bindings apply, if it can (`Mux.BINDINGS`);
    /// "prefix" stands for its prefix key.
    case keys(pane: String, keys: [String], bindings: Bool = false)

    /// Composed text in place of whatever the program's input line holds: `clear`
    /// keys empty the line first, then the gateway sends the text as the program
    /// takes it best, and Enter if `enter`.
    case submit(pane: String, text: String, enter: Bool = false, clear: [String] = [])

    case select(window: String? = nil, pane: String? = nil)

    /// Makes a pane its window's active pane, zoomed if the window has others; undone on disconnect.
    case focus(pane: String)

    /// Opens a pane beside this one, in its directory, and focuses it.
    case split(pane: String)

    /// Opens a window, current in the client's session only.
    case new

    case rename(window: String, name: String)

    /// Closes a window or a pane; closing a window's last pane closes it.
    case kill(window: String? = nil, pane: String? = nil)

    /// Starts or stops `pane`'s mobile view arriving as `Entries`, for a pane with one (`Pane.view`).
    case read(pane: String, on: Bool)

    /// Asks for a picture entry's bytes, which come in a picture frame.
    case picture(pane: String, id: String)

    /// Starts sending a file: `size` bytes follow in upload frames with `id`; `Uploaded` says where it was saved.
    case upload(id: String, name: String, size: Int64)

    /// Asks for a directory's entries, which come in a `Listing` with the same `id`; `path` is absolute, or starts with ~ for the home (empty: the home).
    case list(id: String, path: String)

    /// Asks for a file's bytes: a `FileReply` with the same `id` says how many, then they follow in file frames; with `limit`, no more than that many from the start.
    case fetch(id: String, path: String, limit: Int64? = nil)

    /// How many of a fetch's bytes have arrived, said as they do: the gateway sends only so far ahead of it.
    case got(id: String, size: Int64)

    /// Stops a fetch partway; frames already on their way still come.
    case cancel(id: String)

    case ping(id: Int)

    /// The user has left the app for now (`on`), or is back: while away, it doesn't size the session's windows.
    case away(on: Bool)

    /// Asks for the sessions in every multiplexer the gateway has, which come in a `SessionList`.
    case sessions

    /// Attaches to a session, in any multiplexer: `Attached` says which, and state follows; failing while attached to nothing, `Detached` says why.
    case switchTo(mux: String, session: String)

    /// Starts a session, named `name` or a name of the multiplexer's own (and the multiplexer, if it isn't running and `MuxSessions.CAN_NEW`), and switches to it.
    case newSession(mux: String, name: String? = nil)

    case renameSession(mux: String, session: String, name: String)

    /// Ends a session and the programs in it; not the one the app is in.
    case killSession(mux: String, session: String)

    /// Leaves the session for none, as the user chose: `Detached` follows.
    case detach
}

extension ClientMessage {
    var json: [UInt8] {
        switch self {
        case let .hello(m):
            var o = JSONObject("hello")
            o.field("v", m.v)
            o.field("cols", m.cols)
            o.field("rows", m.rows)
            o.field("mux", m.mux)
            o.field("session", m.session)
            if m.away { o.field("away", m.away) }
            return o.end()
        case let .resize(cols, rows):
            var o = JSONObject("resize")
            o.field("cols", cols)
            o.field("rows", rows)
            return o.end()
        case let .open(pane, history):
            var o = JSONObject("open")
            o.field("pane", pane)
            o.field("history", history)
            return o.end()
        case let .close(pane):
            var o = JSONObject("close")
            o.field("pane", pane)
            return o.end()
        case let .keys(pane, keys, bindings):
            var o = JSONObject("keys")
            o.field("pane", pane)
            o.field("keys", keys)
            if bindings { o.field("bindings", bindings) }
            return o.end()
        case let .submit(pane, text, enter, clear):
            var o = JSONObject("submit")
            o.field("pane", pane)
            o.field("text", text)
            if enter { o.field("enter", enter) }
            if !clear.isEmpty { o.field("clear", clear) }
            return o.end()
        case let .select(window, pane):
            var o = JSONObject("select")
            o.field("window", window)
            o.field("pane", pane)
            return o.end()
        case let .focus(pane):
            var o = JSONObject("focus")
            o.field("pane", pane)
            return o.end()
        case let .split(pane):
            var o = JSONObject("split")
            o.field("pane", pane)
            return o.end()
        case .new:
            var o = JSONObject("new")
            return o.end()
        case let .rename(window, name):
            var o = JSONObject("rename")
            o.field("window", window)
            o.field("name", name)
            return o.end()
        case let .kill(window, pane):
            var o = JSONObject("kill")
            o.field("window", window)
            o.field("pane", pane)
            return o.end()
        case let .read(pane, on):
            var o = JSONObject("read")
            o.field("pane", pane)
            o.field("on", on)
            return o.end()
        case let .picture(pane, id):
            var o = JSONObject("picture")
            o.field("pane", pane)
            o.field("id", id)
            return o.end()
        case let .upload(id, name, size):
            var o = JSONObject("upload")
            o.field("id", id)
            o.field("name", name)
            o.field("size", size)
            return o.end()
        case let .list(id, path):
            var o = JSONObject("list")
            o.field("id", id)
            o.field("path", path)
            return o.end()
        case let .fetch(id, path, limit):
            var o = JSONObject("fetch")
            o.field("id", id)
            o.field("path", path)
            if let limit, limit > 0 { o.field("limit", limit) }
            return o.end()
        case let .got(id, size):
            var o = JSONObject("got")
            o.field("id", id)
            o.field("size", size)
            return o.end()
        case let .cancel(id):
            var o = JSONObject("cancel")
            o.field("id", id)
            return o.end()
        case let .ping(id):
            var o = JSONObject("ping")
            o.field("id", id)
            return o.end()
        case let .away(on):
            var o = JSONObject("away")
            o.field("on", on)
            return o.end()
        case .sessions:
            var o = JSONObject("sessions")
            return o.end()
        case let .switchTo(mux, session):
            var o = JSONObject("switch")
            o.field("mux", mux)
            o.field("session", session)
            return o.end()
        case let .newSession(mux, name):
            var o = JSONObject("new-session")
            o.field("mux", mux)
            o.field("name", name)
            return o.end()
        case let .renameSession(mux, session, name):
            var o = JSONObject("rename-session")
            o.field("mux", mux)
            o.field("session", session)
            o.field("name", name)
            return o.end()
        case let .killSession(mux, session):
            var o = JSONObject("kill-session")
            o.field("mux", mux)
            o.field("session", session)
            return o.end()
        case .detach:
            var o = JSONObject("detach")
            return o.end()
        }
    }
}

/// The first message: where the app was (`session`, in the multiplexer `mux`
/// names, tmux or rex; nil: the gateway's default), to attach there again if
/// it's there; with no `session`, or one that's gone, it's attached to
/// nothing (`Detached`). `away` as in `.away`.
public struct ClientHello: Sendable, Equatable {
    public var v: Int
    public var cols: Int
    public var rows: Int
    public var mux: String?
    public var session: String?
    public var away: Bool

    public init(v: Int = VERSION, cols: Int, rows: Int, mux: String? = nil, session: String? = nil, away: Bool = false) {
        self.v = v
        self.cols = cols
        self.rows = rows
        self.mux = mux
        self.session = session
        self.away = away
    }
}

public enum ServerMessage: Sendable, Equatable {
    case hello(ServerHello)
    case attached(Attached)
    case detached(Detached)
    case state(SessionState)
    case reset(Reset)
    case entries(Entries)
    case error(ErrorMessage)
    case pong(Pong)
    case bye(Bye)
    case uploaded(Uploaded)
    case notification(ProgramNotification)
    case sessions(SessionList)
    case listing(Listing)
    case file(FileReply)
}

extension ServerMessage {
    /// The message of type `type` in `json`; nil for a type this client doesn't know.
    static func decode(_ type: String, from json: Data) throws -> ServerMessage? {
        let d = JSONDecoder()
        switch type {
        case "hello": return .hello(try d.decode(ServerHello.self, from: json))
        case "attached": return .attached(try d.decode(Attached.self, from: json))
        case "detached": return .detached(try d.decode(Detached.self, from: json))
        case "state": return .state(try d.decode(SessionState.self, from: json))
        case "reset": return .reset(try d.decode(Reset.self, from: json))
        case "entries": return .entries(try d.decode(Entries.self, from: json))
        case "error": return .error(try d.decode(ErrorMessage.self, from: json))
        case "pong": return .pong(try d.decode(Pong.self, from: json))
        case "bye": return .bye(try d.decode(Bye.self, from: json))
        case "uploaded": return .uploaded(try d.decode(Uploaded.self, from: json))
        case "notification": return .notification(try d.decode(ProgramNotification.self, from: json))
        case "sessions": return .sessions(try d.decode(SessionList.self, from: json))
        case "listing": return .listing(try d.decode(Listing.self, from: json))
        case "file": return .file(try d.decode(FileReply.self, from: json))
        default: return nil
        }
    }
}

/// Comes first, before the app is attached to anything.
public struct ServerHello: Sendable, Equatable, Decodable {
    public var v: Int
    /// Where uploads are saved: paths under it are uploads.
    public var uploads: String?
    /// The machine's name, as its owner calls it (a Mac's Computer Name).
    public var machine: String?
    /// The phone halves of plugins the user gave the gateway, as data.
    public var programs: [ProgramData]
    /// What this gateway does beyond what every one of its version does (`FILES`), so the app offers only that.
    public var can: [String]

    /// The gateway lists directories (`.list`) and sends files (`.fetch`).
    public static let FILES = "files"

    public init(v: Int, uploads: String? = nil, machine: String? = nil, programs: [ProgramData] = [], can: [String] = []) {
        self.v = v
        self.uploads = uploads
        self.machine = machine
        self.programs = programs
        self.can = can
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(
            v: try c.required("v"), uploads: try c.optional("uploads"), machine: try c.optional("machine"),
            programs: try c.value("programs", or: []), can: try c.value("can", or: [])
        )
    }
}

/// A plugin's phone half as data: its key bar, the commands the composer offers, what starts a new line and empties one.
public struct ProgramData: Sendable, Equatable, Decodable {
    public var id: String
    public var keys: KeyBarData?
    public var commands: [CommandData]
    public var newline: [String]?
    public var clear: [String]?

    public init(id: String, keys: KeyBarData? = nil, commands: [CommandData] = [], newline: [String]? = nil, clear: [String]? = nil) {
        self.id = id
        self.keys = keys
        self.commands = commands
        self.newline = newline
        self.clear = clear
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(
            id: try c.required("id"), keys: try c.optional("keys"), commands: try c.value("commands", or: []),
            newline: try c.optional("newline"), clear: try c.optional("clear")
        )
    }
}

/// A key bar by key names, and what the send button presses to `stop` the program.
public struct KeyBarData: Sendable, Equatable, Decodable {
    public var name: String
    public var keys: [String]
    public var stop: [String]?

    public init(name: String, keys: [String], stop: [String]? = nil) {
        self.name = name
        self.keys = keys
        self.stop = stop
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(name: try c.required("name"), keys: try c.required("keys"), stop: try c.optional("stop"))
    }
}

/// A command a program takes, as the composer offers it.
public struct CommandData: Sendable, Equatable, Decodable {
    public var text: String
    public var about: String

    public init(text: String, about: String = "") {
        self.text = text
        self.about = about
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(text: try c.required("text"), about: try c.value("about", or: ""))
    }
}

/// The app is attached to a session in `mux`; its state follows.
public struct Attached: Sendable, Equatable, Decodable {
    public var mux: Mux

    public init(mux: Mux) {
        self.mux = mux
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(mux: try c.required("mux"))
    }
}

/// The app is attached to nothing: it never was this connection, it asked
/// (`.detach`), or the session or its multiplexer went, or one it asked for
/// couldn't be had (`reason` says). The sessions follow, and come again
/// whenever they change, until it's attached.
public struct Detached: Sendable, Equatable, Decodable {
    public var reason: String?

    public init(reason: String? = nil) {
        self.reason = reason
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(reason: try c.optional("reason"))
    }
}

/// The multiplexer the session is in (tmux), its version, and what it `can` do beyond what every one does.
public struct Mux: Sendable, Equatable, Decodable {
    public var name: String
    public var version: String
    public var can: [String]

    /// `.keys` can go through the multiplexer's own key bindings.
    public static let BINDINGS = "bindings"

    public init(name: String, version: String, can: [String] = []) {
        self.name = name
        self.version = version
        self.can = can
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(name: try c.required("name"), version: try c.required("version"), can: try c.value("can", or: []))
    }
}

public struct SessionState: Sendable, Equatable, Decodable {
    public var session: String
    public var name: String
    public var windows: [MuxWindow]

    public init(session: String, name: String, windows: [MuxWindow]) {
        self.session = session
        self.name = name
        self.windows = windows
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(session: try c.required("session"), name: try c.required("name"), windows: try c.required("windows"))
    }
}

public struct MuxWindow: Sendable, Equatable, Decodable {
    public var id: String
    public var index: Int
    public var name: String
    public var active: Bool
    public var panes: [Pane]

    public init(id: String, index: Int, name: String, active: Bool, panes: [Pane]) {
        self.id = id
        self.index = index
        self.name = name
        self.active = active
        self.panes = panes
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(
            id: try c.required("id"), index: try c.required("index"), name: try c.required("name"),
            active: try c.required("active"), panes: try c.required("panes")
        )
    }
}

public struct Pane: Sendable, Equatable, Decodable {
    public var id: String
    public var index: Int
    public var active: Bool
    public var cols: Int
    public var rows: Int
    /// A full-screen app owns the pane; no scrollback is kept for it.
    public var alt: Bool
    public var title: String
    public var path: String
    public var command: String
    /// The program the pane runs (claude, zsh, shell), if the gateway knows it: the app's half of it goes by the same name (Programs).
    public var program: String?
    /// The program has a mobile view of the pane; see `.read`.
    public var view: Bool
    /// What a program with a mobile view is doing, if it says: `WORKING`, `WAITING` or `IDLE`; `since` when, in Unix milliseconds.
    public var activity: String
    public var since: Int

    public static let WORKING = "working"
    public static let WAITING = "waiting"
    public static let IDLE = "idle"

    public init(
        id: String, index: Int, active: Bool, cols: Int, rows: Int, alt: Bool = false, title: String = "", path: String = "",
        command: String, program: String? = nil, view: Bool = false, activity: String = "", since: Int = 0
    ) {
        self.id = id
        self.index = index
        self.active = active
        self.cols = cols
        self.rows = rows
        self.alt = alt
        self.title = title
        self.path = path
        self.command = command
        self.program = program
        self.view = view
        self.activity = activity
        self.since = since
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(
            id: try c.required("id"), index: try c.required("index"), active: try c.required("active"),
            cols: try c.required("cols"), rows: try c.required("rows"), alt: try c.value("alt", or: false),
            title: try c.value("title", or: ""), path: try c.value("path", or: ""), command: try c.required("command"),
            program: try c.optional("program"), view: try c.value("view", or: false),
            activity: try c.value("activity", or: ""), since: try c.value("since", or: 0)
        )
    }
}

/// Recreate the pane's terminal at this size; the next data frame repaints it.
public struct Reset: Sendable, Equatable, Decodable {
    public var pane: String
    public var cols: Int
    public var rows: Int

    public init(pane: String, cols: Int, rows: Int) {
        self.pane = pane
        self.cols = cols
        self.rows = rows
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(pane: try c.required("pane"), cols: try c.required("cols"), rows: try c.required("rows"))
    }
}

/// A pane's mobile view. With `reset`, its latest entries replace any before
/// (`more`: earlier ones weren't sent); otherwise these entries are new, or
/// replace the ones with their ids. `error` says why there's nothing to read.
public struct Entries: Sendable, Equatable, Decodable {
    public var pane: String
    public var reset: Bool
    public var more: Bool
    public var entries: [WireEntry]
    public var error: String?
    /// Tokens the conversation takes of the program's context, when it says; 0 once it no longer knows (just compacted).
    public var context: Int?
    /// What the conversation is called, with a reset and whenever it changes.
    public var title: String?
    /// What the user sent that waits for the program to take it, oldest first, when that changes; empty once nothing waits.
    public var queued: [String]?

    public init(
        pane: String, reset: Bool = false, more: Bool = false, entries: [WireEntry] = [], error: String? = nil,
        context: Int? = nil, title: String? = nil, queued: [String]? = nil
    ) {
        self.pane = pane
        self.reset = reset
        self.more = more
        self.entries = entries
        self.error = error
        self.context = context
        self.title = title
        self.queued = queued
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(
            pane: try c.required("pane"), reset: try c.value("reset", or: false), more: try c.value("more", or: false),
            entries: try c.value("entries", or: []), error: try c.optional("error"), context: try c.optional("context"),
            title: try c.optional("title"), queued: try c.optional("queued")
        )
    }
}

/// One part of a mobile view: something said, or a step taken.
public struct WireEntry: Sendable, Equatable, Decodable {
    public var id: String
    /// user or program
    public var from: String
    /// said (markdown), step, thought, picture or note
    public var kind: String
    /// What was said; for a step, what it was asked.
    public var text: String
    public var name: String
    /// A step's: running, done or failed.
    public var status: String
    public var output: String
    public var outputCut: Int
    /// A step's change to a file, as unified diff hunks.
    public var diff: String
    public var diffCut: Int
    public var mime: String
    /// A picture's bytes, which `.picture` asks for.
    public var size: Int

    public init(
        id: String, from: String, kind: String, text: String = "", name: String = "", status: String = "", output: String = "",
        outputCut: Int = 0, diff: String = "", diffCut: Int = 0, mime: String = "", size: Int = 0
    ) {
        self.id = id
        self.from = from
        self.kind = kind
        self.text = text
        self.name = name
        self.status = status
        self.output = output
        self.outputCut = outputCut
        self.diff = diff
        self.diffCut = diffCut
        self.mime = mime
        self.size = size
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(
            id: try c.required("id"), from: try c.required("from"), kind: try c.required("kind"),
            text: try c.value("text", or: ""), name: try c.value("name", or: ""), status: try c.value("status", or: ""),
            output: try c.value("output", or: ""), outputCut: try c.value("output_cut", or: 0),
            diff: try c.value("diff", or: ""), diffCut: try c.value("diff_cut", or: 0),
            mime: try c.value("mime", or: ""), size: try c.value("size", or: 0)
        )
    }
}

public struct ErrorMessage: Sendable, Equatable, Decodable {
    public var message: String

    public init(message: String) {
        self.message = message
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(message: try c.required("message"))
    }
}

public struct Pong: Sendable, Equatable, Decodable {
    public var id: Int

    public init(id: Int) {
        self.id = id
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(id: try c.required("id"))
    }
}

public struct Bye: Sendable, Equatable, Decodable {
    public var reason: String

    public init(reason: String = "") {
        self.reason = reason
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(reason: try c.value("reason", or: ""))
    }
}

/// Where an upload was saved, once all of it arrived, or why it wasn't.
public struct Uploaded: Sendable, Equatable, Decodable {
    public var id: String
    public var path: String?
    public var error: String?

    public init(id: String, path: String? = nil, error: String? = nil) {
        self.id = id
        self.path = path
        self.error = error
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(id: try c.required("id"), path: try c.optional("path"), error: try c.optional("error"))
    }
}

/// A desktop notification `pane`'s program sent through its terminal (OSC 9, 777 or 99), or its `bell`, with no body.
public struct ProgramNotification: Sendable, Equatable, Decodable {
    public var pane: String
    public var title: String?
    public var body: String
    public var bell: Bool

    public init(pane: String, title: String? = nil, body: String = "", bell: Bool = false) {
        self.pane = pane
        self.title = title
        self.body = body
        self.bell = bell
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(pane: try c.required("pane"), title: try c.optional("title"), body: try c.value("body", or: ""), bell: try c.value("bell", or: false))
    }
}

/// The sessions in each multiplexer the gateway has; sent when asked, after one is started, renamed or ended, and whenever they change while the app is attached to nothing.
public struct SessionList: Sendable, Equatable, Decodable {
    public var muxes: [MuxSessions]

    public init(muxes: [MuxSessions]) {
        self.muxes = muxes
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(muxes: try c.required("muxes"))
    }
}

/// One multiplexer's sessions, in its own order, or why they can't be listed; whether it's `running`, and what `can` be done with its sessions.
public struct MuxSessions: Sendable, Equatable, Decodable {
    public var name: String
    public var running: Bool
    public var sessions: [SessionInfo]
    public var can: [String]
    public var error: String?

    /// A session can be started, and the multiplexer with it if it isn't running.
    public static let CAN_NEW = "new"
    public static let CAN_RENAME = "rename"
    public static let CAN_KILL = "kill"

    public init(name: String, running: Bool = false, sessions: [SessionInfo] = [], can: [String] = [], error: String? = nil) {
        self.name = name
        self.running = running
        self.sessions = sessions
        self.can = can
        self.error = error
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(
            name: try c.required("name"), running: try c.value("running", or: false), sessions: try c.value("sessions", or: []),
            can: try c.value("can", or: []), error: try c.optional("error")
        )
    }
}

public struct SessionInfo: Sendable, Equatable, Decodable {
    public var id: String
    public var name: String
    public var windows: Int

    public init(id: String, name: String, windows: Int = 0) {
        self.id = id
        self.name = name
        self.windows = windows
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(id: try c.required("id"), name: try c.required("name"), windows: try c.value("windows", or: 0))
    }
}

/// A directory's entries, as `.list` asked: `path` is where it is, absolute and clean, and `more` says it has more entries than were sent. `error` says why it couldn't be read.
public struct Listing: Sendable, Equatable, Decodable {
    public var id: String
    public var path: String
    public var entries: [FileEntry]
    public var more: Bool
    public var error: String?

    public init(id: String, path: String, entries: [FileEntry] = [], more: Bool = false, error: String? = nil) {
        self.id = id
        self.path = path
        self.entries = entries
        self.more = more
        self.error = error
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(
            id: try c.required("id"), path: try c.required("path"), entries: try c.value("entries", or: []),
            more: try c.value("more", or: false), error: try c.optional("error")
        )
    }
}

/// One of a directory's entries: its `kind` is what it is, or, for a symbolic link (`link`), what it leads to. `size` is a file's, in bytes; `mtime` when it last changed, in Unix milliseconds. `hidden` is a name that starts with a dot, or one the Mac hides (~/Library).
public struct FileEntry: Sendable, Equatable, Hashable, Decodable {
    public var name: String
    public var kind: String
    public var link: Bool
    public var size: Int64
    public var mtime: Int64
    public var hidden: Bool

    public static let DIR = "dir"
    public static let FILE = "file"
    public static let OTHER = "other"

    public init(name: String, kind: String, link: Bool = false, size: Int64 = 0, mtime: Int64 = 0, hidden: Bool = false) {
        self.name = name
        self.kind = kind
        self.link = link
        self.size = size
        self.mtime = mtime
        self.hidden = hidden
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(
            name: try c.required("name"), kind: try c.required("kind"), link: try c.value("link", or: false),
            size: try c.value("size", or: 0), mtime: try c.value("mtime", or: 0), hidden: try c.value("hidden", or: false)
        )
    }

    public var isDir: Bool { kind == Self.DIR }
}

/// Answers a `.fetch`: `size` bytes of the file at `path` follow in file frames with its `id`, or `error` says why none do (or, partway, why the rest won't).
public struct FileReply: Sendable, Equatable, Decodable {
    public var id: String
    public var path: String
    public var size: Int64
    public var error: String?

    public init(id: String, path: String, size: Int64 = 0, error: String? = nil) {
        self.id = id
        self.path = path
        self.size = size
        self.error = error
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        self.init(id: try c.required("id"), path: try c.required("path"), size: try c.value("size", or: 0), error: try c.optional("error"))
    }
}
