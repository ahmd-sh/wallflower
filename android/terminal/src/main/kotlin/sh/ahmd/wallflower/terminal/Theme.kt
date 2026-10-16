package sh.ahmd.wallflower.terminal

/** Colors as 0xRRGGBB. [palette] holds the 16 ANSI colors; the rest of the 256 are standard. */
data class Theme(
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val palette: IntArray,
) {
    companion object {
        /** Ghostty's "Everforest Dark Hard", matching the desktop. */
        val EverforestDarkHard = Theme(
            background = 0x1E2326,
            foreground = 0xD3C6AA,
            cursor = 0xE69875,
            palette = intArrayOf(
                0x7A8478, 0xE67E80, 0xA7C080, 0xDBBC7F, 0x7FBBB3, 0xD699B6, 0x83C092, 0xF2EFDF,
                0xA6B0A0, 0xF85552, 0x8DA101, 0xDFA000, 0x3A94C5, 0xDF69BA, 0x35A77C, 0xFFFBEF,
            ),
        )
    }
}
