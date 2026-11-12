import ScreenReading
import SwiftUI
import UIKit
import WallflowerDesign

/** A key to pick or add, as its [face]: on when [selected]; held, it says its [name]. */
struct KeyChoice: Identifiable {
    let id: AnyHashable
    let face: Face
    let name: String
    let selected: Bool
    let action: () -> Void

    init(id: some Hashable, face: Face, name: String, selected: Bool, action: @escaping () -> Void) {
        self.id = AnyHashable(id)
        self.face = face
        self.name = name
        self.selected = selected
        self.action = action
    }

    /** [key], by its face and name. */
    init(_ key: BarKey, id: some Hashable, selected: Bool, action: @escaping () -> Void) {
        self.init(id: id, face: faceOf(key), name: KeyBar.name(of: key), selected: selected, action: action)
    }

    /** [keys] sent as one, by their face and name. */
    init(keys: [String], selected: Bool, action: @escaping () -> Void) {
        self.init(id: keys, face: faceOf(keys), name: KeyBar.name(of: keys), selected: selected, action: action)
    }
}

/**
 * Keys to pick or add, flowing onto as many rows as they take. Held, a key
 * says what it sends just above it; with [move], held and dragged, it's
 * carried among the others to where it's let go ([dragging] meanwhile).
 */
struct KeyChoices: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    @Environment(\.haptics) private var haptics
    let choices: [KeyChoice]
    var move: ((_ from: Int, _ to: Int) -> Void)?
    var dragging: Binding<Bool>?
    @GestureState private var hold: Hold?
    @State private var frames: [AnyHashable: CGRect] = [:]
    @State private var space = UUID()

    /** A key held: where on it the finger is, where the finger is now, and whether it's being carried. */
    private struct Hold: Equatable {
        let id: AnyHashable
        var grab: CGSize?
        var at: CGPoint?
        var carried = false
    }

    var body: some View {
        Flow(spacing: 6) {
            ForEach(choices) { c in
                let held = hold?.id == c.id
                Pill(on: c.selected, pressed: held) { ink in faceText(c.face, size: 13, color: ink) }
                    .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(space)) } action: { frames[c.id] = $0 }
                    .opacity(held && hold?.carried == true ? 0 : 1)
                    .contentShape(Capsule())
                    .onTapGesture {
                        if !c.selected { haptics.tick() }
                        c.action()
                    }
                    .gesture(holding(c))
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(c.name)
                    .accessibilityAddTraits(c.selected ? [.isButton, .isSelected] : .isButton)
            }
        }
        .animation(Motion.spatial, value: choices.map(\.id))
        .overlay { lifted }
        .coordinateSpace(.named(space))
        .onChange(of: hold?.id) { _, id in if id != nil { haptics.hold() } }
        .onChange(of: hold?.carried == true) { _, carried in dragging?.wrappedValue = carried }
    }

    /** Held, what the key sends, just above it; carried, the key, under the finger. */
    @ViewBuilder private var lifted: some View {
        if let h = hold, let f = frames[h.id], let c = choices.first(where: { $0.id == h.id }) {
            if h.carried, let at = h.at, let grab = h.grab {
                Pill(on: c.selected, pressed: true) { ink in faceText(c.face, size: 13, color: ink) }
                    .scaleEffect(1.08)
                    .shadow(color: .black.opacity(0.25), radius: 6, y: 3)
                    .position(x: at.x - grab.width + f.width / 2, y: at.y - grab.height + f.height / 2)
                    .allowsHitTesting(false)
            } else {
                Above(anchor: f, gap: 8) {
                    Text(c.name)
                        .font(look.words(look.size(mono: 12, sans: 14)))
                        .foregroundColor(palette.fg)
                        .lineLimit(1)
                        .fixedSize()
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .chrome()
                }
                .allowsHitTesting(false)
            }
        }
    }

    private func holding(_ c: KeyChoice) -> some Gesture {
        LongPressGesture(minimumDuration: 0.4)
            .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .named(space)))
            .updating($hold) { value, state, _ in
                guard case .second(true, let drag) = value else { return }
                var h = state ?? Hold(id: c.id)
                if let drag {
                    if h.grab == nil, let f = frames[c.id] {
                        h.grab = CGSize(width: drag.startLocation.x - f.minX, height: drag.startLocation.y - f.minY)
                    }
                    h.at = drag.location
                    if move != nil, hypot(drag.translation.width, drag.translation.height) > 4 { h.carried = true }
                }
                state = h
            }
            .onChanged { value in
                guard let move, case .second(true, let drag?) = value, hypot(drag.translation.width, drag.translation.height) > 4,
                      let from = choices.firstIndex(where: { $0.id == c.id }) else { return }
                // Only well inside another key: keys differ in width, so a swap can leave the finger on the edge of the one it just passed, which mustn't swap them back.
                if let to = choices.indices.first(where: { i in
                    guard i != from, let f = frames[choices[i].id] else { return false }
                    return f.insetBy(dx: f.width * 0.25, dy: f.height * 0.2).contains(drag.location)
                }) {
                    haptics.tick()
                    move(from, to)
                }
            }
    }
}

/** Its one view centered over [anchor], [gap] above it, kept within the bounds. */
private struct Above: Layout {
    let anchor: CGRect
    let gap: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        proposal.replacingUnspecifiedDimensions()
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let s = subviews.first else { return }
        let size = s.sizeThatFits(.unspecified)
        let x = min(max(anchor.midX - size.width / 2, 0), max(bounds.width - size.width, 0))
        s.place(at: CGPoint(x: bounds.minX + x, y: bounds.minY + anchor.minY - gap - size.height), proposal: .unspecified)
    }
}
