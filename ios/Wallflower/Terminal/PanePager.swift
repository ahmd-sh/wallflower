import Observation
import SwiftUI
import WallflowerDesign
import Wire

/** Where a page reports sideways drags: how far, as they go, then how far and how fast (pt/s) when let go. */
@MainActor
protocol Swipe: AnyObject {
    func drag(_ dx: CGFloat)
    func end(_ dx: CGFloat, _ velocity: CGFloat)
}

/** Where the pager's panes are, and the swipe that moves them. */
@MainActor
@Observable
final class Pager: Swipe {
    var current: String?
    var offset: CGFloat = 0
    var width: CGFloat = 0
    @ObservationIgnored var haptics: Haptics?
    @ObservationIgnored var neighbours: (before: Pane?, after: Pane?) = (nil, nil)
    @ObservationIgnored var onShow: ((Pane) -> Void)?
    @ObservationIgnored private var from: CGFloat = 0
    @ObservationIgnored private var dragging = false
    @ObservationIgnored private var armed = false // let go now, and it moves on
    @ObservationIgnored private var settling = 0

    func drag(_ dx: CGFloat) {
        if !dragging {
            dragging = true
            settling += 1
            from = offset
            armed = false
        }
        let w = width
        offset = neighbours.after == nil ? (from + dx) * 0.3 : min(max(from + dx, -w), w)
        let reached = neighbours.after != nil && abs(offset) > w * 0.2
        if reached != armed {
            armed = reached
            haptics?.threshold(reached)
        }
    }

    func end(_ dx: CGFloat, _ velocity: CGFloat) {
        dragging = false
        let w = width
        let (before, after) = neighbours
        // A fling goes its way, back to the pane if that's where it points.
        let target: CGFloat
        if after == nil { target = 0 }
        else if velocity < -800 { target = offset <= 0 ? -w : 0 }
        else if velocity > 800 { target = offset >= 0 ? w : 0 }
        else if offset < -w * 0.2 { target = -w }
        else if offset > w * 0.2 { target = w }
        else { target = 0 }
        if target != 0 && !armed { haptics?.threshold(true) } // a fling short of the threshold
        settling += 1
        let mine = settling
        let distance = target - offset
        let v = distance == 0 ? 0 : velocity / distance
        withAnimation(.interpolatingSpring(mass: 1, stiffness: 200, damping: 2 * sqrt(200), initialVelocity: v)) {
            offset = target
        } completion: { [weak self] in
            guard let self, self.settling == mine else { return }
            let to = target < 0 ? after : target > 0 ? before : nil
            guard let to else { return }
            var t = Transaction()
            t.disablesAnimations = true
            withTransaction(t) {
                self.current = to.id
                self.offset = 0
            }
            self.onShow?(to)
        }
    }

    /** The shown pane changing elsewhere (a card, the computer) cuts straight to it. */
    func cut(to id: String) {
        settling += 1
        current = id
        offset = 0
    }
}

/**
 * A window's panes side by side, the shown one on screen. A sideways drag
 * moves them with the finger; let go, they settle on a pane with a spring
 * that carries the fling, and that pane is shown. Panes wrap around. Every
 * pane's terminal is kept current, so the next one comes in already drawn.
 * A drag far enough to move on is felt as it gets there. [onPosition] hears
 * where the panes are, as an index between panes while they move.
 */
struct PanePager<Page: View>: View {
    @Environment(\.haptics) private var haptics
    let panes: [Pane]
    let shown: Pane
    let onShow: (Pane) -> Void
    var onPosition: ((CGFloat) -> Void)?
    @ViewBuilder let page: (Pane, Bool, Swipe) -> Page
    @State private var pager = Pager()

    var body: some View {
        let i = panes.firstIndex { $0.id == (pager.current ?? shown.id) } ?? panes.firstIndex { $0.id == shown.id } ?? 0
        let here = panes.indices.contains(i) ? panes[i] : shown
        let previous = panes.count > 1 ? panes[(i - 1 + panes.count) % panes.count] : nil
        let next = panes.count > 1 ? panes[(i + 1) % panes.count] : nil
        // With two panes the other is both neighbours; it waits on the side being uncovered.
        var slots: [(Pane, Int)] = [(here, 0)]
        if let next {
            if previous?.id == next.id {
                slots.append((next, pager.offset > 0 ? -1 : 1))
            } else if let previous {
                slots.append((previous, -1))
                slots.append((next, 1))
            }
        }
        // What a settled swipe shows goes to the latest window as it is now, not as it was when the neighbours last changed.
        let _ = {
            pager.neighbours = (previous, next)
            pager.onShow = onShow
        }()
        return GeometryReader { geo in
            ZStack {
                ForEach(slots, id: \.0.id) { pane, slot in
                    page(pane, slot == 0, pager)
                        .frame(width: geo.size.width, height: geo.size.height)
                        .offset(x: CGFloat(slot) * geo.size.width + pager.offset)
                }
            }
            .onAppear { pager.width = geo.size.width }
            .onChange(of: geo.size.width) { _, w in pager.width = w }
        }
        .clipped()
        .onAppear {
            pager.current = shown.id
            pager.haptics = haptics
        }
        .onChange(of: shown.id) { _, id in if id != pager.current { pager.cut(to: id) } }
        .onChange(of: pager.offset) { _, _ in report(i) }
        .onChange(of: i) { _, now in report(now) }
    }

    private func report(_ i: Int) {
        let w = pager.width
        onPosition?(w == 0 ? CGFloat(i) : CGFloat(i) - pager.offset / w)
    }
}
