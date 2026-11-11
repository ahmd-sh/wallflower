import SwiftUI
import WallflowerDesign

/** Switches a pane between its terminal and its mobile view. */
struct ViewToggle: View {
    @Environment(\.palette) private var palette
    let mobile: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                GlyphIcon(mobile ? .terminal : .chat, palette.fg, size: 14)
                    .id(mobile)
                    .transition(.opacity)
            }
            .animation(Motion.effect, value: mobile)
            .padding(.horizontal, 9)
            .padding(.vertical, 4)
            .chrome()
        }
        .buttonStyle(Pressable())
        .padding(.vertical, 8)
    }
}

/**
 * Which of the window's [count] panes is showing: swipe the terminal sideways
 * for the others. The light follows [position] as panes move, stretching
 * from dot to dot.
 */
struct PaneDots: View {
    @Environment(\.palette) private var palette
    let count: Int
    let position: CGFloat
    let action: () -> Void

    var body: some View {
        let dot: CGFloat = 6
        let gap: CGFloat = 5
        let n = CGFloat(max(count, 1))
        Button(action: action) {
            Canvas { ctx, _ in
                let step = dot + gap
                for i in 0..<count {
                    ctx.fill(Path(roundedRect: CGRect(x: CGFloat(i) * step, y: 0, width: dot, height: dot), cornerRadius: dot / 2), with: .color(palette.bg3))
                }
                var pos = position.truncatingRemainder(dividingBy: n)
                if pos < 0 { pos += n }
                let i = min(max(Int(floor(pos)), 0), count - 1)
                let f = pos - floor(pos)
                if i == count - 1 && f > 0 {
                    // Round the end: the light leaves the last dot as it comes back at the first.
                    ctx.fill(Path(roundedRect: CGRect(x: CGFloat(i) * step, y: 0, width: dot, height: dot), cornerRadius: dot / 2), with: .color(palette.fg.opacity(1 - f)))
                    ctx.fill(Path(roundedRect: CGRect(x: 0, y: 0, width: dot, height: dot), cornerRadius: dot / 2), with: .color(palette.fg.opacity(f)))
                } else {
                    let left = CGFloat(i) * step + step * min(max(f * 2 - 1, 0), 1)
                    let right = CGFloat(i) * step + dot + step * min(max(f * 2, 0), 1)
                    ctx.fill(Path(roundedRect: CGRect(x: left, y: 0, width: right - left, height: dot), cornerRadius: dot / 2), with: .color(palette.fg))
                }
            }
            .frame(width: dot * CGFloat(count) + gap * CGFloat(max(count - 1, 0)), height: dot)
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .chrome()
        }
        .buttonStyle(Pressable())
        .padding(8)
        .animation(Motion.spatial, value: position)
    }
}
