import Foundation

/**
 * Where the gateway runs, the name it last gave its [machine], and the
 * multiplexer ([mux], tmux or rex) and [session] (its id; nil: the one the
 * gateway picks) the phone was last in. [dev] uses `wallflower listen` over
 * plain TCP instead of ssh.
 */
public struct Host: Codable, Equatable, Sendable {
    public var address: String = ""
    public var port: Int = 22
    public var user: String = ""
    public var command: String = "~/go/bin/wallflower serve"
    public var mux: String = "tmux"
    public var session: String?
    public var dev: Bool = false
    public var machine: String?

    public init(address: String = "", port: Int = 22, user: String = "", command: String = "~/go/bin/wallflower serve", mux: String = "tmux", session: String? = nil, dev: Bool = false, machine: String? = nil) {
        self.address = address
        self.port = port
        self.user = user
        self.command = command
        self.mux = mux
        self.session = session
        self.dev = dev
        self.machine = machine
    }

    public func save(_ defaults: UserDefaults = .standard) {
        if let data = try? JSONEncoder().encode(self) { defaults.set(data, forKey: "host") }
    }

    public static func load(_ defaults: UserDefaults = .standard) -> Host? {
        guard let data = defaults.data(forKey: "host") else { return nil }
        return try? JSONDecoder().decode(Host.self, from: data)
    }

    /** What to call the Mac: its name, once its gateway has said it. */
    public var called: String { machine ?? "your computer" }
}

public extension Optional where Wrapped == Host {
    var called: String { self?.called ?? "your computer" }
}
