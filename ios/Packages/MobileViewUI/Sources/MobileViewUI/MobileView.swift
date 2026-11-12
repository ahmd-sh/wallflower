import ReadModel
import ScreenReading
import SwiftUI
import WallflowerDesign

/**
 * A pane's mobile view in place of its terminal: what each side said, with
 * each run of steps and thinking folded into one line, to open when
 * wanted. The newest is at the bottom, which stays in view as entries come.
 * Below it, while the program works, a line says so; when its screen offers
 * a [prompt] (a permission, a question), that's there to answer, and the keys
 * picking an option go to [onKeys], and the option to [onPicked]. What the
 * program shows [aside] of its line, where keys are its own, is said at the
 * foot, with the keys that put it away. Its [mode] goes beside how full its
 * context is, to pick another of those it goes round ([modes]). Sideways
 * drags go to [onSwipe] and [onSwipeEnd], to move between panes as on the
 * terminal. The conversation's [title], if its tab doesn't say it, sits at
 * the top.
 */
public struct MobileView: View {
    let reading: Reading
    /** What the program is doing, if it says, and [since] when (Unix milliseconds). */
    let activity: Activity?
    let since: Int64
    /** A picture entry's bytes, asked for when it shows. */
    let picture: (String) async -> Data?
    let prompt: Prompt?
    /** What the program says it's doing, in its words, while it works. */
    let doing: String?
    /** Questions it asked that wait on its screen to be brought up, which [onKeys] does. */
    let asked: Asked?
    /** Bumps when something sent was held for the prompt to be answered first. */
    let nudged: Int
    /** A message asked back to edit is being found in what the program lists, which isn't the user's to answer yet. */
    let seeking: Bool
    let aside: Aside?
    /** Of an answer aside, what's been read of it, as it's scrolled. */
    let answer: [String]
    let mode: Mode?
    let modes: [Mode]
    let onMode: (String) -> Void
    let onKeys: ([String]) -> Void
    let onPicked: (Prompt, Prompt.Option) -> Void
    /** Sends the program its compact command, from the context button; nil while it can't. */
    let onCompact: (() -> Void)?
    /** Asks for one of the user's messages back (its id, its text), to edit; nil while it can't be. */
    let onEdit: ((String, String) -> Void)?
    let title: String?
    /** On screen, so what moves moves; a page beside it holds still. */
    let live: Bool
    let onSwipe: ((CGFloat) -> Void)?
    let onSwipeEnd: ((CGFloat, CGFloat) -> Void)?

    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    @Environment(\.viewing) private var viewing
    @State private var unread = false
    @State private var stopping = false
    @State private var feel = Fold()
    @State private var feelAside = Fold()
    @State private var grouped = Grouped()

    public init(
        reading: Reading,
        activity: Activity?,
        since: Int64,
        picture: @escaping (String) async -> Data?,
        prompt: Prompt? = nil,
        doing: String? = nil,
        asked: Asked? = nil,
        nudged: Int = 0,
        seeking: Bool = false,
        aside: Aside? = nil,
        answer: [String] = [],
        mode: Mode? = nil,
        modes: [Mode] = [],
        onMode: @escaping (String) -> Void = { _ in },
        onKeys: @escaping ([String]) -> Void = { _ in },
        onPicked: @escaping (Prompt, Prompt.Option) -> Void = { _, _ in },
        onCompact: (() -> Void)? = nil,
        onEdit: ((String, String) -> Void)? = nil,
        title: String? = nil,
        live: Bool = true,
        onSwipe: ((CGFloat) -> Void)? = nil,
        onSwipeEnd: ((CGFloat, CGFloat) -> Void)? = nil
    ) {
        self.reading = reading
        self.activity = activity
        self.since = since
        self.picture = picture
        self.prompt = prompt
        self.doing = doing
        self.asked = asked
        self.nudged = nudged
        self.seeking = seeking
        self.aside = aside
        self.answer = answer
        self.mode = mode
        self.modes = modes
        self.onMode = onMode
        self.onKeys = onKeys
        self.onPicked = onPicked
        self.onCompact = onCompact
        self.onEdit = onEdit
        self.title = title
        self.live = live
        self.onSwipe = onSwipe
        self.onSwipeEnd = onSwipeEnd
    }

    /** The program's list of its tasks, if that's what it shows aside. */
    private var tasks: Aside.Tasks? { if case .tasks(let t) = aside { t } else { nil } }

    /** A prompt while the program says it's busy is one left on the screen, or a list that looks like one. */
    private var asking: Prompt? { activity != .working ? prompt : nil }

    private var status: Status? {
        if seeking { return .seeking }
        if asking != nil { return .asking }
        switch activity {
        case .working: return .working
        // Claude says it waits while its /btw answer is up, which asks nothing: the card says what's there.
        case .waiting where aside != nil: return nil
        // The program says it's waiting a moment after its screen moved on (answered), so for that long it's working.
        case .waiting: return unread ? .waiting : .working
        default: return nil
        }
    }

    public var body: some View {
        let items = grouped.items(reading.shown)
        let status = self.status
        // Nothing said yet, a conversation can still have something at its foot: an answer aside, a menu, what's queued.
        let phase: Phase = tasks != nil ? .tasks
            : !reading.loaded ? .reading
            : items.isEmpty && aside == nil && status == nil && reading.queued.isEmpty ? .empty : .shown
        ZStack(alignment: .topLeading) {
            Group {
                switch phase {
                // The program's list of its tasks takes the conversation's place, as it does the program's screen.
                case .tasks: if let tasks { TasksPage(tasks: tasks, onKeys: onKeys, nudged: nudged) }
                case .reading: Hint(text: "reading…")
                case .empty:
                    // The mode can be picked before the first message.
                    ZStack(alignment: .bottomTrailing) {
                        Empty(problem: reading.problem)
                        if let mode {
                            FooterView(footer: Footer(mode: mode, modes: modes, onMode: onMode, context: nil))
                                .padding(.horizontal, 16)
                                .padding(.vertical, 12)
                        }
                    }
                case .shown:
                    Conversation(
                        items: items,
                        reading: reading,
                        status: status,
                        asking: asking,
                        since: since,
                        doing: stopping ? "stopping" : doing,
                        asked: asked,
                        nudged: nudged,
                        aside: aside,
                        answer: answer,
                        mode: mode,
                        modes: modes,
                        onMode: onMode,
                        picture: picture,
                        onKeys: onKeys,
                        onPicked: onPicked,
                        onCompact: onCompact,
                        onEdit: onEdit,
                        feel: feel,
                        feelAside: feelAside
                    )
                }
            }
            .transition(.opacity)
            .animation(Motion.effect, value: phase)
            if let title, phase != .tasks { // the list says what it is
                Capped(width: 240) {
                    Text(title)
                        .words(.label)
                        .foregroundColor(palette.grey)
                        .lineLimit(1)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .chrome()
                }
                .padding(8)
                .transition(.opacity)
            }
        }
        .animation(Motion.effect, value: title)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(palette.bg0)
        .environment(\.mobileViewLive, live)
        .environment(\.openURL, viewing.opener)
        .gesture(Swipe(onDrag: onSwipe, onEnd: onSwipeEnd))
        .task(id: Unread(activity: activity, asking: asking != nil, asked: asked != nil)) {
            unread = false
            guard activity == .waiting && asking == nil else { return }
            // What waits to be brought up isn't on its way to the screen: it says so at once.
            if asked != nil {
                unread = true
                return
            }
            try? await Task.sleep(for: .milliseconds(unreadMs))
            if !Task.isCancelled { unread = true }
        }
        // Stopped from here, it says so at once, until the program says it's stopped or a few seconds pass.
        .task(id: reading.stopped) {
            let left = stoppingMs - (Int(Date().timeIntervalSince1970 * 1_000) - reading.stopped)
            if reading.stopped != 0 && left > 0 {
                stopping = true
                try? await Task.sleep(for: .milliseconds(left))
                if Task.isCancelled { return }
            }
            stopping = false
        }
        // The card opening and shutting, felt even where the status line leaves with it.
        .onChange(of: status == .asking, initial: true) { _, open in feel.shown(open, haptics) }
        .onChange(of: aside != nil, initial: true) { _, open in feelAside.shown(open, haptics) }
    }
}

private enum Phase { case tasks, reading, empty, shown }

/** What's below the conversation: a menu to answer, the program working, or waiting on something unread. */
enum Status { case asking, working, waiting, seeking }

private struct Unread: Equatable {
    let activity: Activity?
    let asking: Bool
    let asked: Bool
}

/** How long a stop from the phone says it's stopping before it's for the program to say. */
private let stoppingMs = 3_000

/** How long the program can say it's waiting without a menu on screen before that's what it is. */
private let unreadMs = 1_200

private struct Hint: View {
    let text: String
    @Environment(\.palette) private var palette

    var body: some View {
        Text(text)
            .words(.body)
            .foregroundColor(palette.grey)
            .multilineTextAlignment(.center)
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/** A conversation with nothing in it yet, to start from the composer; or, with a [problem], why there's nothing to show. */
private struct Empty: View {
    let problem: String?
    @Environment(\.palette) private var palette

    var body: some View {
        VStack(spacing: 10) {
            GlyphIcon(.chat, palette.bg4, size: 30)
            Text(problem == nil ? "a new conversation" : "nothing to show")
                .words(.title)
                .foregroundColor(palette.grey)
            Text(problem ?? "what you send below starts it")
                .words(.body)
                .foregroundColor(palette.dim)
                .multilineTextAlignment(.center)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/** Its content no wider than [width], and no wider than it needs. */
private struct Capped: Layout {
    let width: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        subviews.first?.sizeThatFits(ProposedViewSize(width: min(proposal.width ?? width, width), height: proposal.height)) ?? .zero
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, proposal: ProposedViewSize(width: bounds.width, height: bounds.height))
    }
}

/**
 * The conversation grouped (ReadModel.items), again only when what's shown
 * changes: the view's drawn again for much else (the clock, the line, a
 * swipe). A cache, not state: nothing watches it.
 */
@MainActor
private final class Grouped {
    private var entries: [Entry] = []
    private var grouped: [Item] = []

    func items(_ shown: [Entry]) -> [Item] {
        if shown != entries { // an array unchanged is the same storage, which compares at once
            entries = shown
            grouped = ReadModel.items(shown)
        }
        return grouped
    }
}
