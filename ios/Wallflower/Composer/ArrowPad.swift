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

/**
 * [pad], while it's open, over the terminal screen, kept within it; its keys
 * go to [onKey]. It grows out of the arrows key, and the × shrinks it back
 * into the key, or where it was moved to, in place. Dragged, it ticks
 * softly as it goes, as a fine dial does.
 */
struct ArrowPadUp: View {
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    let pad: ArrowPad
    let onKey: (BarKey) -> Void
    @State private var dragging = false
    @State private var from: CGPoint = .zero
    @State private var travelled: CGFloat = 0
    /** On screen: open, or still shrinking away. */
    @State private var mounted = false
    /** Full size; it grows from small as it opens and shrinks as it's put away. */
    @State private var grown = false
    /** Where it last was while open: the × forgets where it was moved to, but it shrinks away from there. */
    @State private var left = Spot(to: .zero, home: true)

    var body: some View {
        GeometryReader { geo in
            if mounted {
                let bounds = geo.size
                let size = Self.size
                let home = CGPoint(x: pad.home.midX - size.width / 2, y: pad.home.minY - Self.gap - size.height)
                let spot = pad.open ? Spot(to: clamp(pad.at ?? home, in: bounds), home: pad.at == nil) : left
                // Above the key it grows from the key's middle; moved away, from its own.
                let anchor = spot.home ? UnitPoint(x: (pad.home.midX - spot.to.x) / size.width, y: (pad.home.midY - spot.to.y) / size.height) : .center
                panel(at: spot.to, in: bounds)
                    .frame(width: size.width, height: size.height)
                    .scaleEffect(grown ? 1 : Self.seed, anchor: anchor)
                    .animation(Motion.effect) { $0.opacity(grown ? 1 : 0) }
                    .offset(x: spot.to.x, y: spot.to.y)
                    .animation(dragging ? nil : Motion.spatial, value: spot.to)
                    .allowsHitTesting(pad.open)
                    .onChange(of: spot, initial: true) { _, now in if pad.open { left = now } }
            }
        }
        .onChange(of: pad.open, initial: true) { _, open in
            if open {
                mounted = true
                // A turn later, so it's there small to grow from.
                Task { @MainActor in withAnimation(Motion.bounce) { grown = pad.open } }
            } else {
                withAnimation(Motion.effect) { grown = false } completion: { if !pad.open { mounted = false } }
            }
        }
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
        .padding([.horizontal, .bottom], Self.inset)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(palette.bg1.opacity(0.55)))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(palette.border, lineWidth: 1))
        .environment(\.colorScheme, palette.light ? .light : .dark)
    }

    /** Its bar dragged from [at]: it goes with the finger, within [bounds], a soft detent every so far it moves. */
    private func drag(at: CGPoint, in bounds: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 2, coordinateSpace: .named(ArrowPad.space))
            .onChanged { v in
                if !dragging {
                    dragging = true
                    from = at
                    travelled = 0
                    haptics.tick()
                    haptics.ready()
                }
                let was = pad.at ?? at
                let now = clamp(CGPoint(x: from.x + v.translation.width, y: from.y + v.translation.height), in: bounds)
                pad.at = now
                travelled += hypot(now.x - was.x, now.y - was.y) // as far as it moved: held at an edge, it's still
                if travelled >= Self.detent {
                    travelled.formTruncatingRemainder(dividingBy: Self.detent)
                    haptics.detent()
                }
            }
            .onEnded { _ in dragging = false }
    }

    private func clamp(_ p: CGPoint, in bounds: CGSize) -> CGPoint {
        CGPoint(x: min(max(p.x, 0), max(bounds.width - Self.size.width, 0)), y: min(max(p.y, 0), max(bounds.height - Self.size.height, 0)))
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
    private static let inset: CGFloat = 6
    /** Its size, known before it's drawn, so it starts where it should. */
    static let size = CGSize(width: place * 3 + keyGap * 2 + inset * 2, height: bar + place * 2 + keyGap + inset)
    /** How small it is as it starts to grow: about the key's size. */
    private static let seed: CGFloat = 0.25
    /** How far it moves between detents. */
    private static let detent: CGFloat = 14
}

/** Where the pad is, and whether that's above its key. */
private struct Spot: Equatable {
    var to: CGPoint
    var home: Bool
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
                        hold = holding(repeats: true, onHeld: { held = true }, fire: onPress)
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
