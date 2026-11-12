import ScreenReading
import SwiftUI
import WallflowerDesign

/**
 * The key bar: its keys, scrolling sideways, then a pencil to edit them.
 * Held, each says what it is; the shortcuts key, tapped, brings up the
 * pane's shortcuts ([menu], which the composer shows: ShortcutsUp), and the
 * arrows key their [pad] (which the terminal screen shows: ArrowPadUp).
 */
struct KeyRow: View {
    let keys: [BarKey]
    let armed: BarKey?
    let menu: ShortcutsMenu
    let pad: ArrowPad
    let onKey: (BarKey) -> Void
    let onEdit: () -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(Array(keys.enumerated()), id: \.offset) { _, key in
                    if key.cluster {
                        ClusterChip(key: key, pad: pad)
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
        .onDisappear { hold?.cancel() } // gone mid-press (the bar changed), it stops
    }

    private func start() {
        hold?.cancel()
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

/** Tapped, the pane's shortcuts come up ([menu]); tapped again, they go. */
private struct ShortcutsChip: View {
    @Environment(\.haptics) private var haptics
    let key: BarKey
    let menu: ShortcutsMenu

    var body: some View {
        Button {
            if menu.open {
                menu.close()
            } else {
                haptics.tick()
                menu.show()
            }
        } label: {
            Pill(on: false, pressed: menu.open) { ink in KeyFace(key: key, color: ink) }
                .frame(height: 36)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(KeyBar.name(of: key))
    }
}

/** Keys gathered in one ([BarKey.cluster]): tapped, they come up on their [pad], above it. */
private struct ClusterChip: View {
    @Environment(\.haptics) private var haptics
    let key: BarKey
    let pad: ArrowPad

    var body: some View {
        Button {
            haptics.tick()
            pad.press(key)
        } label: {
            Pill(on: false, pressed: pad.open) { ink in KeyFace(key: key, color: ink) }
                .frame(height: 36)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(KeyBar.name(of: key))
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(ArrowPad.space)) } action: { pad.home = $0 }
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
