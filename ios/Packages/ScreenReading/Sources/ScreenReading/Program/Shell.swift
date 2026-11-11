/// A program that marks its prompt (OSC 133), as a shell can: its line is
/// what's typed after the last prompt. `clearKeys` empty a row of it, if
/// anything does.
public final class Shell: Program {
    public let clearKeys: [String]
    public let keyBar: KeyDefaults?

    public init(clearKeys: [String] = [], keyBar: KeyDefaults? = nil) {
        self.clearKeys = clearKeys
        self.keyBar = keyBar
    }

    /// The input cells after the last prompt, without autosuggestion ghost
    /// text after the cursor. None while a command runs: its output, not a
    /// prompt, holds the cursor.
    public func line(_ screen: Screen) -> InputLine? {
        let s = screen
        if !(0..<s.rows).contains(s.cursorY) { return nil }
        guard let prompt = downTo(s.cursorY, 0).first(where: { y in (0..<s.cols).contains { s.role($0, y) == Screen.prompt } }) else { return nil }
        func holdsLine(_ y: Int) -> Bool { (0..<s.cols).contains { s.role($0, y) != Screen.output } }
        if (prompt...s.cursorY).contains(where: { !holdsLine($0) }) { return nil }
        var last = s.cursorY
        while last + 1 < s.rows && s.isWrapped(last) { last += 1 }

        var out = ""
        for y in prompt...last {
            var row = ""
            // From its first input cell to its last: a shell can leave a blank it typed undrawn, unmarked.
            let first = (0..<s.cols).first { s.role($0, y) == Screen.input } ?? -1
            let end = downTo(s.cols - 1, 0).first { s.role($0, y) == Screen.input } ?? -2
            for x in stride(from: max(first, 0), through: end, by: 1) {
                if s.isSpacer(x, y) { continue }
                let afterCursor = y > s.cursorY || (y == s.cursorY && x >= s.cursorX)
                if afterCursor && (s.isGrey(x, y) || s.isFaint(x, y)) { continue }
                let t = s.text(x, y)
                row += t.isEmpty ? " " : t
            }
            out += s.isWrapped(y) ? row : row.trimmedEnd()
            if y < last && !s.isWrapped(y) { out += "\n" }
        }
        return InputLine(text: out.trimming("\n"), rows: last - prompt + 1)
    }
}
