import CoreText
import SwiftUI
import UIKit

/** How wallflower's own words look: in the terminal's font with [x] marks, or Bricolage Grotesque with checkboxes. */
public enum Look: String, CaseIterable, Codable {
    case terminal, grotesque
}

private struct LookKey: EnvironmentKey {
    public static let defaultValue = Look.grotesque
}

extension EnvironmentValues {
    public var look: Look {
        get { self[LookKey.self] }
        set { self[LookKey.self] = newValue }
    }
}

public enum Fonts {
    /** Makes the bundled fonts usable by name; once, at launch. */
    public static func register() {
        guard let dir = Bundle.module.url(forResource: "Fonts", withExtension: nil),
              let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return }
        for f in files where f.pathExtension == "ttf" {
            CTFontManagerRegisterFontsForURL(f as CFURL, .process, nil)
        }
    }

    public static let monoName = "JetBrainsMonoNFM-Regular"
    public static let monoBoldName = "JetBrainsMonoNFM-Bold"

    /** The terminal's font: what's typed for it, keys and values. */
    public static func mono(_ size: CGFloat, bold: Bool = false) -> Font {
        .custom(bold ? monoBoldName : monoName, fixedSize: size)
    }

    public static func grotesque(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        let name: String
        switch weight {
        case .bold, .heavy, .black: name = "BricolageGrotesque-24ptBold"
        case .semibold, .medium: name = "BricolageGrotesque-14ptSemiBold"
        default: name = "BricolageGrotesque-14pt"
        }
        return .custom(name, fixedSize: size)
    }

    public static func uiMono(_ size: CGFloat, bold: Bool = false) -> UIFont {
        UIFont(name: bold ? monoBoldName : monoName, size: size) ?? .monospacedSystemFont(ofSize: size, weight: bold ? .bold : .regular)
    }
}

/**
 * The sizes wallflower's own words come in: each a mono size and a
 * grotesque one, which reads smaller. Text in the terminal's font sets its
 * own.
 */
public enum TypeStyle {
    /** Small print: notes, hints, a field's label, copy. */
    case caption
    /** Labels: tabs, rows, summaries, the body of a setting. */
    case label
    /** What's read: a dialog's body, a menu's choices. */
    case body
    /** A dialog's or a card's title. */
    case title

    public func size(_ look: Look) -> CGFloat {
        switch (self, look) {
        case (.caption, .terminal): return 11
        case (.caption, .grotesque): return 13
        case (.label, .terminal): return 12
        case (.label, .grotesque): return 14
        case (.body, .terminal): return 13
        case (.body, .grotesque): return 15
        case (.title, .terminal): return 16
        case (.title, .grotesque): return 20
        }
    }
}

extension Look {
    /** The font for wallflower's own words. */
    public func words(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        switch self {
        case .terminal: return Fonts.mono(size, bold: weight == .bold || weight == .semibold)
        case .grotesque: return Fonts.grotesque(size, weight: weight)
        }
    }

    public func font(_ style: TypeStyle, weight: Font.Weight = .regular) -> Font { words(style.size(self), weight: weight) }

    /** Grotesque reads smaller than mono at the same size. */
    public func size(mono: CGFloat, sans: CGFloat) -> CGFloat { self == .grotesque ? sans : mono }
}

/** Words in one of the styles, in the look the user picked. */
public struct Words: ViewModifier {
    @Environment(\.look) private var look
    public let style: TypeStyle
    public let weight: Font.Weight

    public func body(content: Content) -> some View {
        content.font(look.font(style, weight: weight))
    }
}

extension View {
    public func words(_ style: TypeStyle, weight: Font.Weight = .regular) -> some View {
        modifier(Words(style: style, weight: weight))
    }
}
