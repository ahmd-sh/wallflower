import Foundation
import Observation
import ScreenReading
import WallflowerDesign

/** What the user chose about how wallflower looks and behaves, kept on the phone. */
@Observable
public final class Preferences {
    @ObservationIgnored private let defaults: UserDefaults

    public var look: Look { didSet { defaults.set(look.rawValue, forKey: Key.look) } }

    public var scheme: Scheme { didSet { defaults.set(scheme.rawValue, forKey: Key.scheme) } }

    /** Whether touches answer with haptics. */
    public var haptics: Bool { didSet { defaults.set(haptics, forKey: Key.haptics) } }

    /** Whether closing a pane asks first; "don't ask again" turns it off, settings back on. */
    public var askClosingPane: Bool { didSet { defaults.set(askClosingPane, forKey: Key.askPane) } }

    /** Whether closing a window (or its last pane) asks first. */
    public var askClosingWindow: Bool { didSet { defaults.set(askClosingWindow, forKey: Key.askWindow) } }

    /** Panes whose program has a mobile view show it rather than the terminal: one switch, for every pane. */
    public var mobileView: Bool { didSet { defaults.set(mobileView, forKey: Key.mobileView) } }

    /** Whether a program working, or needing the user, shows in the Dynamic Island and on the Lock Screen (a Live Activity). */
    public var liveActivity: Bool { didSet { defaults.set(liveActivity, forKey: Key.liveActivity) } }

    /**
     * Whether the connection stays up while wallflower's in the background,
     * so the Live Activity and notifications keep up: a silent audio session,
     * which uses some battery.
     */
    public var keepConnected: Bool { didSet { defaults.set(keepConnected, forKey: Key.keepConnected) } }

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        look = defaults.string(forKey: Key.look).flatMap(Look.init(rawValue:)) ?? .grotesque
        scheme = defaults.string(forKey: Key.scheme).flatMap(Scheme.init(rawValue:)) ?? .everforestDarkHard
        haptics = defaults.object(forKey: Key.haptics) as? Bool ?? true
        askClosingPane = defaults.object(forKey: Key.askPane) as? Bool ?? true
        askClosingWindow = defaults.object(forKey: Key.askWindow) as? Bool ?? true
        mobileView = defaults.object(forKey: Key.mobileView) as? Bool ?? false
        liveActivity = defaults.object(forKey: Key.liveActivity) as? Bool ?? true
        keepConnected = defaults.object(forKey: Key.keepConnected) as? Bool ?? true
    }

    private enum Key {
        static let look = "look"
        static let scheme = "scheme"
        static let haptics = "haptics"
        static let askPane = "confirm_close_pane"
        static let askWindow = "confirm_close_window"
        static let mobileView = "mobile_view"
        static let liveActivity = "live_activity"
        static let keepConnected = "keep_connected"
    }
}

/** The key bar's profiles, kept on the phone as JSON. */
@Observable
public final class KeyBarStore {
    @ObservationIgnored private let defaults: UserDefaults
    public private(set) var bar: KeyBar

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        bar = defaults.data(forKey: Self.key).flatMap { try? JSONDecoder().decode(KeyBar.self, from: $0) } ?? KeyBar.defaults
    }

    public func update(_ change: (KeyBar) -> KeyBar) {
        let next = change(bar)
        bar = next
        if let data = try? JSONEncoder().encode(next) { defaults.set(data, forKey: Self.key) }
    }

    public func reset() {
        bar = KeyBar.defaults
        defaults.removeObject(forKey: Self.key)
    }

    private static let key = "keybar"
}
