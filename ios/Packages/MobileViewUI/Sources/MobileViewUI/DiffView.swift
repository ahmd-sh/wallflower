import MarkdownKit
import SwiftUI
import WallflowerDesign

/** A diff's [lines], unwrapped and scrolling sideways together, changed ones tinted edge to edge. */
struct DiffView: View {
    let lines: [DiffLine]
    @Environment(\.palette) private var palette
    @State private var width: CGFloat = 0

    private static let lineHeight: CGFloat = 15

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                    Text(shown(line))
                        .font(Fonts.mono(11))
                        .foregroundColor(palette.fg)
                        .lineLimit(1)
                        .fixedSize()
                        .frame(height: Self.lineHeight, alignment: .leading)
                }
            }
            .padding(.horizontal, 10)
            // Scrolling sideways, it's laid out at least as wide as it shows, so tints reach the edge.
            .frame(minWidth: width, alignment: .leading)
            .background(alignment: .top) {
                VStack(spacing: 0) {
                    ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                        tint(line).frame(height: Self.lineHeight)
                    }
                }
            }
            .padding(.vertical, 8)
        }
        .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.bg1)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func tint(_ line: DiffLine) -> Color {
        switch line.kind {
        case .added: palette.bgGreen
        case .removed: palette.bgRed
        default: .clear
        }
    }

    private func shown(_ line: DiffLine) -> AttributedString {
        func run(_ s: String, _ color: Color? = nil) -> AttributedString {
            var a = AttributedString(s)
            if let color { a.foregroundColor = color }
            return a
        }
        switch line.kind {
        case .added: return run("+ ", palette.green) + run(line.text)
        case .removed: return run("- ", palette.red) + run(line.text)
        case .context: return run("  " + line.text, palette.grey)
        case .hunk, .note: return run(line.text, palette.dim)
        }
    }
}
