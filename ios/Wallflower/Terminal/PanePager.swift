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

/**
 * Where the pager's panes are, and the swipe that moves them. A lone pane
 * dragged left far enough, with [onNew], gives a little more at once
 * ([snap]), felt, and shows where a new pane would go; let go there, it's
 * asked for ([making]) and the panes go on to its place ([lead]), the new
 * one coming in with them.
 */
@MainActor
@Observable
final class Pager: Swipe {
    var current: String?
    var offset: CGFloat = 0
    var width: CGFloat = 0
    var snap: CGFloat = 0
    var lead: CGFloat = 0
    /** Let go now, and it moves on, or makes a new pane. */
    private(set) var armed = false
    private(set) var making = false
    @ObservationIgnored var haptics: Haptics?
    @ObservationIgnored var neighbours: (before: Pane?, after: Pane?) = (nil, nil)
    @ObservationIgnored var onShow: ((Pane) -> Void)?
    @ObservationIgnored var onNew: (() -> Void)?
    @ObservationIgnored private var from: CGFloat = 0
    @ObservationIgnored private var dragging = false
    @ObservationIgnored private var settling = 0
    @ObservationIgnored private var led = false // the panes are at the new pane's place
    @ObservationIgnored private var arrived: String?

    func drag(_ dx: CGFloat) {
        if making { return }
        if !dragging {
            dragging = true
            settling += 1
            from = offset
            armed = false
        }
        let w = width
        let lone = neighbours.after == nil
        offset = lone ? (from + dx) * 0.3 : min(max(from + dx, -w), w)
        let reached = lone ? onNew != nil && offset < -newPaneReach : abs(offset) > w * 0.2
        if reached != armed {
            armed = reached
            haptics?.threshold(reached)
            if lone { withAnimation(.spring(response: 0.18, dampingFraction: 0.7)) { snap = reached ? newPaneSnap : 0 } }
        }
    }

    func end(_ dx: CGFloat, _ velocity: CGFloat) {
        if making { return }
        dragging = false
        let w = width
        let (before, after) = neighbours
        if after == nil && armed {
            makeNew(velocity)
            return
        }
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

    /** On to the new pane's place, to wait for it; back, if it doesn't come. */
    private func makeNew(_ velocity: CGFloat) {
        armed = false
        making = true
        led = false
        arrived = nil
        onNew?()
        settling += 1
        let mine = settling
        let distance = snap - width - offset // where the new pane, beside this one, is on screen
        withAnimation(.interpolatingSpring(mass: 1, stiffness: 200, damping: 2 * sqrt(200), initialVelocity: distance == 0 ? 0 : velocity / distance)) {
            lead = distance
        } completion: { [weak self] in
            guard let self, self.settling == mine else { return }
            if let id = self.arrived { return self.land(id) }
            self.led = true
            Task { [weak self] in
                try? await Task.sleep(for: newPaneWait)
                guard let self, self.settling == mine, self.making else { return }
                self.making = false
                withAnimation(Motion.spatial) {
                    self.offset = 0
                    self.lead = 0
                    self.snap = 0
                }
            }
        }
    }

    /** The new pane, come in with the panes, takes its place. */
    private func land(_ id: String) {
        withTransaction(still) {
            current = id
            offset = 0
            lead = 0
            snap = 0
            making = false
        }
    }

    /** The shown pane changing elsewhere (a card, the computer) cuts straight to it; the new pane asked for lands once the panes are at its place. */
    func cut(to id: String) {
        if making {
            arrived = id
            if led {
                settling += 1
                land(id)
            }
            return
        }
        settling += 1
        withTransaction(still) {
            current = id
            offset = 0
            snap = 0
            lead = 0
        }
    }

    private var still: Transaction {
        var t = Transaction()
        t.disablesAnimations = true
        return t
    }
}

/** How far a lone pane goes left, given, to make a new one; how much more it gives at once there; how long its place waits for the new pane. */
private let newPaneReach: CGFloat = 48
private let newPaneSnap: CGFloat = 24
private let newPaneWait = Duration.milliseconds(2500)

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
    var onNew: (() -> Void)?
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
        return GeometryReader { geo in
            ZStack {
                ForEach(slots, id: \.0.id) { pane, slot in
                    page(pane, slot == 0, pager)
                        .frame(width: geo.size.width, height: geo.size.height)
                        .offset(x: CGFloat(slot) * geo.size.width + pager.offset)
                }
                if next == nil, onNew != nil {
                    // Kept in the middle of what the pane has uncovered.
                    NewPane(lit: pager.armed || pager.making)
                        .opacity(min(max(-(pager.offset - pager.snap) / newPaneReach, 0), 1))
                        .offset(x: (pager.snap - pager.lead) / 2)
                        .position(x: geo.size.width + pager.offset / 2, y: geo.size.height / 2)
                }
            }
            .offset(x: pager.lead - pager.snap) // apart from the drag's, so they spring while the finger moves
            .onAppear { pager.width = geo.size.width }
            .onChange(of: geo.size.width) { _, w in pager.width = w }
        }
        .clipped()
        .onAppear {
            pager.current = shown.id
            pager.haptics = haptics
        }
        // Where a swipe goes, and what shows it there; [onShow] looks up the window as it is when it settles.
        .onChange(of: [previous?.id, next?.id, shown.id], initial: true) { _, _ in
            pager.neighbours = (previous, next)
            pager.onShow = onShow
            pager.onNew = onNew
        }
        .onChange(of: shown.id) { _, id in if id != pager.current { pager.cut(to: id) } }
        .onChange(of: pager.offset + pager.lead) { _, _ in report(i) }
        .onChange(of: i) { _, now in report(now) }
    }

    private func report(_ i: Int) {
        let w = pager.width
        onPosition?(w == 0 ? CGFloat(i) : CGFloat(i) - (pager.offset + pager.lead) / w)
    }
}

/** Where a new pane would go: a plus and "new pane", reading up the edge it's at. */
private struct NewPane: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    let lit: Bool

    var body: some View {
        let ink = lit ? palette.grey : palette.dim
        HStack(spacing: 8) {
            GlyphIcon(.plus, ink, size: 14)
            Text("new pane").font(look.font(.label)).foregroundColor(ink).lineLimit(1)
        }
        .fixedSize()
        .rotationEffect(.degrees(-90))
        .animation(Motion.effect, value: lit)
        .allowsHitTesting(false)
    }
}
