import Observation
import ScreenReading
import SwiftUI
import WallflowerDesign

/**
 * The arrows key's pad ([BarKey.cluster]): tapped, the key brings it up
 * just above itself, ⏎ ↑ ⌫ over ← ↓ →, frosted so what's behind shows. It
 * stays up while the pane's typed into, till its × puts it away. Dragged by
 * the bar along its top, it stays where it's let go; the key, tapped while
 * it's away, brings it back, and tapped again puts it away.
 */
@MainActor
@Observable
final class ArrowPad {
    private(set) var open = false
    /** Its keys: the arrows key's. */
    private(set) var key = KeyBar.arrows
    /** Where the arrows key is, on the terminal screen: the pad comes up above it. */
    var home: CGRect = .zero
    /** Where it was dragged to, its top left on the terminal screen; nil while it's above the key. */
    var at: CGPoint?

    /** The arrows key tapped. */
    func press(_ key: BarKey) {
        self.key = key
        if !open {
            open = true
        } else if at != nil {
            at = nil
        } else {
            open = false
        }
    }

    func close() {
        open = false
        at = nil
    }

    /** The space the pad and its key are placed in: the terminal screen's. */
    static let space = "terminal screen"
}

/** [pad], while it's open, over the terminal screen, kept within it; its keys go to [onKey]. */
struct ArrowPadUp: View {
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    let pad: ArrowPad
    let onKey: (BarKey) -> Void
    @State private var size: CGSize = .zero
    @State private var dragging = false
    @State private var from: CGPoint = .zero

    var body: some View {
        GeometryReader { geo in
            if pad.open {
                let bounds = geo.size
                let home = CGPoint(x: pad.home.midX - size.width / 2, y: pad.home.minY - Self.gap - size.height)
                let to = clamp(pad.at ?? home, in: bounds)
                panel(at: to, in: bounds)
                    .onGeometryChange(for: CGSize.self) { $0.size } action: { size = $0 }
                    .opacity(size == .zero ? 0 : 1)
                    .offset(x: to.x, y: to.y)
                    .animation(dragging || size == .zero ? nil : Motion.spatial, value: to)
                    .transition(.scale(scale: 0.6, anchor: .bottom).combined(with: .opacity))
            }
        }
        .animation(Motion.bounce, value: pad.open)
    }

    private func panel(at: CGPoint, in bounds: CGSize) -> some View {
        VStack(spacing: 0) {
            // The bar it's dragged by, and its ×.
            HStack(spacing: 0) {
                Color.clear
                    .frame(width: Self.place * 3 + Self.keyGap * 2 - Self.bar, height: Self.bar)
                    .overlay(alignment: .center) {
                        Capsule().fill(palette.grey.opacity(0.6)).frame(width: 28, height: 4).offset(x: Self.bar / 2)
                    }
                    .contentShape(Rectangle())
                    .gesture(drag(at: at, in: bounds))
                Button {
                    haptics.tick()
                    pad.close()
                } label: {
                    GlyphIcon(.close, palette.grey, size: 10).frame(width: Self.bar, height: Self.bar).contentShape(Circle())
                }
                .buttonStyle(Pressable())
                .accessibilityLabel("put the arrows away")
            }
            VStack(spacing: Self.keyGap) {
                ForEach(Self.places.indices, id: \.self) { r in
                    HStack(spacing: Self.keyGap) {
                        ForEach(Self.places[r], id: \.self) { i in
                            if i < pad.key.keys.count {
                                Place(name: pad.key.keys[i]) { again in send(i, again: again) }
                            } else {
                                Color.clear.frame(width: Self.place, height: Self.place)
                            }
                        }
                    }
                }
            }
        }
        .padding([.horizontal, .bottom], 6)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(palette.bg1.opacity(0.55)))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(palette.border, lineWidth: 1))
        .environment(\.colorScheme, palette.light ? .light : .dark)
    }

    /** Its bar dragged from [at]: it goes with the finger, within [bounds]. */
    private func drag(at: CGPoint, in bounds: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 2, coordinateSpace: .named(ArrowPad.space))
            .onChanged { v in
                if !dragging {
                    dragging = true
                    from = at
                    haptics.tick()
                }
                pad.at = clamp(CGPoint(x: from.x + v.translation.width, y: from.y + v.translation.height), in: bounds)
            }
            .onEnded { _ in dragging = false }
    }

    private func clamp(_ p: CGPoint, in bounds: CGSize) -> CGPoint {
        CGPoint(x: min(max(p.x, 0), max(bounds.width - size.width, 0)), y: min(max(p.y, 0), max(bounds.height - size.height, 0)))
    }

    private func send(_ i: Int, again: Bool) {
        guard i < pad.key.keys.count else { return }
        let k = pad.key.keys[i]
        again ? haptics.repeating() : haptics.key()
        onKey(BarKey(label: KeyBar.label(of: [k]), keys: [k]))
    }

    /** Where the pad's keys go, row by row, by their place in it: top left, up, top right; left, down, right. */
    private static let places = [[2, 0, 5], [1, 4, 3]]
    static let place: CGFloat = 46
    private static let keyGap: CGFloat = 4
    private static let bar: CGFloat = 22
    private static let gap: CGFloat = 16 // above the key, clear of the composer's edge
}

/** A key of the pad: it sends on tap, and the arrows repeat while held. */
private struct Place: View {
    @Environment(\.palette) private var palette
    let name: String
    let onPress: (Bool) -> Void
    @State private var pressed = false
    @State private var hold: Task<Void, Never>?
    @State private var held = false
    @GestureState private var touching = false

    var body: some View {
        KeysFace(keys: [name], color: palette.fg, size: 16)
            .frame(width: ArrowPadUp.place, height: ArrowPadUp.place)
            .background(RoundedRectangle(cornerRadius: 12).fill(pressed ? palette.bg3.opacity(0.85) : palette.bg2.opacity(0.5)))
            .scaleEffect(pressed ? 1.06 : 1)
            .animation(Motion.effect, value: pressed)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .updating($touching) { _, t, _ in t = true }
                    .onChanged { _ in
                        guard !pressed else { return }
                        pressed = true
                        held = false
                        guard KeyBar.repeating.contains(name) else { return }
                        hold = Task {
                            try? await Task.sleep(for: .milliseconds(400))
                            guard !Task.isCancelled else { return }
                            held = true
                            onPress(false)
                            while !Task.isCancelled {
                                try? await Task.sleep(for: .milliseconds(50))
                                guard !Task.isCancelled else { return }
                                onPress(true)
                            }
                        }
                    }
                    .onEnded { _ in
                        hold?.cancel()
                        if !held { onPress(false) }
                        pressed = false
                    }
            )
            // A press cut short rather than let go (the system took the touch), or the pad gone mid-press, stops too.
            .onChange(of: touching) { _, now in
                if !now {
                    hold?.cancel()
                    pressed = false
                }
            }
            .onDisappear { hold?.cancel() }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(KeyBar.name(of: [name]))
            .accessibilityAddTraits(.isButton)
    }
}
