import ReadModel
import ScreenReading
import SwiftUI
import WallflowerDesign

extension Item {
    /** Whether it ends with a copy button: the reply that ends a turn, once the turn is done. */
    func copies(ongoing: Bool) -> Bool {
        if case .said(_, last: true) = self { return !ongoing }
        return false
    }
}

/** The conversation, newest at the bottom; following the newest until the user drags, and again once they've let go at it. */
struct Conversation: View {
    /** How many items are laid out, the latest: all at once, as a lazy list's scroll watchers chased its corrected heights. */
    static let laidOut = 150

    let items: [Item]
    let reading: Reading
    let status: Status?
    let asking: Prompt?
    let since: Int64
    let doing: String?
    let asked: Asked?
    let nudged: Int
    let aside: Aside?
    let answer: [String]
    let mode: Mode?
    let modes: [Mode]
    let onMode: (String) -> Void
    let picture: (String) async -> Data?
    let onKeys: ([String]) -> Void
    let onPicked: (Prompt, Prompt.Option) -> Void
    let onCompact: (() -> Void)?
    let onEdit: ((String, String) -> Void)?
    let feel: Fold
    let feelAside: Fold

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
        var aside: Bool
    }

    var body: some View {
        // The mode, and how full the context is, go beside the last reply's copy; with no copy to go beside, on their own.
        let context = reading.context > 0 ? ContextSize(tokens: reading.context, onCompact: onCompact) : nil
        let footer = context != nil || mode != nil ? Footer(mode: mode, modes: modes, onMode: onMode, context: context) : nil
        let besideCopy = items.last?.copies(ongoing: status != nil) == true
        let alone = footer != nil && !besideCopy
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
                        beside: newest ? footer : nil,
                        onEdit: edit
                    )
                }
                if let status {
                    // One item that changes between them, so a menu that flickers off and on doesn't leave and come back.
                    ZStack(alignment: .topLeading) {
                        switch status {
                        case .asking:
                            if let asking {
                                PromptCard(prompt: asking, onKeys: onKeys, nudged: aside == nil ? nudged : 0) { picked in onPicked(asking, picked) }
                                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { feel.laidOut($0, haptics) }
                                    .transition(.opacity)
                            }
                        case .working: Working(since: since, doing: doing).transition(.opacity)
                        case .waiting: Waiting(asked: asked) { if let asked { onKeys(asked.keys) } }.transition(.opacity)
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
                if alone, let footer {
                    FooterView(footer: footer).frame(maxWidth: .infinity, alignment: .trailing)
                }
                // What's aside of the program's line takes its place, at the foot.
                if let aside, aside.card {
                    AsideCard(aside: aside, answer: answer, nudged: nudged, onKeys: onKeys)
                        .id(asideKey(aside))
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { feelAside.laidOut($0, haptics) }
                        .transition(.opacity)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 48)
            .padding(.bottom, 12)
            .animation(Motion.spatial, value: Listed(keys: items.map(\.key), status: status, queued: reading.queued, context: alone, aside: aside != nil))
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
    let beside: Footer?
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
