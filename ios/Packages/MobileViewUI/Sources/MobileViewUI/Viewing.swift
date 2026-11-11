import SwiftUI

/** What a picture, or a file a program mentions, opens in: the app's viewer. Unset, they're only shown. */
public struct Viewing {
    /** A picture entry, by its id and its type. */
    public var picture: ((_ id: String, _ mime: String) -> Void)?
    /** A file, by its path as a program wrote it; nil while the Mac's files can't be had. */
    public var path: ((String) -> Void)?

    public init(picture: ((String, String) -> Void)? = nil, path: ((String) -> Void)? = nil) {
        self.picture = picture
        self.path = path
    }

    /** The scheme of the links a path in what's said becomes. */
    static let scheme = "wallflower-file"

    /** A link to [path], to open here. */
    static func link(_ path: String) -> URL? {
        var c = URLComponents()
        c.scheme = scheme
        c.path = path.hasPrefix("/") ? path : "/" + path
        c.fragment = path.hasPrefix("/") ? nil : "relative"
        return c.url
    }

    /** The path a link from [link] stands for. */
    static func path(_ url: URL) -> String? {
        guard url.scheme == scheme, let c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        return c.fragment == "relative" ? String(c.path.dropFirst()) : c.path
    }

    /** Opens what links stand for here, and anything else as usual. */
    var opener: OpenURLAction {
        OpenURLAction { url in
            guard let p = Self.path(url) else { return .systemAction }
            path?(p)
            return .handled
        }
    }
}

private struct ViewingKey: EnvironmentKey {
    static let defaultValue = Viewing()
}

public extension EnvironmentValues {
    var viewing: Viewing {
        get { self[ViewingKey.self] }
        set { self[ViewingKey.self] = newValue }
    }
}
