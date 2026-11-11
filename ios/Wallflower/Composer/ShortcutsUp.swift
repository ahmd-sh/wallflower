import ScreenReading
import SwiftUI
import WallflowerDesign

/** Whether the shortcuts are up, and where each is, so the finger that opened them can slide onto one ([over]) and let go to [pick] it. */
@Observable
final class ShortcutsMenu {
    var open = false
    var over: Int?
    var places: [Int: CGRect] = [:]
    var pick: (Int) -> Void = { _ in }

    func show() {
        places = [:]
        withAnimation(Motion.bounce) { open = true }
    }

    func close() {
        withAnimation(Motion.effect) { open = false }
        over = nil
        places = [:]
    }
}

/**
 * While [menu] is open, the pane's [shortcuts] over the bar, on a panel of
 * their own, then a pill to add one. Three rows of them at most: more go on
 * pages to swipe between. Slid onto from the key that opened them and let
 * go of, one goes and they shut; let go of on the key, they stay up for a
 * tap on one, till a tap anywhere else. What the one slid onto sends shows
 * above them.
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
            if let i = menu.over, i < shortcuts.count {
                Text(KeyBar.name(of: shortcuts[i]))
                    .words(.label)
                    .foregroundColor(palette.grey)
                    .lineLimit(1)
                    .padding(.horizontal, 6)
            }
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
            .onChange(of: shown) { menu.places = [:] }
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
        .onAppear { menu.pick = pick }
        .onChange(of: shortcuts) { menu.pick = pick }
    }

    /** Item [i] on page [p]: a shortcut, or after them the pill to add one. */
    @ViewBuilder
    private func item(_ i: Int, on p: Int) -> some View {
        let lit = menu.over == i
        Group {
            if i < shortcuts.count {
                Button { pick(i) } label: {
                    KeyFace(key: shortcuts[i], color: palette.fg, size: 14)
                        .lineLimit(1)
                        .padding(.horizontal, Self.side)
                        .frame(height: Self.height)
                        .background(Capsule().fill(lit ? palette.bg3 : palette.bg2))
                        .contentShape(Capsule())
                }
            } else {
                Button { pick(i) } label: {
                    HStack(spacing: 6) {
                        GlyphIcon(.plus, lit ? palette.fg : palette.grey, size: 14)
                        Text(Self.add).words(.label).foregroundColor(lit ? palette.fg : palette.grey).lineLimit(1)
                    }
                    .padding(.horizontal, Self.side)
                    .frame(height: Self.height)
                    .background(Capsule().fill(lit ? palette.bg2 : .clear))
                    .overlay(Capsule().strokeBorder(palette.dim, style: StrokeStyle(lineWidth: 1, dash: [5, 4])))
                    .contentShape(Capsule())
                }
            }
        }
        .buttonStyle(Pressable())
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { if menu.open && p == page { menu.places[i] = $0 } } // not while it goes
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
        let font = Fonts.uiMono(14)
        let text = { (s: String) in (s as NSString).size(withAttributes: [.font: font]).width.rounded(.up) }
        return shortcuts.map { faceWidth($0, size: 14, text: text) + Self.side * 2 } + [text(Self.add) + 14 + 6 + Self.side * 2]
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

/** How wide [key]'s face is at [size], as KeyFace draws it, [text] measuring its words. */
private func faceWidth(_ key: BarKey, size: CGFloat, text: (String) -> CGFloat) -> CGFloat {
    let glyph = size * 1.15
    if key.cluster || key.shortcuts { return glyph }
    if !key.hasOwnLabel { return text(key.label) }
    var total: CGFloat = 0
    for (n, k) in key.keys.enumerated() {
        if n > 0 { total += size * 0.3 }
        let (held, rest) = KeyBar.held(k)
        let parts = held.map { keyGlyphs["\($0)-"] != nil ? glyph : text(KeyBar.modifierLabel($0)) } + [keyGlyphs[rest] != nil ? glyph : text(KeyBar.label(of: [rest]))]
        total += parts.reduce(0, +) + CGFloat(parts.count - 1)
    }
    return total
}
