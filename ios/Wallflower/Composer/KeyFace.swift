import ScreenReading
import SettingsUI
import SwiftUI
import WallflowerDesign

/** How a key shows: its keys, with icons for those that have one (tab, ⏎, the arrows, ctrl…), unless the user wrote its label. */
struct KeyFace: View {
    let key: BarKey
    let color: Color
    var size: CGFloat = 13

    var body: some View {
        faceText(faceOf(key), size: size, color: color).lineLimit(1).fixedSize().accessibilityLabel(key.hasOwnLabel ? KeyBar.name(of: key.keys) : key.label)
    }
}

/** [keys] with icons for those that have one, and the rest as the bar names them (esc, alt, ⌘, which keyboards mark differently). */
struct KeysFace: View {
    let keys: [String]
    let color: Color
    var size: CGFloat = 13

    var body: some View {
        faceText(faceOf(keys), size: size, color: color).lineLimit(1).fixedSize().accessibilityLabel(KeyBar.name(of: keys))
    }
}

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
