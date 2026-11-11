import MarkdownKit
import SwiftUI
import WallflowerDesign

/** Markdown, rendered in wallflower's fonts: words in the look's, code in mono. Held, its words select, to copy. */
public struct MarkdownText: View {
    let text: String

    public init(_ text: String) {
        self.text = text
    }

    public var body: some View {
        Blocks(blocks: Markdown.parse(text))
    }
}

private struct Blocks: View {
    let blocks: [Block]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, b in
                BlockView(block: b)
            }
        }
    }
}

private struct BlockView: View {
    let block: Block
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look

    private var size: CGFloat { look.size(mono: 14, sans: 15) }
    private var lineHeight: CGFloat { look.size(mono: 21, sans: 22) }

    var body: some View {
        switch block {
        case let .heading(level, text):
            let size: CGFloat = level == 1 ? 21 : level == 2 ? 18 : 16
            let lineHeight: CGFloat = level == 1 ? 28 : level == 2 ? 25 : 22
            Paragraph(text: inline(text, size: size, weight: .semibold), lineSpacing: Leading.words(look, size, lineHeight, weight: .semibold))
                .padding(.top, 4)
        case let .paragraph(text):
            Paragraph(text: inline(text, size: size), lineSpacing: Leading.words(look, size, lineHeight))
        case let .code(_, text):
            ScrollView(.horizontal, showsIndicators: false) {
                Text(text)
                    .font(Fonts.mono(12))
                    .lineSpacing(Leading.mono(12, 17))
                    .foregroundColor(palette.fg)
                    .fixedSize()
                    .textSelection(.enabled)
                    .padding(12)
            }
            .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.bg1)
            .clipShape(RoundedRectangle(cornerRadius: 10))
        case let .quote(blocks):
            Blocks(blocks: blocks)
                .padding(.leading, 15)
                .background(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 2).fill(palette.bg3).frame(width: 3)
                }
        case let .list(ordered, start, items):
            VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(items.enumerated()), id: \.offset) { i, item in
                    HStack(alignment: .top, spacing: 0) {
                        Text(ordered ? "\(start + i)." : "•")
                            .font(look.words(size))
                            .foregroundColor(palette.grey)
                            .frame(minWidth: ordered ? 22 : 14, alignment: .leading)
                        Blocks(blocks: item).padding(.leading, 4)
                    }
                }
            }
        case let .table(header, rows):
            let table = Grid(alignment: .leading, horizontalSpacing: 0, verticalSpacing: 0) {
                GridRow {
                    ForEach(header.indices, id: \.self) { c in cell(header[c], weight: .semibold) }
                }
                ForEach(rows.indices, id: \.self) { r in
                    GridRow {
                        ForEach(rows[r].indices, id: \.self) { c in cell(rows[r][c]) }
                    }
                }
            }
            .padding(.vertical, 4)
            // As wide as it is, scrolling sideways once it's wider than there's room for.
            ViewThatFits(in: .horizontal) {
                table
                    .background(palette.bg1)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                ScrollView(.horizontal, showsIndicators: false) { table }
                    .background(palette.bg1)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            }
        case .rule:
            Rule(color: palette.bg3).padding(.vertical, 4)
        }
    }

    private func cell(_ text: String, weight: Font.Weight = .regular) -> some View {
        Text(inline(text, size: size, weight: weight))
            .foregroundColor(palette.fg)
            .tint(palette.blue)
            .lineLimit(1)
            .fixedSize()
            .padding(.vertical, 6)
            .padding(.horizontal, 12)
    }

    private func inline(_ text: String, size: CGFloat, weight: Font.Weight = .regular) -> AttributedString {
        styled(text, look: look, palette: palette, size: size, weight: weight)
    }
}

/** A paragraph's words, wrapping, selectable. */
private struct Paragraph: View {
    let text: AttributedString
    let lineSpacing: CGFloat
    @Environment(\.palette) private var palette

    var body: some View {
        Text(text)
            .foregroundColor(palette.fg)
            .tint(palette.blue)
            .lineSpacing(lineSpacing)
            .fixedSize(horizontal: false, vertical: true)
            .textSelection(.enabled)
    }
}

/** A line of inline markdown in [look]'s words of [size], code in mono a little smaller, links underlined. */
func styled(_ text: String, look: Look, palette: Colors, size: CGFloat, weight: Font.Weight = .regular) -> AttributedString {
    var out = AttributedString()
    for span in Markdown.inline(text) {
        var run = AttributedString(span.text)
        let bold: Font.Weight = span.bold ? .semibold : weight
        if span.code {
            let codeSize = size * 0.9
            let bolder = bold == .semibold || bold == .bold
            run.font = span.italic ? slanted(bolder ? Fonts.monoBoldName : Fonts.monoName, codeSize) : Fonts.mono(codeSize, bold: bolder)
            run.foregroundColor = palette.orange
            run.backgroundColor = palette.bg2
        } else {
            run.font = span.italic ? slanted(Leading.name(look, bold), size) : look.words(size, weight: bold)
        }
        if span.strike { run.strikethroughStyle = .single }
        if let link = span.link, let url = URL(string: link) {
            run.link = url
            run.foregroundColor = palette.blue
            run.underlineStyle = .single
        }
        out += run
    }
    return out
}
