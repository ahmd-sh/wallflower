import SwiftUI

// The pieces wallflower's screens are made of.

/** A rounded chip of text, as keys and choices are: lit orange while [on], a step lighter while [pressed]. */
public struct Pill<Label: View>: View {
    @Environment(\.palette) private var palette
    let on: Bool
    let pressed: Bool
    let label: (Color) -> Label

    public init(on: Bool, pressed: Bool = false, @ViewBuilder label: @escaping (Color) -> Label) {
        self.on = on
        self.pressed = pressed
        self.label = label
    }

    public var body: some View {
        let ink = on ? palette.bg0 : palette.fg
        label(ink)
            .lineLimit(1)
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .background(Capsule().fill(on ? palette.orange : pressed ? palette.bg3 : palette.bg2))
            .animation(Motion.effect, value: on)
            .animation(Motion.effect, value: pressed)
    }
}

public extension Pill where Label == Text {
    init(_ text: String, on: Bool, pressed: Bool = false) {
        self.init(on: on, pressed: pressed) { ink in Text(text).font(Fonts.mono(13)).foregroundColor(ink) }
    }
}

/** A line between things in a list. */
public struct Rule: View {
    @Environment(\.palette) private var palette
    var color: Color?

    public init(color: Color? = nil) { self.color = color }

    public var body: some View {
        Rectangle().fill(color ?? palette.bg2).frame(height: 1)
    }
}

/** A small control floating over a pane, as the mobile view toggle and the pane dots are. */
public struct Chrome: ViewModifier {
    @Environment(\.palette) private var palette

    public init() {}

    public func body(content: Content) -> some View {
        content
            .background(RoundedRectangle(cornerRadius: 8).fill(palette.bg1))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(palette.border, lineWidth: 1))
    }
}

public extension View {
    func chrome() -> some View { modifier(Chrome()) }
}

/** A section's heading, in orange. */
public struct Section: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    let label: String

    public init(_ label: String) { self.label = label }

    public var body: some View {
        Text(label)
            .font(look.words(look.size(mono: 12, sans: 15), weight: look == .grotesque ? .semibold : .bold))
            .foregroundColor(palette.orange)
            .padding(.top, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/** A paragraph explaining something, in grey. */
public struct Body: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    let text: String

    public init(_ text: String) { self.text = text }

    public var body: some View {
        Text(text)
            .words(.label)
            .foregroundColor(palette.grey)
            .lineSpacing(look == .grotesque ? 4 : 1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/** A labelled line to type in, showing [hint] while it's empty. */
public struct Field: View {
    @Environment(\.palette) private var palette
    let label: String
    @Binding var value: String
    var hint: String?
    var keyboard: UIKeyboardType

    public init(_ label: String, value: Binding<String>, hint: String? = nil, keyboard: UIKeyboardType = .default) {
        self.label = label
        _value = value
        self.hint = hint
        self.keyboard = keyboard
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).words(.caption).foregroundColor(palette.dim)
            TextField("", text: $value, prompt: hint.map { Text($0).foregroundColor(palette.dim) })
                .font(Fonts.mono(15))
                .foregroundColor(palette.fg)
                .tint(palette.orange)
                .keyboardType(keyboard)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 10).fill(palette.bg1))
        }
    }
}

/** A setting that's on or off: a checkbox in the grotesque look, [x] in the terminal's. */
public struct Toggle: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    @Environment(\.haptics) private var haptics
    let label: String
    let on: Bool
    let onChange: (Bool) -> Void

    public init(_ label: String, on: Bool, onChange: @escaping (Bool) -> Void) {
        self.label = label
        self.on = on
        self.onChange = onChange
    }

    public var body: some View {
        Button {
            haptics.toggle(!on)
            onChange(!on)
        } label: {
            if look == .grotesque {
                HStack(spacing: 14) {
                    Checkbox(on: on)
                    Text(label).font(Fonts.grotesque(15)).foregroundColor(palette.fg).multilineTextAlignment(.leading)
                    Spacer(minLength: 0)
                }
                .padding(.vertical, 6)
                .contentShape(Rectangle())
            } else {
                HStack(spacing: 0) {
                    Text(on ? "[x] " : "[ ] ").font(Fonts.mono(13)).foregroundColor(palette.orange)
                    Text(label).font(Fonts.mono(12)).foregroundColor(palette.fg).multilineTextAlignment(.leading)
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
        }
        .buttonStyle(.plain)
    }
}

/** A box that's ticked or not. */
public struct Checkbox: View {
    @Environment(\.palette) private var palette
    let on: Bool

    public init(on: Bool) { self.on = on }

    public var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 4)
                .stroke(on ? palette.orange : palette.grey, lineWidth: 2)
                .background(RoundedRectangle(cornerRadius: 4).fill(on ? palette.orange : .clear))
            if on { GlyphIcon(.check, palette.bg0, size: 12) }
        }
        .frame(width: 18, height: 18)
        .animation(Motion.effect, value: on)
    }
}

/** A button of words: green for the main thing to do. */
public struct WordButton: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    let label: String
    let primary: Bool
    let action: () -> Void

    public init(_ label: String, primary: Bool = false, action: @escaping () -> Void) {
        self.label = label
        self.primary = primary
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            Text(label)
                .font(look.words(look.size(mono: 13, sans: 14), weight: primary ? (look == .grotesque ? .semibold : .bold) : .regular))
                .foregroundColor(primary ? palette.bg0 : palette.fg)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(RoundedRectangle(cornerRadius: 8).fill(primary ? palette.green : palette.bg3))
        }
        .buttonStyle(Pressable())
    }
}

/** A command to run, or a value to copy, in a box of its own. */
public struct Code: View {
    @Environment(\.palette) private var palette
    let text: String

    public init(_ text: String) { self.text = text }

    public var body: some View {
        Text(text)
            .font(Fonts.mono(11))
            .foregroundColor(palette.fg)
            .textSelection(.enabled)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 8).fill(palette.bg1))
    }
}

/** A choice among a few: on when [selected]. */
public struct Chip: View {
    @Environment(\.haptics) private var haptics
    let label: String
    let selected: Bool
    var font: Font?
    let action: () -> Void

    public init(_ label: String, selected: Bool, font: Font? = nil, action: @escaping () -> Void) {
        self.label = label
        self.selected = selected
        self.font = font
        self.action = action
    }

    public var body: some View {
        Button {
            if !selected { haptics.tick() }
            action()
        } label: {
            Pill(on: selected) { ink in Text(label).font(font ?? Fonts.mono(13)).foregroundColor(ink) }
        }
        .buttonStyle(.plain)
    }
}

/** Pressed, a little smaller and dimmer: how wallflower's own buttons answer a touch. */
public struct Pressable: ButtonStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(Motion.effect, value: configuration.isPressed)
    }
}

/** A page's top: back, its title, and what else it offers. */
public struct PageHeader<Trailing: View>: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    @Environment(\.haptics) private var haptics
    let title: String
    let onBack: (() -> Void)?
    let trailing: Trailing

    public init(_ title: String, onBack: (() -> Void)?, @ViewBuilder trailing: () -> Trailing = { EmptyView() }) {
        self.title = title
        self.onBack = onBack
        self.trailing = trailing()
    }

    public var body: some View {
        HStack(spacing: 12) {
            if let onBack {
                Button {
                    haptics.tick()
                    onBack()
                } label: {
                    GlyphIcon(.left, palette.fg, size: 16).padding(10).contentShape(Rectangle())
                }
                .buttonStyle(Pressable())
            }
            Text(title).font(look.font(.title, weight: look == .grotesque ? .semibold : .bold)).foregroundColor(palette.fg)
            Spacer()
            trailing
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
    }
}
