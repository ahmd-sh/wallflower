import ScreenReading
import SwiftUI
import WallflowerDesign

/** Which aside it is: an answer's question, or a view's name; its card starts afresh with another. */
func asideKey(_ aside: Aside) -> String {
    switch aside {
    case .view(let v): "view " + v.name
    case .answer(let a): "answer " + a.question
    case .tasks(let t): "tasks " + t.named
    }
}

extension Aside {
    /** Whether it goes at the conversation's foot, on a card, rather than in its place (a list of tasks). */
    var card: Bool { if case .tasks = self { false } else { true } }
}

/**
 * What the program shows aside of its line, whose keys are its own: a view
 * of its own, said so, with a way back; or an answer to a question asked
 * aside, as far as it's been read ([answer]), with esc to close it, and a way
 * down it while there may be more. Something sent while it's up waits: the
 * card says so a moment, with a shake ([nudged]).
 */
struct AsideCard: View {
    let aside: Aside
    let answer: [String]
    let nudged: Int
    let onKeys: ([String]) -> Void

    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    @Environment(\.haptics) private var haptics
    @State private var held = false
    @State private var holds = 0
    /** Rows read when more was last asked for, till more comes. */
    @State private var asked: Int?
    /** More was asked for and nothing came: it's all there. */
    @State private var ended = false

    var body: some View {
        card
            .keyframeAnimator(initialValue: CGFloat(0), trigger: holds) { card, x in
                card.offset(x: x)
            } keyframes: { _ in
                KeyframeTrack {
                    CubicKeyframe(-6, duration: 0.06)
                    CubicKeyframe(5, duration: 0.08)
                    CubicKeyframe(-3, duration: 0.08)
                    SpringKeyframe(0, duration: 0.25, spring: .init(response: 0.25, dampingRatio: 0.6))
                }
            }
            .onChange(of: nudged) { holds += 1 }
            .task(id: holds) {
                guard holds > 0 else { return }
                withAnimation(Motion.effect) { held = true }
                try? await Task.sleep(for: .seconds(asideHeldSeconds))
                if !Task.isCancelled { withAnimation(Motion.effect) { held = false } }
            }
            .onChange(of: answer.count) { _, n in if let at = asked, n > at { asked = nil } }
            .task(id: asked) {
                guard let at = asked else { return }
                try? await Task.sleep(for: .seconds(moreSeconds))
                if !Task.isCancelled && answer.count <= at {
                    ended = true
                    asked = nil
                }
            }
    }

    private func leave() {
        haptics.tick()
        onKeys(aside.leave)
    }

    @ViewBuilder
    private var card: some View {
        switch aside {
        case .view(let v):
            HStack(spacing: 0) {
                Circle().fill(palette.blue).frame(width: 6, height: 6)
                VStack(alignment: .leading, spacing: 0) {
                    Text("the terminal shows the \(v.name)")
                        .words(.label)
                        .foregroundColor(palette.fg)
                    Text(held ? "go back first, then send" : "what's typed goes to it till you're back")
                        .words(.caption)
                        .foregroundColor(held ? palette.orange : palette.grey)
                        .contentTransition(.opacity)
                }
                .padding(.horizontal, 10)
                .frame(maxWidth: .infinity, alignment: .leading)
                Chip(text: "back", color: palette.blue, action: leave)
            }
            .padding(.leading, 14)
            .padding(.trailing, 8)
            .padding(.vertical, 8)
            .background(palette.bg1)
            .clipShape(RoundedRectangle(cornerRadius: 18))
        case .tasks:
            EmptyView() // a page of its own (TasksPage)
        case .answer(let a):
            ZStack(alignment: .topTrailing) {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        Text("by the way")
                            .words(.caption)
                            .foregroundColor(palette.purple)
                        Text(a.question)
                            .words(.caption)
                            .foregroundColor(palette.grey)
                            .lineLimit(1)
                    }
                    .padding(.trailing, 44)
                    .padding(.top, 2)
                    if held {
                        Text("close this first (esc), then send")
                            .font(look.words(look.size(mono: 12, sans: 13)))
                            .foregroundColor(palette.orange)
                            .transition(.opacity)
                    }
                    if let rows = a.rows {
                        let size = look.size(mono: 14, sans: 15)
                        Text(a.text(answer))
                            .font(look.words(size))
                            .lineSpacing(Leading.words(look, size, look.size(mono: 21, sans: 22)))
                            .foregroundColor(palette.fg)
                            .textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)
                        // Taller than its room, down a page at a time, but for a row or two, so what shows next meets what's been read; till a page brings nothing.
                        if a.taller && !ended {
                            Chip(text: "more ↓", color: palette.blue) {
                                haptics.tick()
                                asked = answer.count
                                onKeys(Array(repeating: a.down, count: max(1, (rows.count - 2) / a.step)).flatMap { $0 })
                            }
                        }
                    } else {
                        Working(since: 0, doing: "answering")
                    }
                }
                .padding(.horizontal, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
                Chip(text: "esc", color: palette.grey, mono: true, action: leave)
            }
            .padding(10)
            .background(palette.bg1)
            .clipShape(RoundedRectangle(cornerRadius: 24))
        }
    }
}

/** A small rounded button, as the cards' esc. */
private struct Chip: View {
    let text: String
    let color: Color
    var mono = false
    let action: () -> Void
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(mono ? Fonts.mono(12) : look.words(12))
                .foregroundColor(color)
                .padding(.horizontal, 10)
                .padding(.vertical, 3)
                .background(RoundedRectangle(cornerRadius: 14).fill(palette.bg2))
        }
        .buttonStyle(Pressable())
    }
}

/** How long something sent while an aside is up says it's waiting. */
private let asideHeldSeconds: Double = 3

/** How long after asking for more of an answer nothing new means there's no more. */
private let moreSeconds: Double = 1.2
