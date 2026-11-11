/// What a program's input line holds, read off its screen, and how many rows
/// it takes; while it's empty, what the program suggests typing there.
public struct InputLine: Hashable, Sendable {
    public var text: String
    public var rows: Int
    public var suggestion: String

    public init(text: String, rows: Int, suggestion: String = "") {
        self.text = text
        self.rows = rows
        self.suggestion = suggestion
    }
}
