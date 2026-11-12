import ScreenReading
import SwiftUI
import WallflowerDesign

/** How full the program's context is, and how to compact it. */
struct ContextSize {
    let tokens: Int
    let onCompact: (() -> Void)?
}

/** What goes beside the last reply's copy, or on its own: the mode the program's in, to pick another of those it goes round, and how full its context is. */
struct Footer {
    let mode: Mode?
    let modes: [Mode]
    let onMode: (String) -> Void
    let context: ContextSize?
}

/** How much of the program's context the conversation takes; tapped or held, it offers to compact it. */
private struct ContextButton: View {
    let context: ContextSize
    @Environment(\.palette) private var palette

    var body: some View {
        let label = Text("context \(count(context.tokens))")
            .words(.caption)
            .foregroundColor(palette.purple)
            .padding(.vertical, 4)
            .padding(.horizontal, 2)
        if let compact = context.onCompact {
            Menu {
                Button("compact", action: compact)
            } label: {
                label.contentShape(Rectangle())
            }
            .menuStyle(.button)
            .buttonStyle(.plain)
        } else {
            label
        }
    }
}

struct FooterView: View {
    let footer: Footer

    var body: some View {
        HStack(spacing: 10) {
            if let mode = footer.mode { ModeButton(mode: mode, modes: footer.modes, onMode: footer.onMode) }
            if let context = footer.context { ContextButton(context: context) }
        }
    }
}

/**
 * The mode the program's in, colored as it shows it; tapped or held, its
 * modes, the one it's in checked (iOS's menus mark it so), to step to another.
 */
private struct ModeButton: View {
    let mode: Mode
    let modes: [Mode]
    let onMode: (String) -> Void
    @Environment(\.palette) private var palette

    var body: some View {
        Menu {
            ForEach(modes, id: \.name) { m in
                Button {
                    if m.name != mode.name { onMode(m.name) }
                } label: {
                    if m.name == mode.name { Label(said(m), systemImage: "checkmark") } else { Text(said(m)) }
                }
            }
        } label: {
            Text(said(mode))
                .words(.caption)
                .foregroundColor(color(mode.hue, palette))
                .lineLimit(1)
                .padding(.vertical, 4)
                .padding(.horizontal, 2)
                .contentShape(Rectangle())
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
    }
}

/** A mode by its name, in its color: its mark (⏵⏵, ⏸) is an emoji in some fonts and missing in others. */
private func said(_ mode: Mode) -> String {
    mode.name
}

/** A hue a program shows something in, in the theme's colors. */
private func color(_ hue: Hue, _ palette: Colors) -> Color {
    switch hue {
    case .grey: palette.grey
    case .red: palette.red
    case .orange: palette.orange
    case .yellow: palette.yellow
    case .green: palette.green
    case .aqua: palette.aqua
    case .blue: palette.blue
    case .purple: palette.purple
    }
}

private func count(_ n: Int) -> String {
    if n < 1_000 { return "\(n)" }
    if n < 1_000_000 { return "\(n / 1_000)k" }
    return String(format: "%.1fM", Double(n) / 1e6)
}
