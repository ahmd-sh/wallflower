import ScreenReading
import SwiftUI
import WallflowerDesign

/** How a key shows: its keys, with icons for those that have one (esc, tab, ⏎, the arrows, ctrl…), unless the user wrote its label. */
struct KeyFace: View {
    let key: BarKey
    let color: Color
    var size: CGFloat = 13

    var body: some View {
        if key.cluster {
            GlyphIcon(.arrows, color, size: size * 1.15)
        } else if key.shortcuts {
            GlyphIcon(.bolt, color, size: size * 1.15)
        } else if key.hasOwnLabel {
            KeysFace(keys: key.keys, color: color, size: size)
        } else {
            Text(key.label).font(Fonts.mono(size)).foregroundColor(color)
        }
    }
}

/** [keys] with icons for those that have one, and the rest as the bar names them. */
struct KeysFace: View {
    let keys: [String]
    let color: Color
    var size: CGFloat = 13

    var body: some View {
        HStack(spacing: size * 0.3) {
            ForEach(Array(keys.enumerated()), id: \.offset) { _, key in
                let (held, rest) = KeyBar.held(key)
                HStack(spacing: 1) {
                    ForEach(Array(held.enumerated()), id: \.offset) { _, m in
                        if let g = keyGlyphs["\(m)-"] { GlyphIcon(g, color, size: size * 1.15) }
                    }
                    if let g = keyGlyphs[rest] {
                        GlyphIcon(g, color, size: size * 1.15)
                    } else {
                        Text(KeyBar.label(of: [rest])).font(Fonts.mono(size)).foregroundColor(color)
                    }
                }
            }
        }
        .accessibilityLabel(KeyBar.name(of: keys))
    }
}

let keyGlyphs: [String: Glyph] = [
    "C-": .ctrl, "M-": .alt, "S-": .shift,
    "Escape": .escape, "Tab": .tab, "BTab": .backTab, "Enter": .enter, "BSpace": .backspace, "Space": .space,
    "Up": .up, "Down": .down, "Left": .left, "Right": .right,
]

/** What a held key is, in words, just above it. */
struct HeldName: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    let name: String

    var body: some View {
        Text(name)
            .font(look.words(look.size(mono: 12, sans: 14)))
            .foregroundColor(palette.fg)
            .lineLimit(1)
            .fixedSize()
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .chrome()
            .offset(y: -44)
            .transition(.opacity.combined(with: .scale(scale: 0.9)))
            .allowsHitTesting(false)
    }
}
