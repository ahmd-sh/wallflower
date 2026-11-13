import MobileViewUI
import ReadModel
import ScreenReading
import SwiftUI
import TerminalCore
import WallflowerDesign
import Wire

/** The shown pane's terminal view, for what the composer does to it: predict, scroll down, take the keyboard. */
@MainActor
final class TerminalHandle {
    weak var view: TerminalView?
    /** The keyboard was up on a pane that's no longer the one shown: the next one takes it. */
    var handoff = false

    func predict(_ text: String) { view?.predict(text) }
    func scrollToBottom() { view?.scrollToBottom() }
    func type() { _ = view?.becomeFirstResponder() }
    func hideKeyboard() { _ = view?.resignFirstResponder() }
    /** A key went that the keyboard didn't type: what it typed isn't its to change any more. */
    func forgetTyped() { view?.forgetTyped() }
}

/** A terminal view in SwiftUI. */
struct TerminalHost: UIViewRepresentable {
    @Environment(\.haptics) private var haptics
    @Environment(\.palette) private var palette
    let terminal: Terminal?
    let theme: Theme
    let paused: Bool
    let current: Bool
    let typing: Bool
    let handle: TerminalHandle
    let swipe: Swipe?
    let onGridSize: (Int, Int) -> Void
    let onAppScroll: (Bool, Data?) -> Void
    let onTyped: (Typed) -> Void
    let onTypingChanged: (Bool) -> Void
    /** Whether it takes touches; a picture of a pane (a card) leaves them to what holds it. */
    var touchable = true

    func makeUIView(context: Context) -> TerminalView {
        let v = TerminalView(frame: .zero)
        v.theme = theme
        return v
    }

    func updateUIView(_ v: TerminalView, context: Context) {
        v.terminal = terminal
        v.theme = theme
        v.paused = paused
        v.onGridSize = current ? onGridSize : nil
        v.onAppScroll = onAppScroll
        v.isUserInteractionEnabled = touchable
        // What's typed goes where the selection was: it's let go.
        v.onTyped = { [weak v] t in
            v?.clearSelection()
            onTyped(t)
        }
        v.onTypingChanged = onTypingChanged
        v.onHeld = { [haptics] in haptics.hold() }
        v.onSelectionMoved = { [haptics] in haptics.tick() }
        v.selectionColor = UIColor(palette.orange)
        if v.isFirstResponder && !(typing && current) { handle.handoff = true }
        v.typingEnabled = typing && current
        if let swipe {
            v.onSwipe = { [weak swipe] dx in swipe?.drag(dx) }
            v.onSwipeEnd = { [weak swipe] dx, vel in swipe?.end(dx, vel) }
        } else {
            v.onSwipe = nil
            v.onSwipeEnd = nil
        }
        if current {
            handle.view = v
            if handle.handoff && typing {
                handle.handoff = false
                DispatchQueue.main.async { _ = v.becomeFirstResponder() }
            }
        }
    }
}

/**
 * A pane's page: its terminal, and its mobile view over it while that's on
 * ([reading]), which pauses the terminal underneath. With no pane
 * ([actions] nil), an empty terminal that measures the grid until there
 * are panes. [current]: the page on screen, which gets the [prompt] the
 * pane's screen offers, what its program says it's doing ([status]), what
 * it shows [aside] of its line (with what's been read of an [answer]), and
 * its [mode] (with those it goes round, [modes]), which picked goes to [onMode].
 */
struct PanePage: View {
    @Environment(\.palette) private var palette
    let actions: PaneActions?
    let terminal: Terminal?
    let reading: Reading?
    let current: Bool
    let swipe: Swipe?
    let covered: Bool
    let prompt: Prompt?
    let line: () -> InputLine?
    let status: String?
    /** Questions the program asked, waiting to be brought up. */
    var asked: Asked? = nil
    let aside: Aside?
    let answer: [String]
    let mode: Mode?
    let modes: [Mode]
    let onMode: (String) -> Void
    let nudged: Int
    /** The message held to edit, if picking is for it. */
    let held: String?
    /** The app is walking the program's list to a message itself: the list isn't the user's to pick from meanwhile. */
    var seeking = false
    let handle: TerminalHandle
    let typing: Bool
    let onGridSize: (Int, Int) -> Void
    let onTyped: (Typed) -> Void
    let onTypingChanged: (Bool) -> Void
    let onEdit: (String, String) -> Void
    /** Keys from the prompt's card: the user's own hand on it. */
    let onPrompt: () -> Void
    let tab: String?

    var body: some View {
        ZStack {
            TerminalHost(
                terminal: terminal, theme: palette.terminal, paused: covered || reading != nil, current: current, typing: typing,
                handle: handle, swipe: swipe, onGridSize: onGridSize,
                onAppScroll: { up, wheel in actions?.wheel(up, wheel) },
                onTyped: onTyped, onTypingChanged: onTypingChanged
            )
            .padding(.horizontal, 4)
            if let actions, let reading {
                MobileView(
                    reading: reading,
                    activity: activityOf(actions.pane),
                    since: Int64(actions.pane.since),
                    picture: { id in await actions.picture(id) },
                    prompt: current ? prompt : nil,
                    doing: current ? status : nil,
                    asked: current ? asked : nil,
                    nudged: current ? nudged : 0,
                    seeking: current && seeking,
                    aside: current ? aside : nil,
                    answer: answer,
                    mode: current ? mode : nil,
                    modes: modes,
                    onMode: onMode,
                    onKeys: { keys in
                        onPrompt()
                        actions.keys(keys)
                    },
                    onPicked: { p, o in
                        onPrompt()
                        actions.picked(p, o, held: held)
                    },
                    onCompact: current ? actions.compact.map { c in { c(line()) } } : nil,
                    onEdit: current && prompt == nil ? onEdit : nil,
                    title: reading.title.flatMap { $0 == tab ? nil : $0 },
                    live: current,
                    onSwipe: swipe.map { s in { [weak s] dx in s?.drag(dx) } },
                    onSwipeEnd: swipe.map { s in { [weak s] dx, v in s?.end(dx, v) } }
                )
                .background(palette.bg0)
                .transition(.opacity)
            }
        }
        .animation(Motion.effect, value: reading != nil)
    }
}
