/** Colors as 0xRRGGBB. [palette] holds the 16 ANSI colors; the rest of the 256 are standard. */
public struct Theme: Equatable, Sendable {
    public var background: Int32
    public var foreground: Int32
    public var cursor: Int32
    public var palette: [Int32]

    public init(background: Int32, foreground: Int32, cursor: Int32, palette: [Int32]) {
        self.background = background
        self.foreground = foreground
        self.cursor = cursor
        self.palette = palette
    }
}

/** Ghostty's built-in themes of these names. */
public extension Theme {
    /** Matching the desktop. */
    static let everforestDarkHard = Theme(
        background: 0x1E2326,
        foreground: 0xD3C6AA,
        cursor: 0xE69875,
        palette: [
            0x7A8478, 0xE67E80, 0xA7C080, 0xDBBC7F, 0x7FBBB3, 0xD699B6, 0x83C092, 0xF2EFDF,
            0xA6B0A0, 0xF85552, 0x8DA101, 0xDFA000, 0x3A94C5, 0xDF69BA, 0x35A77C, 0xFFFBEF,
        ]
    )

    static let gruvboxDark = Theme(
        background: 0x282828,
        foreground: 0xEBDBB2,
        cursor: 0xEBDBB2,
        palette: [
            0x282828, 0xCC241D, 0x98971A, 0xD79921, 0x458588, 0xB16286, 0x689D6A, 0xA89984,
            0x928374, 0xFB4934, 0xB8BB26, 0xFABD2F, 0x83A598, 0xD3869B, 0x8EC07C, 0xEBDBB2,
        ]
    )

    static let catppuccinMocha = Theme(
        background: 0x1E1E2E,
        foreground: 0xCDD6F4,
        cursor: 0xF5E0DC,
        palette: [
            0x45475A, 0xF38BA8, 0xA6E3A1, 0xF9E2AF, 0x89B4FA, 0xF5C2E7, 0x94E2D5, 0xBAC2DE,
            0x585B70, 0xF7AEC2, 0xC2ECBF, 0xFCD682, 0xAECCFC, 0xF398DA, 0xB1EAE1, 0xA6ADC8,
        ]
    )

    static let catppuccinLatte = Theme(
        background: 0xEFF1F5,
        foreground: 0x4C4F69,
        cursor: 0xDC8A78,
        palette: [
            0xBCC0CC, 0xD20F39, 0x40A02B, 0xDF8E1D, 0x1E66F5, 0xEA76CB, 0x179299, 0x5C5F77,
            0xACB0BE, 0xE7103F, 0x46B02F, 0xE49931, 0x3878F6, 0xEF95D7, 0x19A1A8, 0x6C6F85,
        ]
    )

    static let tokyoNight = Theme(
        background: 0x1A1B26,
        foreground: 0xC0CAF5,
        cursor: 0xC0CAF5,
        palette: [
            0x15161E, 0xF7768E, 0x9ECE6A, 0xE0AF68, 0x7AA2F7, 0xBB9AF7, 0x7DCFFF, 0xA9B1D6,
            0x414868, 0xF7768E, 0x9ECE6A, 0xE0AF68, 0x7AA2F7, 0xBB9AF7, 0x7DCFFF, 0xC0CAF5,
        ]
    )

    static let nord = Theme(
        background: 0x2E3440,
        foreground: 0xD8DEE9,
        cursor: 0xECEFF4,
        palette: [
            0x3B4252, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x88C0D0, 0xE5E9F0,
            0x596377, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x8FBCBB, 0xECEFF4,
        ]
    )
}
