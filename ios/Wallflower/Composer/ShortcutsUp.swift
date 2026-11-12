import ScreenReading
import SettingsUI
import SwiftUI
import WallflowerDesign

/** Whether the shortcuts are up. */
@Observable
final class ShortcutsMenu {
    var open = false

    func show() { withAnimation(Motion.bounce) { open = true } }

    func close() { withAnimation(Motion.effect) { open = false } }
}

/**
 * While [menu] is open, the pane's [shortcuts] over the bar, on a panel of
 * their own, then a pill to add one. Three rows of them at most: more go on
 * pages to swipe between. One tapped goes and they shut; a tap anywhere
 * else puts them away.
 */
struct ShortcutsUp: View {
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    let menu: ShortcutsMenu
    let shortcuts: [BarKey]
    let onShortcut: (BarKey) -> Void
    let onAdd: () -> Void
    @State private var width: CGFloat = 0
    @State private var shown: Int? = 0

    private var page: Int { shown ?? 0 }

    var body: some View {
        let pages = paged(widths, width: width, gap: Self.gap, rows: Self.rows)
        let rows = pages.map(\.count).max() ?? 1
        VStack(alignment: .leading, spacing: Self.gap) {
            let height = Self.height * CGFloat(rows) + Self.gap * CGFloat(rows - 1)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 0) {
                    ForEach(pages.indices, id: \.self) { p in
                        VStack(alignment: .leading, spacing: Self.gap) {
                            ForEach(pages[p].indices, id: \.self) { r in
                                HStack(spacing: Self.gap) {
                                    ForEach(pages[p][r], id: \.self) { i in item(i, on: p) }
                                }
                            }
                        }
                        .frame(width: width, height: height, alignment: .topLeading)
                        .id(p)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.paging)
            .scrollPosition(id: $shown)
            .frame(height: height)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
            if pages.count > 1 {
                HStack(spacing: 6) {
                    ForEach(pages.indices, id: \.self) { p in
                        Circle().fill(p == page ? palette.fg : palette.bg3).frame(width: 6, height: 6)
                    }
                }
                .frame(maxWidth: .infinity)
            }
        }
        .opacity(width > 0 ? 1 : 0) // laid out once it's known how wide it is
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(palette.bg1))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(palette.border, lineWidth: 1))
        .padding(.horizontal, -8) // as wide as the composer
        .background(
            // A tap anywhere else puts them away.
            Color.black.opacity(0.001).frame(width: 4000, height: 4000).onTapGesture { menu.close() }
        )
    }

    /** Item [i] on page [p]: a shortcut, or after them the pill to add one. */
    @ViewBuilder
    private func item(_ i: Int, on p: Int) -> some View {
        Group {
            if i < shortcuts.count {
                Button { pick(i) } label: {
                    KeyFace(key: shortcuts[i], color: palette.fg, size: 14)
                        .lineLimit(1)
                        .padding(.horizontal, Self.side)
                        .frame(height: Self.height)
                        .background(Capsule().fill(palette.bg2))
                        .contentShape(Capsule())
                }
            } else {
                Button { pick(i) } label: {
                    HStack(spacing: 6) {
                        GlyphIcon(.plus, palette.grey, size: 14)
                        Text(Self.add).words(.label).foregroundColor(palette.grey).lineLimit(1)
                    }
                    .padding(.horizontal, Self.side)
                    .frame(height: Self.height)
                    .overlay(Capsule().strokeBorder(palette.dim, style: StrokeStyle(lineWidth: 1, dash: [5, 4])))
                    .contentShape(Capsule())
                }
            }
        }
        .buttonStyle(Pressable())
    }

    private func pick(_ i: Int) {
        menu.close()
        if i < shortcuts.count {
            haptics.key()
            onShortcut(shortcuts[i])
        } else {
            onAdd()
        }
    }

    /** How wide each item is: the shortcuts' faces, then the pill to add one (in the terminal's font, which is no narrower than the words'). */
    private var widths: [CGFloat] {
        shortcuts.map { faceWidth(faceOf($0), size: 14) + Self.side * 2 } + [faceWidth([.text(Self.add)], size: 14) + 14 + 6 + Self.side * 2]
    }

    static let rows = 3
    static let height: CGFloat = 40
    static let gap: CGFloat = 6
    private static let side: CGFloat = 16
    private static let add = "add shortcut"
}

/**
 * Items [widths] wide, laid in rows [width] wide, [gap] apart, [rows] rows
 * to a page: each page's rows, as the items' places. One too wide for a row
 * has a row to itself.
 */
func paged(_ widths: [CGFloat], width: CGFloat, gap: CGFloat, rows: Int) -> [[[Int]]] {
    var pages: [[[Int]]] = []
    var page: [[Int]] = []
    var row: [Int] = []
    var used: CGFloat = 0
    for (i, w) in widths.enumerated() {
        if !row.isEmpty && used + gap + w > width {
            page.append(row)
            row = []
            if page.count == rows {
                pages.append(page)
                page = []
            }
        }
        used = row.isEmpty ? w : used + gap + w
        row.append(i)
    }
    if !row.isEmpty { page.append(row) }
    if !page.isEmpty { pages.append(page) }
    return pages
}
