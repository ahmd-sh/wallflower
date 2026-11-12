import MarkdownKit
import ReadModel
import SwiftUI
import WallflowerDesign

/** A run of tool calls and thinking, as one line saying what ran; open, a line per step. */
struct Steps: View {
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
struct Running: View {
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
