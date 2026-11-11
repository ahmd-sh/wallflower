import ScreenReading
import SessionCore
import SwiftUI
import WallflowerDesign
import Wire

/** A profile of the key bar, by where it sits. */
public enum ProfileRef: Hashable, Sendable {
    case `default`
    case at(Int)
}

extension KeyBar {
    /** The profile the bar of a pane running [command] (the gateway's [program]) comes from. */
    public func refFor(command: String?, program: String? = nil) -> ProfileRef {
        profiles.firstIndex { $0.matches(command, program: program) }.map(ProfileRef.at) ?? .default
    }

    func profile(at ref: ProfileRef) -> KeyProfile? {
        switch ref {
        case .default: return self.default
        case .at(let i): return profiles.indices.contains(i) ? profiles[i] : nil
        }
    }
}

/**
 * Settings: the Mac, how wallflower looks, and the key bars each a page of
 * their own, said in a line here; what's on or off is here as it is, and
 * what the phone knows of the gateway (its plugins, its versions) last.
 */
public struct SettingsScreen: View {
    let host: Host?
    let store: KeyBarStore
    let settings: Preferences
    let program: String?
    let programId: String?
    let gateway: String?
    let onBack: () -> Void
    let onEditHost: () -> Void
    let onLook: () -> Void
    let onKeyBars: () -> Void

    public init(host: Host?, store: KeyBarStore, settings: Preferences, program: String?, programId: String?, gateway: String?, onBack: @escaping () -> Void, onEditHost: @escaping () -> Void, onLook: @escaping () -> Void, onKeyBars: @escaping () -> Void) {
        self.host = host
        self.store = store
        self.settings = settings
        self.program = program
        self.programId = programId
        self.gateway = gateway
        self.onBack = onBack
        self.onEditHost = onEditHost
        self.onLook = onLook
        self.onKeyBars = onKeyBars
    }

    public var body: some View {
        let bar = store.bar
        Page("settings", onBack: onBack) {
            if let host {
                PageRow(host.called, host.dev ? "development transport" : "\(host.user)@\(host.address):\(host.port)", action: onEditHost)
            }
            PageRow("look", "\(settings.scheme.label) · \(settings.look == .terminal ? "terminal" : "bricolage") font", action: onLook)
            PageRow("key bars", "\(bar.profiles.count + 1) · this pane's: \(bar.profile(at: bar.refFor(command: program, program: programId))?.name ?? bar.default.name)", action: onKeyBars)

            Section("as you go")
            Toggle("mobile views, where a program has one", on: settings.mobileView) { settings.mobileView = $0 }
            Paragraph("Claude Code shows as its conversation, the terminal running underneath; the button by a pane's dots switches.")
            Toggle("haptics for swipes, switches and sends", on: settings.haptics) { settings.haptics = $0 }
            Toggle("ask before closing a pane", on: settings.askClosingPane) { settings.askClosingPane = $0 }
            Toggle("ask before closing a tab (or its last pane)", on: settings.askClosingWindow) { settings.askClosingWindow = $0 }
            Toggle("in the Dynamic Island and on the Lock Screen while Claude works or needs you (a Live Activity)", on: settings.liveActivity) { settings.liveActivity = $0 }
            Toggle("stay connected while wallflower's in the background", on: settings.keepConnected) { settings.keepConnected = $0 }
            Paragraph("So the Live Activity and notifications keep up; it uses a silent audio session and some battery.")

            Section("plugins")
            let builtin = Programs.builtin.map { $0.id }.joined(separator: ", ")
            let yours = Programs.suppliedIds.sorted().joined(separator: ", ")
            Paragraph("Built in: \(builtin). " + (yours.isEmpty ? "None of yours yet." : "Yours: \(yours)."))
            Paragraph("Yours go on the Mac, a JSON file each in ~/.config/wallflower/plugins (docs/plugins.md in wallflower's source).")

            Section("about")
            Paragraph((["wallflower \(appVersion) · protocol \(Wire.VERSION)"] + [gateway].compactMap { $0 }).joined(separator: "\n"))
        }
    }

    private var appVersion: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?" }
}

/** The theme and the font. */
public struct LookScreen: View {
    let settings: Preferences
    let onBack: () -> Void

    public init(settings: Preferences, onBack: @escaping () -> Void) {
        self.settings = settings
        self.onBack = onBack
    }

    public var body: some View {
        Page("look", onBack: onBack) {
            Section("theme")
            ThemePicker(picked: settings.scheme) { settings.scheme = $0 }
            Paragraph("The terminal takes the theme's colors too. Matching the phone, it's everforest while the phone's dark and catppuccin latte while it's light.")
            Section("font")
            HStack(spacing: 6) {
                Chip("terminal", selected: settings.look == .terminal) { settings.look = .terminal }
                Chip("bricolage", selected: settings.look == .grotesque, font: Fonts.grotesque(13)) { settings.look = .grotesque }
            }
            Paragraph("For wallflower's own words. The terminal, what you type for it, keys and values stay in the terminal font.")
        }
    }
}

/** The themes, two to a row, each in its own colors. */
private struct ThemePicker: View {
    @Environment(\.colorScheme) private var colorScheme
    let picked: Scheme
    let onPick: (Scheme) -> Void

    var body: some View {
        let rows = stride(from: 0, to: Scheme.allCases.count, by: 2).map { Array(Scheme.allCases[$0 ..< min($0 + 2, Scheme.allCases.count)]) }
        VStack(spacing: 8) {
            ForEach(rows, id: \.self) { row in
                HStack(spacing: 8) {
                    ForEach(row, id: \.self) { s in
                        ThemeChoice(name: s.label, colors: s.colors(dark: colorScheme == .dark), selected: s == picked) { onPick(s) }
                    }
                    if row.count == 1 { Color.clear.frame(maxWidth: .infinity, maxHeight: 0) }
                }
            }
        }
    }
}

/** A theme as a swatch: its page, its words and its accents. */
private struct ThemeChoice: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    @Environment(\.haptics) private var haptics
    let name: String
    let colors: Colors
    let selected: Bool
    let onPick: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 12)
        Button {
            if !selected { haptics.tick() }
            onPick()
        } label: {
            VStack(alignment: .leading, spacing: 8) {
                Text(name)
                    .font(look.words(look.size(mono: 12, sans: 14)))
                    .foregroundColor(colors.fg)
                    .lineLimit(1)
                HStack(spacing: 5) {
                    ForEach(Array([colors.red, colors.orange, colors.yellow, colors.green, colors.blue, colors.purple].enumerated()), id: \.offset) { _, c in
                        Circle().fill(c).frame(width: 10, height: 10)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(shape.fill(colors.bg0))
            .overlay(shape.strokeBorder(selected ? palette.orange : palette.border, lineWidth: selected ? 2 : 1))
            .contentShape(shape)
        }
        .buttonStyle(Pressable())
    }
}

extension Scheme {
    /** Its name, shortly. */
    var label: String {
        switch self {
        case .everforestDarkHard: return "everforest"
        case .gruvboxDark: return "gruvbox"
        case .catppuccinMocha: return "catppuccin mocha"
        case .catppuccinLatte: return "catppuccin latte"
        case .tokyoNight: return "tokyo night"
        case .nord: return "nord"
        case .system: return "match the phone"
        }
    }
}

/** The key bar's profiles, by the programs they're for, to open and change. */
public struct KeyBarsScreen: View {
    let store: KeyBarStore
    let program: String?
    let programId: String?
    let onBack: () -> Void
    let onOpen: (ProfileRef) -> Void
    @State private var confirm = false

    public init(store: KeyBarStore, program: String?, programId: String?, onBack: @escaping () -> Void, onOpen: @escaping (ProfileRef) -> Void) {
        self.store = store
        self.program = program
        self.programId = programId
        self.onBack = onBack
        self.onOpen = onOpen
    }

    public var body: some View {
        let bar = store.bar
        let focused = bar.refFor(command: program, program: programId)
        Page("key bars", onBack: onBack) {
            Paragraph("Each pane gets the first profile listing its program." + (program.nonEmpty.map { " The focused pane runs \($0)." } ?? ""))
            ForEach(Array(bar.profiles.enumerated()), id: \.offset) { i, p in
                ProfileRow(profile: p, programs: p.programs.joined(separator: " "), focused: focused == .at(i)) { onOpen(.at(i)) }
            }
            ProfileRow(profile: bar.default, programs: "everything else", focused: focused == .default) { onOpen(.default) }
            HStack(spacing: 8) {
                WordButton("new profile") {
                    let forProgram = focused == .default ? program.nonEmpty : nil
                    let index = store.bar.profiles.count
                    store.update { b in
                        var b = b
                        b.profiles.append(KeyProfile(name: forProgram ?? "new", programs: forProgram.map { [$0] } ?? [], keys: b.default.keys, stop: b.default.stop))
                        return b
                    }
                    onOpen(.at(index))
                }
                WordButton(confirm ? "tap again to reset" : "reset") {
                    if confirm { store.reset() }
                    confirm.toggle()
                }
            }
        }
        .task(id: confirm) {
            guard confirm, (try? await Task.sleep(for: .seconds(3))) != nil else { return }
            confirm = false
        }
    }
}

private struct ProfileRow: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    let profile: KeyProfile
    let programs: String
    let focused: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 0) {
                    Text(profile.name)
                        .font(look.words(look.size(mono: 13, sans: 16), weight: look == .grotesque ? .semibold : .bold))
                        .foregroundColor(palette.fg)
                    if focused {
                        Circle().fill(palette.green).frame(width: 6, height: 6).padding(.leading, 8)
                    }
                    Text(programs)
                        .font(Fonts.mono(12))
                        .foregroundColor(palette.grey)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.leading, 12)
                    Text("›").font(Fonts.mono(14)).foregroundColor(palette.grey)
                }
                faceText(joined(profile.keys.map(faceOf), gap: "  "), size: 12, color: palette.dim)
                    .lineLimit(1)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12).fill(palette.bg1))
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(Pressable())
    }
}
