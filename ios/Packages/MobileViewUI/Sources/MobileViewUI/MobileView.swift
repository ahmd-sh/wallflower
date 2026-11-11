import MarkdownKit
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
 * picking an option go to [onKeys], and the option to [onPicked]. Sideways
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
    /** Bumps when something sent was held for the prompt to be answered first. */
    let nudged: Int
    /** A message asked back to edit is being found in what the program lists, which isn't the user's to answer yet. */
    let seeking: Bool
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

    public init(
        reading: Reading,
        activity: Activity?,
        since: Int64,
        picture: @escaping (String) async -> Data?,
        prompt: Prompt? = nil,
        doing: String? = nil,
        nudged: Int = 0,
        seeking: Bool = false,
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
        self.nudged = nudged
        self.seeking = seeking
        self.onKeys = onKeys
        self.onPicked = onPicked
        self.onCompact = onCompact
        self.onEdit = onEdit
        self.title = title
        self.live = live
        self.onSwipe = onSwipe
        self.onSwipeEnd = onSwipeEnd
    }

    /** A prompt while the program says it's busy is one left on the screen, or a list that looks like one. */
    private var asking: Prompt? { activity != .working ? prompt : nil }

    private var status: Status? {
        if seeking { return .seeking }
        if asking != nil { return .asking }
        switch activity {
        case .working: return .working
        // The program says it's waiting a moment after its screen moved on (answered), so for that long it's working.
        case .waiting: return unread ? .waiting : .working
        default: return nil
        }
    }

    public var body: some View {
        let items = ReadModel.items(reading.shown)
        let phase: Phase = !reading.loaded ? .reading : items.isEmpty ? .empty : .shown
        let status = self.status
        ZStack(alignment: .topLeading) {
            Group {
                switch phase {
                case .reading: Hint(text: "reading…")
                case .empty: Empty(problem: reading.problem)
                case .shown:
                    Conversation(
                        items: items,
                        reading: reading,
                        status: status,
                        asking: asking,
                        since: since,
                        doing: stopping ? "stopping" : doing,
                        nudged: nudged,
                        picture: picture,
                        onKeys: onKeys,
                        onPicked: onPicked,
                        onCompact: onCompact,
                        onEdit: onEdit,
                        feel: feel
                    )
                }
            }
            .transition(.opacity)
            .animation(Motion.effect, value: phase)
            if let title {
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
        .task(id: Unread(activity: activity, asking: asking != nil)) {
            unread = false
            guard activity == .waiting && asking == nil else { return }
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
    }
}

private enum Phase { case reading, empty, shown }

/** What's below the conversation: a menu to answer, the program working, or waiting on something unread. */
private enum Status { case asking, working, waiting, seeking }

private struct Unread: Equatable {
    let activity: Activity?
    let asking: Bool
}

/** How long a stop from the phone says it's stopping before it's for the program to say. */
private let stoppingMs = 3_000

/** How long the program can say it's waiting without a menu on screen before that's what it is. */
private let unreadMs = 1_200

private let pictureHeight: CGFloat = 260

extension Item {
    /** Whether it ends with a copy button: the reply that ends a turn, once the turn is done. */
    func copies(ongoing: Bool) -> Bool {
        if case .said(_, last: true) = self { return !ongoing }
        return false
    }
}

/** How full the program's context is, and how to compact it. */
private struct Context {
    let tokens: Int
    let onCompact: (() -> Void)?
}

/** The conversation, newest at the bottom; following the newest until the user drags, and again once they've let go at it. */
private struct Conversation: View {
    /** How many items are laid out, the latest: all at once, as a lazy list's scroll watchers chased its corrected heights. */
    static let laidOut = 150

    let items: [Item]
    let reading: Reading
    let status: Status?
    let asking: Prompt?
    let since: Int64
    let doing: String?
    let nudged: Int
    let picture: (String) async -> Data?
    let onKeys: ([String]) -> Void
    let onPicked: (Prompt, Prompt.Option) -> Void
    let onCompact: (() -> Void)?
    let onEdit: ((String, String) -> Void)?
    let feel: Fold

    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    @State private var position = ScrollPosition(edge: .bottom)
    // Following the newest, not whether the list happens to be at the bottom as things change,
    // which a reply's copy row coming in can move it off.
    @State private var following = true
    @State private var scrolling: ScrollPhase = .idle
    @State private var spot = Spot(atBottom: true, away: false)

    private struct Heights: Equatable {
        var content: CGFloat
        var container: CGFloat
    }

    /** Where the list is: at the bottom, or far enough up for a way back. */
    private struct Spot: Equatable {
        var atBottom: Bool
        var away: Bool
    }

    /** What's listed, by key: what moves when it changes. */
    private struct Listed: Equatable {
        var keys: [String]
        var status: Status?
        var queued: [String]
        var context: Bool
    }

    var body: some View {
        // How full the context is goes beside the last reply's copy; with no copy to go beside, on its own.
        let context = reading.context > 0 ? Context(tokens: reading.context, onCompact: onCompact) : nil
        let besideCopy = items.last?.copies(ongoing: status != nil) == true
        let alone = context != nil && !besideCopy
        // Editing a message asks the program at the foot, where what it asks comes.
        let edit = onEdit.map { e in
            { (id: String, text: String) in
                following = true
                e(id, text)
            }
        }
        ScrollView {
            VStack(alignment: .leading, spacing: 14) { // not lazy: estimated heights corrected as rows come in kept the scroll position and its watchers chasing each other
                // The latest, laid out whole; what came before is the terminal's, as what the gateway didn't send is.
                let latest = Array(items.suffix(Self.laidOut))
                if reading.more || items.count > latest.count { Note(text: "earlier messages are in the terminal") }
                ForEach(Array(latest.enumerated()), id: \.element.key) { i, item in
                    let newest = i == latest.count - 1
                    ItemView(
                        item: item,
                        picture: picture,
                        ongoing: status != nil && newest,
                        beside: newest ? context : nil,
                        onEdit: edit
                    )
                }
                if let status {
                    // One item that changes between them, so a menu that flickers off and on doesn't leave and come back.
                    ZStack(alignment: .topLeading) {
                        switch status {
                        case .asking:
                            if let asking {
                                PromptCard(prompt: asking, onKeys: onKeys, nudged: nudged) { picked in onPicked(asking, picked) }
                                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { feel.laidOut($0, haptics) }
                                    .transition(.opacity)
                            }
                        case .working: Working(since: since, doing: doing).transition(.opacity)
                        case .waiting: Waiting().transition(.opacity)
                        case .seeking: Working(since: 0, doing: "finding your message").transition(.opacity)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .animation(Motion.effect, value: status)
                }
                // What waits for the program to take it is below its working, as Claude shows it.
                ForEach(Array(reading.queued.enumerated()), id: \.offset) { _, text in
                    Queued(text: text)
                }
                if alone, let context {
                    ContextButton(context: context).frame(maxWidth: .infinity, alignment: .trailing)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 48)
            .padding(.bottom, 12)
            .animation(Motion.spatial, value: Listed(keys: items.map(\.key), status: status, queued: reading.queued, context: alone))
        }
        .scrollPosition($position)
        .defaultScrollAnchor(.bottom)
        .scrollDismissesKeyboard(.interactively)
        .onScrollPhaseChange { _, now in
            scrolling = now
            if now == .interacting { following = false }
            if now == .idle && spot.atBottom { following = true }
        }
        .onScrollGeometryChange(for: Spot.self) { geo in
            let below = geo.contentSize.height + geo.contentInsets.bottom - geo.contentOffset.y - geo.containerSize.height
            return Spot(atBottom: below < 3, away: below > 220)
        } action: { _, now in
            withAnimation(Motion.spatial) { spot = now }
            if now.atBottom && scrolling == .idle { following = true }
        }
        // At the bottom, new entries glide into view; scrolled up to read, the list stays put.
        .onScrollGeometryChange(for: Heights.self) { geo in
            Heights(content: geo.contentSize.height, container: geo.containerSize.height)
        } action: { _, _ in
            if following { withAnimation(Motion.spatial) { position.scrollTo(edge: .bottom) } }
        }
        .overlay(alignment: .bottom) {
            if spot.away {
                Button {
                    following = true
                    withAnimation(Motion.spatial) { position.scrollTo(edge: .bottom) }
                } label: {
                    Text("↓ latest")
                        .words(.label)
                        .foregroundColor(palette.fg)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(RoundedRectangle(cornerRadius: 16).fill(palette.bg2))
                }
                .buttonStyle(Pressable())
                .padding(.bottom, 12)
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
    }
}

/** [ongoing]: the newest item of a turn still going, whose reply isn't the last word yet. [beside]: on its copy row's right. */
private struct ItemView: View {
    let item: Item
    let picture: (String) async -> Data?
    let ongoing: Bool
    let beside: Context?
    let onEdit: ((String, String) -> Void)?

    var body: some View {
        switch item {
        case let .steps(entries): Steps(steps: entries)
        case let .said(entry, _):
            switch entry {
            case let .picture(e): Picture(e: e, fetch: picture)
            case let .note(e): Note(text: e.text, detail: e.detail)
            case let .agent(e): AgentMessage(e: e)
            case let .command(c): Command(c: c)
            case let .said(e):
                if e.by == .user {
                    UserPrompt(text: e.text, onEdit: onEdit.map { edit in { edit(e.id, e.text) } })
                } else {
                    Reply(text: e.text, copyable: item.copies(ongoing: ongoing), beside: beside)
                }
            case .step, .thought: EmptyView() // folded into steps
            }
        }
    }
}

/** Something the user said: tapped, it opens if it's cut short; held, it offers to [onEdit] it and send it again. */
private struct UserPrompt: View {
    let text: String
    let onEdit: (() -> Void)?
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    @Environment(\.haptics) private var haptics
    @State private var open = false
    @State private var full: CGFloat = 0
    @State private var cap: CGFloat = 0
    @State private var copier = Copier()

    private static let shownLines = 12

    var body: some View {
        let size = look.size(mono: 14, sans: 15)
        let spacing = Leading.words(look, size, look.size(mono: 21, sans: 22))
        let cut = cap > 0 && full > cap + 0.5
        VStack(alignment: .trailing, spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                Text(text)
                    .font(look.words(size))
                    .lineSpacing(spacing)
                    .foregroundColor(palette.fg)
                    .fixedSize(horizontal: false, vertical: true)
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { full = $0 }
                    .frame(height: open || !cut ? nil : cap, alignment: .top)
                    .clipped()
                // What's cut off is said on a line of its own, as something to open.
                if cut && !open {
                    Text("…")
                        .font(look.words(20, weight: .bold))
                        .foregroundColor(palette.blue)
                        .frame(height: 16)
                }
            }
            .background(alignment: .topLeading) {
                Text(Array(repeating: "|", count: Self.shownLines).joined(separator: "\n"))
                    .font(look.words(size))
                    .lineSpacing(spacing)
                    .fixedSize()
                    .hidden()
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { cap = $0 }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(palette.bg2)
            .clipShape(RoundedRectangle(cornerRadius: 18))
            .contentShape(.contextMenuPreview, RoundedRectangle(cornerRadius: 18))
            .onTapGesture { withAnimation(Motion.spatial) { open.toggle() } }
            .contextMenu {
                if let onEdit { Button("edit", action: onEdit) }
                Button("copy") { copier.copy(text, haptics) }
            }
            CopyIcon { text }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.leading, 40)
    }
}

private struct Reply: View {
    let text: String
    let copyable: Bool
    let beside: Context?
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    @State private var copier = Copier()

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            // Held, words select, to copy them.
            MarkdownText(text)
            if copyable {
                HStack(spacing: 0) {
                    Button { copier.copy(text, haptics) } label: {
                        ZStack(alignment: .leading) {
                            Text("copy").opacity(copier.copied ? 0 : 1)
                            Text("copied").opacity(copier.copied ? 1 : 0)
                        }
                        .words(.caption)
                        .foregroundColor(palette.dim)
                        .padding(.vertical, 4)
                        .padding(.horizontal, 2)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(Pressable())
                    .animation(Motion.effect, value: copier.copied)
                    Spacer(minLength: 0)
                    if let beside { ContextButton(context: beside) }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/** A small copy button, ticked a moment after; [text] is what it copies, made when tapped. */
private struct CopyIcon: View {
    let text: () -> String
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    @State private var copier = Copier()

    var body: some View {
        Button { copier.copy(text(), haptics) } label: {
            ZStack {
                GlyphIcon(.copy, palette.dim, size: 13).opacity(copier.copied ? 0 : 1)
                GlyphIcon(.check, palette.dim, size: 13).opacity(copier.copied ? 1 : 0)
            }
            .padding(6)
            .contentShape(Circle())
        }
        .buttonStyle(Pressable())
        .animation(Motion.effect, value: copier.copied)
    }
}

/** How much of the program's context the conversation takes; tapped or held, it offers to compact it. */
private struct ContextButton: View {
    let context: Context
    @Environment(\.palette) private var palette

    var body: some View {
        let label = Text("context \(count(context.tokens))")
            .words(.caption)
            .foregroundColor(palette.purple)
            .padding(.vertical, 4)
            .padding(.horizontal, 2)
        if let compact = context.onCompact {
            Menu {
                Button("compact", action: compact)
            } label: {
                label.contentShape(Rectangle())
            }
            .menuStyle(.button)
            .buttonStyle(.plain)
        } else {
            label
        }
    }
}

private func count(_ n: Int) -> String {
    if n < 1_000 { return "\(n)" }
    if n < 1_000_000 { return "\(n / 1_000)k" }
    return String(format: "%.1fM", Double(n) / 1e6)
}

/** A picture, shown at about the size it was taken; tapped, it opens in the viewer, to zoom and share. */
private struct Picture: View {
    let e: Entry.Picture
    let fetch: (String) async -> Data?
    @Environment(\.displayScale) private var scale
    @Environment(\.viewing) private var viewing
    @Environment(\.haptics) private var haptics
    @State private var image: UIImage?
    @State private var room: CGFloat = 0

    init(e: Entry.Picture, fetch: @escaping (String) async -> Data?) {
        self.e = e
        self.fetch = fetch
        _image = State(initialValue: Pictures.kept(e.id))
    }

    private struct Load: Equatable {
        let id: String
        let ready: Bool
    }

    var body: some View {
        HStack(spacing: 0) {
            if e.by == .user { Spacer(minLength: 0) }
            if let image, room > 0 {
                // At its own size, or smaller to fit the width and the height it's allowed.
                let natural = CGSize(width: image.size.width / scale, height: image.size.height / scale)
                let k = min(1, room / max(natural.width, 1), pictureHeight / max(natural.height, 1))
                Image(uiImage: image)
                    .resizable()
                    .frame(width: natural.width * k, height: natural.height * k)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .contentShape(RoundedRectangle(cornerRadius: 12))
                    .onTapGesture {
                        guard let open = viewing.picture else { return }
                        haptics.tick()
                        open(e.id, e.mime)
                    }
                    .transition(.opacity)
            }
            if e.by != .user { Spacer(minLength: 0) }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { room = $0 }
        .task(id: Load(id: e.id, ready: room > 0)) {
            guard image == nil, room > 0, let data = await fetch(e.id) else { return }
            let decoded = await Pictures.decode(e.id, data, width: Int(room * scale), height: Int(pictureHeight * scale))
            withAnimation(Motion.effect) { image = decoded }
        }
    }
}

/** Something that happened, small and centered; tapped, it opens, with its [detail] if it has one (a compaction's summary). */
private struct Note: View {
    let text: String
    var detail: String = ""
    @Environment(\.palette) private var palette
    @State private var open = false

    var body: some View {
        VStack(spacing: 0) {
            Text(detail.isEmpty || open ? text : "\(text) · see more")
                .words(.caption)
                .foregroundColor(palette.dim)
                .multilineTextAlignment(.center)
                .lineLimit(open || !detail.isEmpty ? nil : 2)
            if open && !detail.isEmpty {
                Text(detail)
                    .words(.caption)
                    .foregroundColor(palette.grey)
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 12).fill(palette.bg1))
                    .padding(.top, 8)
                    .transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 24)
        .contentShape(Rectangle())
        .onTapGesture { withAnimation(Motion.spatial) { open.toggle() } }
        .clipped()
    }
}

/** Something the user sent that waits for the program to take it: theirs, faint, said to be waiting. */
private struct Queued: View {
    let text: String
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look

    var body: some View {
        let size = look.size(mono: 14, sans: 15)
        VStack(alignment: .trailing, spacing: 4) {
            Text(text)
                .font(look.words(size))
                .lineSpacing(Leading.words(look, size, look.size(mono: 21, sans: 22)))
                .foregroundColor(palette.grey)
                .lineLimit(4)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(palette.bg3, lineWidth: 1))
            Text("queued")
                .words(.caption)
                .foregroundColor(palette.dim)
                .padding(.trailing, 6)
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.leading, 40)
    }
}

/**
 * A message another agent sent the program, on the program's side: who, and
 * what it's about; tapped, the message itself opens beneath.
 */
private struct AgentMessage: View {
    let e: Entry.Agent
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    @State private var open = false
    @State private var feel = Fold()

    var body: some View {
        let opens = !e.message.isEmpty
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 0) {
                GlyphIcon(.spark, palette.purple, size: 11)
                Text(e.name)
                    .words(.label, weight: .semibold)
                    .foregroundColor(palette.purple)
                    .lineLimit(1)
                    .padding(.leading, 6)
                if opens {
                    Text(open ? "  hide" : "  see all")
                        .words(.caption)
                        .foregroundColor(palette.dim)
                        .fixedSize()
                }
            }
            if !open || !opens {
                Text(e.about)
                    .words(.label)
                    .foregroundColor(palette.fg)
                    .lineLimit(3)
            } else {
                MarkdownText(e.message + (more(e.cut, "character").map { "\n\n" + $0 } ?? ""))
                    .transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(palette.bg1)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .contentShape(RoundedRectangle(cornerRadius: 14))
        .onTapGesture {
            guard opens else { return }
            feel.tap()
            withAnimation(Motion.spatial) { open.toggle() }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { feel.laidOut($0, haptics) }
        .padding(.trailing, 24)
    }
}

/** A command the user ran, on their side and marked as one, with what it said back beneath; tapped, that opens if it's long. */
private struct Command: View {
    let c: Entry.Command
    @Environment(\.palette) private var palette
    @State private var open = false

    var body: some View {
        VStack(alignment: .trailing, spacing: 4) {
            Text([c.name, c.args].filter { !$0.isEmpty }.joined(separator: " "))
                .font(Fonts.mono(13))
                .foregroundColor(palette.purple)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(RoundedRectangle(cornerRadius: 14).fill(palette.bg2))
            if !c.output.isEmpty {
                Text(c.output + (more(c.outputCut, "character").map { "\n" + $0 } ?? ""))
                    .font(Fonts.mono(12))
                    .lineSpacing(Leading.mono(12, 17))
                    .foregroundColor(palette.grey)
                    .lineLimit(open ? nil : 3)
                    .padding(.horizontal, 4)
                    .contentShape(Rectangle())
                    .onTapGesture { withAnimation(Motion.spatial) { open.toggle() } }
            }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.leading, 40)
    }
}

/** A run of tool calls and thinking, as one line saying what ran; open, a line per step. */
private struct Steps: View {
    let steps: [Entry]
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    @State private var open = false
    @State private var feel = Fold()

    private var summary: AttributedString {
        let tools = steps.compactMap { e -> Entry.Step? in if case let .step(s) = e { s } else { nil } }
        let failed = tools.count { $0.status == .failed }
        var names: [String] = []
        for t in tools where !names.contains(t.name) { names.append(t.name) }
        var out = AttributedString(steps.count == 1 ? "1 step" : "\(steps.count) steps")
        if !names.isEmpty {
            out += AttributedString(" · " + names.prefix(3).joined(separator: ", ") + (names.count > 3 ? " +\(names.count - 3)" : ""))
        }
        if failed > 0 {
            out += AttributedString(" · ")
            var red = AttributedString("\(failed) failed")
            red.foregroundColor = palette.red
            out += red
        }
        return out
    }

    private var running: Bool {
        if case let .step(s)? = steps.last(where: { if case .step = $0 { true } else { false } }) { return s.status == .running }
        return false
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                feel.tap()
                if open { feel.leave(haptics) }
                withAnimation(Motion.spatial) { open.toggle() }
            } label: {
                HStack(spacing: 0) {
                    Text("▸")
                        .font(Fonts.mono(12))
                        .foregroundColor(palette.grey)
                        .rotationEffect(.degrees(open ? 90 : 0))
                    if running { Running().padding(.leading, 8) }
                    Text(summary)
                        .words(.label)
                        .foregroundColor(palette.grey)
                        .lineLimit(1)
                        .padding(.leading, 8)
                }
                .padding(.vertical, 4)
                .contentShape(Rectangle())
            }
            .buttonStyle(Lit(radius: 8))
            if open {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(steps) { Step(s: $0) }
                    CopyIcon { trace(steps) }
                }
                .padding(.leading, 6)
                .padding(.top, 4)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { feel.laidOut($0, haptics) }
                .transition(.opacity)
            }
        }
        // It opens downward, what's beneath showing as it grows.
        .clipped()
    }
}

/** A dot that breathes while a tool runs. */
private struct Running: View {
    @Environment(\.palette) private var palette
    @Environment(\.mobileViewLive) private var live

    var body: some View {
        BreathingDot(color: palette.purple, breathing: live, low: 0.3, period: 0.7)
            .frame(width: 6, height: 6)
    }
}

private struct Step: View {
    let s: Entry
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    @State private var open = false
    @State private var feel = Fold()

    private var step: Entry.Step? { if case let .step(st) = s { st } else { nil } }

    private var input: String {
        if let step { return step.input }
        if case let .thought(t) = s { return t.text }
        return ""
    }

    private var diffLines: [DiffLine] {
        guard let step, !step.diff.isEmpty else { return [] }
        return MarkdownKit.diffLines(step.diff) + [more(step.diffCut, "line")].compactMap { $0.map { DiffLine(kind: .note, text: $0) } }
    }

    var body: some View {
        let failed = step?.status == .failed
        let output = step?.output ?? ""
        let diff = diffLines
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                Text(step?.name ?? "thinking")
                    .font(step == nil ? slanted(Fonts.monoName, 12) : Fonts.mono(12))
                    .foregroundColor(failed ? palette.red : step?.status == .running ? palette.purple : palette.aqua)
                if !open {
                    Text(input.split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? "")
                        .font(Fonts.mono(12))
                        .foregroundColor(palette.grey)
                        .lineLimit(1)
                        // A changed file's name is at its end.
                        .truncationMode(diff.isEmpty ? .tail : .middle)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.leading, 8)
                } else {
                    Spacer(minLength: 0)
                }
                if !diff.isEmpty {
                    Text(counts(diffCounts(diff)))
                        .font(Fonts.mono(11))
                        .fixedSize()
                        .padding(.leading, 8)
                }
            }
            if open {
                if !input.isEmpty {
                    Text(input)
                        .font(Fonts.mono(12))
                        .lineSpacing(Leading.mono(12, 17))
                        .foregroundColor(palette.fg)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 4)
                }
                if !diff.isEmpty && !failed {
                    DiffView(lines: diff).padding(.top, 6)
                } else if !output.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        Text(output + (more(step?.outputCut ?? 0, "character").map { "\n" + $0 } ?? ""))
                            .font(Fonts.mono(11))
                            .lineSpacing(Leading.mono(11, 15))
                            .foregroundColor(failed ? palette.red : palette.grey)
                            .fixedSize()
                            .padding(10)
                    }
                    .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(palette.bg1)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .padding(.top, 6)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .contentShape(Rectangle())
        .onTapGesture {
            feel.tap()
            withAnimation(Motion.spatial) { open.toggle() }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { feel.laidOut($0, haptics) } // the size it's going to
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func counts(_ c: (added: Int, removed: Int)) -> AttributedString {
        var out = AttributedString()
        if c.added > 0 {
            var a = AttributedString("+\(c.added)")
            a.foregroundColor = palette.green
            out += a
        }
        if c.added > 0 && c.removed > 0 { out += AttributedString(" ") }
        if c.removed > 0 {
            var r = AttributedString("−\(c.removed)")
            r.foregroundColor = palette.red
            out += r
        }
        return out
    }
}

/** The program working on its turn, as it puts it ([doing]) if it says, and for how long; gone when it's done. */
private struct Working: View {
    let since: Int64
    let doing: String?
    @Environment(\.palette) private var palette
    @Environment(\.mobileViewLive) private var live

    var body: some View {
        // The words change once a second; the light passing along them moves on its own, without laying them out again.
        TimelineView(.animation(minimumInterval: 1, paused: !live || since <= 0)) { timeline in
            let label = (doing?.lowercased() ?? "working") + (since > 0 ? " · " + duration(elapsed(since, timeline.date)) : "")
            HStack(spacing: 0) {
                Running()
                Text(label)
                    .words(.label)
                    .foregroundColor(palette.grey)
                    .overlay {
                        if live {
                            Text(label)
                                .words(.label)
                                .foregroundColor(palette.purple)
                                .mask(Sheen())
                        }
                    }
                    .padding(.leading, 8)
            }
            .padding(.vertical, 2)
        }
    }
}

/** A band of light that passes along what it masks, over and over. */
private struct Sheen: View {
    @State private var at: CGFloat = -0.5

    var body: some View {
        GeometryReader { g in
            LinearGradient(colors: [.clear, .black, .clear], startPoint: .leading, endPoint: .trailing)
                .frame(width: g.size.width * 2 / 3)
                .offset(x: at * g.size.width - g.size.width / 3)
        }
        .onAppear {
            guard !UserDefaults.standard.bool(forKey: "wfStill") else { return } // still for UI tests, which wait for the app to come to rest
            withAnimation(.linear(duration: 1.6).repeatForever(autoreverses: false)) { at = 1.5 }
        }
    }
}

private func elapsed(_ since: Int64, _ now: Date) -> Int64 {
    since > 0 ? max((Int64(now.timeIntervalSince1970 * 1_000) - since) / 1_000, 0) : 0
}

private func duration(_ seconds: Int64) -> String {
    if seconds < 60 { return "\(seconds)s" }
    if seconds < 3_600 { return "\(seconds / 60)m \(seconds % 60)s" }
    return "\(seconds / 3_600)h \(seconds % 3_600 / 60)m"
}

/** The program needs an answer that isn't on its screen as a menu wallflower can read. */
private struct Waiting: View {
    @Environment(\.palette) private var palette

    var body: some View {
        HStack(spacing: 0) {
            Circle().fill(palette.orange).frame(width: 6, height: 6)
            Text("waiting for you · answer in the terminal")
                .words(.label)
                .foregroundColor(palette.grey)
                .padding(.leading, 8)
        }
        .padding(.vertical, 2)
    }
}

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
