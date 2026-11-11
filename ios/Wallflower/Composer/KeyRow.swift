import ScreenReading
import SwiftUI
import WallflowerDesign

/**
 * The key bar: its keys, scrolling sideways, then a pencil to edit them.
 * Held, each says what it is; the shortcuts key, pressed, brings up the
 * pane's shortcuts ([menu], which the composer shows: ShortcutsUp).
 */
struct KeyRow: View {
    let keys: [BarKey]
    let armed: BarKey?
    let menu: ShortcutsMenu
    let onKey: (BarKey) -> Void
    let onEdit: () -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(Array(keys.enumerated()), id: \.offset) { _, key in
                    if key.cluster {
                        ClusterChip(key: key, onKey: onKey)
                    } else if key.shortcuts {
                        ShortcutsChip(key: key, menu: menu)
                    } else {
                        KeyChip(key: key, armed: armed == key, onKey: onKey)
                    }
                }
                EditKeys(onEdit: onEdit)
            }
            .padding(.trailing, 4)
        }
        .scrollClipDisabled()
        .mask(
            // Fades out toward the send button, where there's more to scroll to.
            HStack(spacing: 0) {
                Rectangle()
                LinearGradient(colors: [.black, .clear], startPoint: .leading, endPoint: .trailing).frame(width: 20)
            }
            .padding(.top, -200) // what floats above isn't masked
        )
        .padding(.trailing, 8)
    }
}

/**
 * Sends on tap; keys like the arrows repeat while held. A sticky key
 * toggles, and feels like it. Held, a key says what it sends, and one that
 * doesn't repeat doesn't send.
 */
private struct KeyChip: View {
    @Environment(\.haptics) private var haptics
    let key: BarKey
    let armed: Bool
    let onKey: (BarKey) -> Void
    @State private var pressed = false
    @State private var held = false
    @State private var hold: Task<Void, Never>?
    @State private var spent = false

    private var repeats: Bool { key.keys.count == 1 && KeyBar.repeating.contains(key.keys[0]) }

    var body: some View {
        Button {
            // Let go: a key that went off while held, or said what it is, has done its part.
            if spent {
                spent = false
                withAnimation(Motion.effect) { held = false }
                return
            }
            fire(false)
        } label: {
            Pill(on: armed, pressed: pressed) { ink in KeyFace(key: key, color: ink) }
                .frame(height: 36)
        }
        .buttonStyle(PressReporting { p in
            pressed = p
            if p {
                spent = false
                start()
            } else {
                hold?.cancel()
                withAnimation(Motion.effect) { held = false }
            }
        })
        .overlay { if held && !repeats { HeldName(name: KeyBar.name(of: key)) } }
        .animation(Motion.effect, value: held)
    }

    private func start() {
        hold = Task {
            try? await Task.sleep(for: .milliseconds(repeats ? 400 : 500))
            guard !Task.isCancelled else { return }
            spent = true
            withAnimation(Motion.effect) { held = true }
            if !repeats {
                haptics.hold()
                return
            }
            fire(false)
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(50))
                guard !Task.isCancelled else { return }
                fire(true)
            }
        }
    }

    private func fire(_ again: Bool) {
        if key.sticky {
            haptics.toggle(!armed)
        } else if again {
            haptics.repeating()
        } else {
            haptics.key()
        }
        onKey(key)
    }
}

/**
 * Pressed, the pane's shortcuts come up ([menu]), to slide onto and let go
 * of, or, let go of here, to tap; pressed again, they go.
 */
private struct ShortcutsChip: View {
    @Environment(\.haptics) private var haptics
    let key: BarKey
    let menu: ShortcutsMenu
    @State private var slid = false
    @State private var openedThisPress = false
    @State private var shutting = false

    var body: some View {
        Pill(on: false, pressed: menu.open) { ink in KeyFace(key: key, color: ink) }
            .frame(height: 36)
            .gesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .global)
                    .onChanged { v in
                        if !openedThisPress {
                            openedThisPress = true
                            if menu.open {
                                shutting = true
                                menu.close()
                            } else {
                                haptics.tick()
                                menu.show()
                            }
                            return
                        }
                        guard menu.open, !shutting else { return }
                        if !slid && hypot(v.translation.width, v.translation.height) > 8 { slid = true }
                        if slid {
                            let now = menu.places.first { $0.value.contains(v.location) }?.key
                            if now != menu.over, now != nil { haptics.tick() }
                            menu.over = now
                        }
                    }
                    .onEnded { _ in
                        openedThisPress = false
                        shutting = false
                        if slid {
                            if let i = menu.over { menu.pick(i) } else { menu.close() }
                        }
                        slid = false
                    }
            )
    }
}

/**
 * Keys gathered in one ([BarKey.cluster]): pressed, they bubble up above
 * it, ⏎ ↑ ⌫ over ← ↓ →, frosted so what's behind them shows. Slid onto
 * and let go of, one goes and they close; tapped, they stay up to press,
 * the arrows repeating while held, till a tap anywhere else, or on this
 * key again.
 */
private struct ClusterChip: View {
    @Environment(\.haptics) private var haptics
    let key: BarKey
    let onKey: (BarKey) -> Void
    @State private var open = false
    @State private var over: Int?
    @State private var places: [Int: CGRect] = [:]
    @State private var slid = false
    @State private var openedThisPress = false
    @State private var shutting = false

    var body: some View {
        Pill(on: false, pressed: open) { ink in KeyFace(key: key, color: ink) }
            .frame(height: 36)
            .overlay(alignment: .bottom) {
                if open {
                    cluster
                        .offset(y: -36 - 16) // clear of the composer's edge
                        .transition(.scale(scale: 0.6, anchor: .bottom).combined(with: .opacity))
                        .zIndex(10)
                }
            }
            .gesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .global)
                    .onChanged { v in
                        if !openedThisPress {
                            openedThisPress = true
                            if open {
                                shutting = true
                                close()
                            } else {
                                haptics.tick()
                                withAnimation(Motion.bounce) { open = true }
                            }
                            return
                        }
                        guard open, !shutting else { return }
                        if !slid && hypot(v.translation.width, v.translation.height) > 8 { slid = true }
                        if slid {
                            let now = places.first { $0.value.contains(v.location) }?.key
                            if now != over, now != nil { haptics.tick() }
                            over = now
                        }
                    }
                    .onEnded { _ in
                        openedThisPress = false
                        shutting = false
                        if slid {
                            if let i = over { send(i) }
                            close()
                        }
                        slid = false
                        over = nil
                    }
            )
            .zIndex(open ? 10 : 0)
    }

    private var cluster: some View {
        VStack(spacing: 4) {
            ForEach(Self.places.indices, id: \.self) { r in
                HStack(spacing: 4) {
                    ForEach(Self.places[r], id: \.self) { i in
                        if i < 0 || i >= key.keys.count {
                            Color.clear.frame(width: Self.place, height: Self.place)
                        } else {
                            Place(name: key.keys[i], lit: over == i) { again in send(i, again: again) }
                                .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { places[i] = $0 }
                        }
                    }
                }
            }
        }
        .background(
            // A tap anywhere else puts them away.
            Color.black.opacity(0.001).frame(width: 4000, height: 4000).onTapGesture { close() }
        )
    }

    private func close() {
        withAnimation(Motion.effect) { open = false }
        over = nil
    }

    private func send(_ i: Int, again: Bool = false) {
        guard i < key.keys.count else { return }
        let k = key.keys[i]
        again ? haptics.repeating() : haptics.key()
        onKey(BarKey(label: KeyBar.label(of: [k]), keys: [k]))
    }

    /** Where a cluster's keys go, row by row, by their place in it: top left, up, top right; left, down, right. */
    private static let places = [[2, 0, 5], [1, 4, 3]]
    static let place: CGFloat = 46
}

/** A key of a cluster: it sends on tap, and the arrows repeat while held. */
private struct Place: View {
    @Environment(\.palette) private var palette
    let name: String
    let lit: Bool
    let onPress: (Bool) -> Void
    @State private var pressed = false
    @State private var hold: Task<Void, Never>?
    @State private var held = false

    var body: some View {
        KeysFace(keys: [name], color: palette.fg, size: 16)
            .frame(width: ClusterChip.place, height: ClusterChip.place)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
            .background(RoundedRectangle(cornerRadius: 12).fill(lit || pressed ? palette.bg3.opacity(0.85) : palette.bg2.opacity(0.35)))
            .environment(\.colorScheme, palette.light ? .light : .dark)
            .scaleEffect(lit || pressed ? 1.06 : 1)
            .animation(Motion.effect, value: lit || pressed)
            .gesture(
                DragGesture(minimumDistance: 0)
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
    }
}

/** The pencil that opens the bar's keys for editing. */
private struct EditKeys: View {
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    let onEdit: () -> Void
    @State private var held = false

    var body: some View {
        GlyphIcon(.edit, palette.grey)
            .padding(10)
            .contentShape(Circle())
            .overlay { if held { HeldName(name: "edit keys") } }
            .onTapGesture { onEdit() }
            .onLongPressGesture(minimumDuration: 0.5, pressing: { p in if !p { withAnimation(Motion.effect) { held = false } } }) {
                haptics.hold()
                withAnimation(Motion.effect) { held = true }
            }
    }
}

/** A button that says when it's pressed and let go, as a press inside a scroll view ends when the view scrolls. */
struct PressReporting: ButtonStyle {
    let onPress: (Bool) -> Void

    func makeBody(configuration: Configuration) -> some View {
        configuration.label.onChange(of: configuration.isPressed) { _, p in onPress(p) }
    }
}
