package sh.ahmd.wallflower.screen

/**
 * What a program's input line holds, read off its screen, and how many rows
 * it takes; while it's empty, what the program suggests typing there.
 */
data class InputLine(val text: String, val rows: Int, val suggestion: String = "")
