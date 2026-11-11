import SwiftUI
import TerminalCore

/**
 * wallflower's colors in one theme, named after Everforest's: backgrounds
 * from the page ([bg0]) up, words from [fg] down to [dim], accents, and the
 * [terminal]'s colors to match. [light] for a light theme.
 */
public struct Colors: Equatable {
    public var bg0: Color
    public var bg1: Color
    public var bg2: Color
    public var bg3: Color
    public var bg4: Color
    public var fg: Color
    public var grey: Color
    public var dim: Color
    public var green: Color
    public var orange: Color
    public var yellow: Color
    public var red: Color
    public var aqua: Color
    public var blue: Color
    public var purple: Color
    public var terminal: Theme
    /** Behind a diff's added lines. */
    public var bgGreen: Color
    /** Behind its removed ones. */
    public var bgRed: Color
    public var light: Bool

    public init(bg0: Color, bg1: Color, bg2: Color, bg3: Color, bg4: Color, fg: Color, grey: Color, dim: Color, green: Color, orange: Color, yellow: Color, red: Color, aqua: Color, blue: Color, purple: Color, terminal: Theme, bgGreen: Color? = nil, bgRed: Color? = nil, light: Bool = false) {
        self.bg0 = bg0
        self.bg1 = bg1
        self.bg2 = bg2
        self.bg3 = bg3
        self.bg4 = bg4
        self.fg = fg
        self.grey = grey
        self.dim = dim
        self.green = green
        self.orange = orange
        self.yellow = yellow
        self.red = red
        self.aqua = aqua
        self.blue = blue
        self.purple = purple
        self.terminal = terminal
        self.bgGreen = bgGreen ?? bg1.mix(with: green, by: 0.2)
        self.bgRed = bgRed ?? bg1.mix(with: red, by: 0.2)
        self.light = light
    }

    /** Borders and rules between things, whatever the theme. */
    public var border: Color { bg4 }
}

extension Color {
    public init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255, opacity: alpha)
    }

    public init(rgb: Int32) { self.init(hex: UInt32(bitPattern: rgb) & 0xFFFFFF) }
}

/** The themes the user can pick, in their own colors. */
public enum Themes {
    public static let everforestDarkHard = Colors(
        bg0: Color(hex: 0x1E2326),
        bg1: Color(hex: 0x272E33),
        bg2: Color(hex: 0x2E383C),
        bg3: Color(hex: 0x374145),
        bg4: Color(hex: 0x414B50),
        fg: Color(hex: 0xD3C6AA),
        grey: Color(hex: 0x859289),
        dim: Color(hex: 0x5C6A72),
        green: Color(hex: 0xA7C080),
        orange: Color(hex: 0xE69875),
        yellow: Color(hex: 0xDBBC7F),
        red: Color(hex: 0xE67E80),
        aqua: Color(hex: 0x83C092),
        blue: Color(hex: 0x7FBBB3),
        purple: Color(hex: 0xD699B6),
        terminal: .everforestDarkHard,
        bgGreen: Color(hex: 0x3C4841),
        bgRed: Color(hex: 0x4C3743)
    )

    public static let gruvboxDark = Colors(
        bg0: Color(hex: 0x282828),
        bg1: Color(hex: 0x32302F),
        bg2: Color(hex: 0x3C3836),
        bg3: Color(hex: 0x504945),
        bg4: Color(hex: 0x665C54),
        fg: Color(hex: 0xEBDBB2),
        grey: Color(hex: 0xA89984),
        dim: Color(hex: 0x7C6F64),
        green: Color(hex: 0xB8BB26),
        orange: Color(hex: 0xFE8019),
        yellow: Color(hex: 0xFABD2F),
        red: Color(hex: 0xFB4934),
        aqua: Color(hex: 0x8EC07C),
        blue: Color(hex: 0x83A598),
        purple: Color(hex: 0xD3869B),
        terminal: .gruvboxDark
    )

    public static let catppuccinMocha = Colors(
        bg0: Color(hex: 0x1E1E2E),
        bg1: Color(hex: 0x313244),
        bg2: Color(hex: 0x45475A),
        bg3: Color(hex: 0x585B70),
        bg4: Color(hex: 0x6C7086),
        fg: Color(hex: 0xCDD6F4),
        grey: Color(hex: 0xA6ADC8),
        dim: Color(hex: 0x7F849C),
        green: Color(hex: 0xA6E3A1),
        orange: Color(hex: 0xFAB387),
        yellow: Color(hex: 0xF9E2AF),
        red: Color(hex: 0xF38BA8),
        aqua: Color(hex: 0x94E2D5),
        blue: Color(hex: 0x89B4FA),
        purple: Color(hex: 0xCBA6F7),
        terminal: .catppuccinMocha
    )

    public static let catppuccinLatte = Colors(
        bg0: Color(hex: 0xEFF1F5),
        bg1: Color(hex: 0xE6E9EF),
        bg2: Color(hex: 0xDCE0E8),
        bg3: Color(hex: 0xCCD0DA),
        bg4: Color(hex: 0xBCC0CC),
        fg: Color(hex: 0x4C4F69),
        grey: Color(hex: 0x6C6F85),
        dim: Color(hex: 0x8C8FA1),
        green: Color(hex: 0x40A02B),
        orange: Color(hex: 0xFE640B),
        yellow: Color(hex: 0xDF8E1D),
        red: Color(hex: 0xD20F39),
        aqua: Color(hex: 0x179299),
        blue: Color(hex: 0x1E66F5),
        purple: Color(hex: 0x8839EF),
        terminal: .catppuccinLatte,
        light: true
    )

    public static let tokyoNight = Colors(
        bg0: Color(hex: 0x1A1B26),
        bg1: Color(hex: 0x1F2335),
        bg2: Color(hex: 0x292E42),
        bg3: Color(hex: 0x3B4261),
        bg4: Color(hex: 0x414868),
        fg: Color(hex: 0xC0CAF5),
        grey: Color(hex: 0xA9B1D6),
        dim: Color(hex: 0x565F89),
        green: Color(hex: 0x9ECE6A),
        orange: Color(hex: 0xFF9E64),
        yellow: Color(hex: 0xE0AF68),
        red: Color(hex: 0xF7768E),
        aqua: Color(hex: 0x73DACA),
        blue: Color(hex: 0x7AA2F7),
        purple: Color(hex: 0xBB9AF7),
        terminal: .tokyoNight,
        bgGreen: Color(hex: 0x20303B),
        bgRed: Color(hex: 0x37222C)
    )

    public static let nord = Colors(
        bg0: Color(hex: 0x2E3440),
        bg1: Color(hex: 0x3B4252),
        bg2: Color(hex: 0x434C5E),
        bg3: Color(hex: 0x4C566A),
        bg4: Color(hex: 0x616E88),
        fg: Color(hex: 0xD8DEE9),
        grey: Color(hex: 0x616E88).mix(with: Color(hex: 0xD8DEE9), by: 0.4),
        dim: Color(hex: 0x616E88),
        green: Color(hex: 0xA3BE8C),
        orange: Color(hex: 0xD08770),
        yellow: Color(hex: 0xEBCB8B),
        red: Color(hex: 0xBF616A),
        aqua: Color(hex: 0x8FBCBB),
        blue: Color(hex: 0x81A1C1),
        purple: Color(hex: 0xB48EAD),
        terminal: .nord
    )
}

/** The colors wallflower and its terminal are in: a known theme, or the phone's light or dark. */
public enum Scheme: String, CaseIterable, Codable {
    case everforestDarkHard, gruvboxDark, catppuccinMocha, catppuccinLatte, tokyoNight, nord, system

    public var title: String {
        switch self {
        case .everforestDarkHard: return "everforest dark hard"
        case .gruvboxDark: return "gruvbox dark"
        case .catppuccinMocha: return "catppuccin mocha"
        case .catppuccinLatte: return "catppuccin latte"
        case .tokyoNight: return "tokyo night"
        case .nord: return "nord"
        case .system: return "match the phone"
        }
    }

    /** The colors it stands for; the phone's own follow whether it's dark. */
    public func colors(dark: Bool) -> Colors {
        switch self {
        case .everforestDarkHard: return Themes.everforestDarkHard
        case .gruvboxDark: return Themes.gruvboxDark
        case .catppuccinMocha: return Themes.catppuccinMocha
        case .catppuccinLatte: return Themes.catppuccinLatte
        case .tokyoNight: return Themes.tokyoNight
        case .nord: return Themes.nord
        case .system: return dark ? Themes.everforestDarkHard : Themes.catppuccinLatte
        }
    }
}

private struct PaletteKey: EnvironmentKey {
    public static let defaultValue = Themes.everforestDarkHard
}

extension EnvironmentValues {
    /** The colors of the theme the user picked. */
    public var palette: Colors {
        get { self[PaletteKey.self] }
        set { self[PaletteKey.self] = newValue }
    }
}
