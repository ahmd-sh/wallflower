import Observation
import ScreenReading

/**
 * The key bar's sticky keys (pfx, ctrl): one pressed is [armed], waiting
 * for the next key, from the bar or the keyboard, to go with it.
 */
@MainActor
@Observable
final class StickyKeys {
    private(set) var armed: BarKey?
    @ObservationIgnored var onKey: (BarKey) -> Void = { _ in }

    func press(_ key: BarKey) {
        let sticky = armed
        if key.sticky {
            armed = sticky == key ? nil : key
        } else if let sticky {
            armed = nil
            onKey(sticky.then(key))
        } else {
            onKey(key)
        }
    }

    /** A character typed into the box while one's armed: it goes with it instead. */
    func intercept(_ c: Character) { press(.typed(c)) }

    /**
     * What's typed straight into the pane, the keyboard's return key being
     * [returnKeys]: with one armed, its first key goes with it; text
     * otherwise goes to [onText].
     */
    func type(_ typed: Typed, returnKeys: [String], onText: (String) -> Void) {
        switch typed {
        case .key(let name):
            press(BarKey(label: name, keys: [name]))
        case .return:
            press(BarKey(label: "⏎", keys: returnKeys))
        case .text(let text):
            guard armed != nil, let first = text.first else { return onText(text) }
            press(.typed(first))
            if text.count > 1 { onText(String(text.dropFirst())) }
        }
    }
}
