import ScreenReading
import SwiftUI
import WallflowerDesign

/**
 * Choices the program's screen offers, to answer by touch: what it's about,
 * its questions as tabs, its options. Picking one sends [onKeys] the keys
 * that pick it on the screen, so the program, and the computer, see the same
 * answer as if typed, and [onPick] the option. The card goes when the screen
 * moves on.
 */
public struct PromptCard: View {
    let prompt: Prompt
    let onKeys: ([String]) -> Void
    let nudged: Int
    let onPick: (Prompt.Option) -> Void

    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    @Environment(\.haptics) private var haptics
    @State private var held = false
    @State private var holds = 0

    public init(prompt: Prompt, onKeys: @escaping ([String]) -> Void, nudged: Int = 0, onPick: @escaping (Prompt.Option) -> Void = { _ in }) {
        self.prompt = prompt
        self.onKeys = onKeys
        self.nudged = nudged
        self.onPick = onPick
    }

    public var body: some View {
        ZStack(alignment: .topTrailing) {
            VStack(alignment: .leading, spacing: 6) {
                // The first row leaves the corner to esc.
                if !prompt.tabs.isEmpty { tabs.padding(.trailing, escRoom) }
                if !prompt.lines.isEmpty { About(lines: prompt.lines, firstEnd: prompt.tabs.isEmpty ? escRoom : 0) }
                if prompt.tabs.isEmpty && prompt.lines.isEmpty { Spacer().frame(height: 18) }
                if held {
                    Text("answer this first (or esc), then send")
                        .font(look.words(look.size(mono: 12, sans: 13)))
                        .foregroundColor(palette.orange)
                        .padding(.horizontal, 4)
                        .transition(.opacity)
                }
                VStack(spacing: 0) {
                    ForEach(Array(prompt.options.enumerated()), id: \.offset) { i, option in
                        if i > 0 { Rule().padding(.horizontal, 10) }
                        Choice(option: option) {
                            if let checked = option.checked { haptics.toggle(!checked) } else { haptics.confirm() }
                            onKeys(prompt.keys(option))
                            onPick(option)
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                haptics.tick()
                onKeys(["Escape"])
            } label: {
                Text("esc")
                    .font(Fonts.mono(12))
                    .foregroundColor(palette.grey)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 3)
                    .background(RoundedRectangle(cornerRadius: 14).fill(palette.bg2))
            }
            .buttonStyle(Pressable())
        }
        .padding(10)
        .background(palette.bg1)
        .clipShape(RoundedRectangle(cornerRadius: 24))
        // Something sent while it's open waits: it says so a moment, with a shake.
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
            try? await Task.sleep(for: .seconds(heldSeconds))
            if !Task.isCancelled { withAnimation(Motion.effect) { held = false } }
        }
    }

    private var tabs: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                ForEach(Array(prompt.tabs.enumerated()), id: \.offset) { _, tab in
                    Button {
                        haptics.tick()
                        onKeys(prompt.keys(tab))
                    } label: {
                        Text(tab.label)
                            .font(look.words(look.size(mono: 12, sans: 13)))
                            .foregroundColor(tab.current ? palette.fg : palette.grey)
                            .lineLimit(1)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                            .background(RoundedRectangle(cornerRadius: 14).fill(tab.current ? palette.bg3 : palette.bg2))
                    }
                    .buttonStyle(Pressable())
                    .allowsHitTesting(prompt.tabs.count > 1)
                }
            }
        }
        .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

private let escRoom: CGFloat = 44

private let heldSeconds = 3.0

/** What the menu is about: the first and last lines (a title, the question) stand out, boxed rows are code. */
private struct About: View {
    let lines: [Prompt.Line]
    let firstEnd: CGFloat
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look

    /** Lines from [start]: a run of code, or one of prose. */
    private struct Run {
        let start: Int
        let lines: [Prompt.Line]
    }

    private var runs: [Run] {
        var out: [Run] = []
        var i = 0
        while i < lines.count {
            let start = i
            if lines[i].code {
                while i < lines.count && lines[i].code { i += 1 }
            } else {
                i += 1
            }
            out.append(Run(start: start, lines: Array(lines[start..<i])))
        }
        return out
    }

    var body: some View {
        let prose = lines.indices.filter { !lines[$0].code }
        VStack(alignment: .leading, spacing: 4) {
            ForEach(runs, id: \.start) { run in
                let end = run.start == 0 ? firstEnd : 0
                if run.lines[0].code {
                    ScrollView(.horizontal, showsIndicators: false) {
                        Text(run.lines.map(\.text).joined(separator: "\n"))
                            .font(Fonts.mono(12))
                            .lineSpacing(Leading.mono(12, 16))
                            .foregroundColor(palette.fg)
                            .fixedSize()
                            .padding(.horizontal, 10)
                            .padding(.vertical, 8)
                    }
                    .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(palette.bg0)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .padding(.trailing, end)
                } else {
                    let stands = run.start == prose.first || run.start == prose.last
                    let size = stands ? look.size(mono: 13, sans: 15) : look.size(mono: 12, sans: 13)
                    let weight: Font.Weight = stands ? (look == .grotesque ? .semibold : .bold) : .regular
                    Text(run.lines[0].text)
                        .font(look.words(size, weight: weight))
                        .lineSpacing(Leading.words(look, size, look.size(mono: 17, sans: 19), weight: weight))
                        .foregroundColor(stands ? palette.fg : palette.grey)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.trailing, end)
                }
            }
        }
        .padding(.horizontal, 4)
    }
}

/** An option: its number (the key that picks it) or box, its label, and what it means beneath. The cursor's one is lit. */
private struct Choice: View {
    let option: Prompt.Option
    let action: () -> Void
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look

    var body: some View {
        Button(action: action) {
            HStack(spacing: 0) {
                // As wide as a box, so labels line up whichever a menu mixes.
                Group {
                    if let checked = option.checked {
                        Checkbox(on: checked)
                    } else {
                        Text(option.number.map(String.init) ?? "›")
                            .font(Fonts.mono(12))
                            .foregroundColor(palette.dim)
                    }
                }
                .frame(width: 24)
                VStack(alignment: .leading, spacing: 0) {
                    line(option.label, look.size(mono: 13, sans: 14), look.size(mono: 17, sans: 18), option.muted ? palette.grey : palette.fg)
                    if !option.detail.isEmpty {
                        line(option.detail, look.size(mono: 11, sans: 12), look.size(mono: 15, sans: 16), palette.grey)
                    }
                    // A lesser option under the cursor is one to write: what's sent from below goes into it.
                    if option.current && option.muted && option.scroll == 0 {
                        line("write your answer below, and send", look.size(mono: 11, sans: 12), look.size(mono: 15, sans: 16), palette.blue)
                    }
                }
                .padding(.leading, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(RoundedRectangle(cornerRadius: 14).fill(option.current ? palette.bg2 : palette.bg1))
            .contentShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(Lit(radius: 14))
    }

    private func line(_ text: String, _ size: CGFloat, _ lineHeight: CGFloat, _ color: Color) -> some View {
        Text(text)
            .font(look.words(size))
            .lineSpacing(Leading.words(look, size, lineHeight))
            .foregroundColor(color)
            .multilineTextAlignment(.leading)
            .fixedSize(horizontal: false, vertical: true)
    }
}
