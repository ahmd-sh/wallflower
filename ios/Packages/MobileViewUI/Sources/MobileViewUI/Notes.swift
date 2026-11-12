import ReadModel
import SwiftUI
import WallflowerDesign

/** Something that happened, small and centered; tapped, it opens, with its [detail] if it has one (a compaction's summary). */
struct Note: View {
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

/**
 * A message another agent sent the program, on the program's side: who, and
 * what it's about; tapped, the message itself opens beneath.
 */
struct AgentMessage: View {
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
struct Command: View {
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
