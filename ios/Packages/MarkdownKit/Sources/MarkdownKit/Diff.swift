import Foundation

/// A line of a unified diff, as shown.
public struct DiffLine: Hashable, Sendable {
    public enum Kind: Hashable, Sendable { case hunk, context, added, removed, note }

    public var kind: Kind
    public var text: String

    public init(kind: Kind, text: String) {
        self.kind = kind
        self.text = text
    }
}

private let hunk = pattern(#"^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@"#)

/// A diff's hunks as lines: each hunk's header says where it is, in the file as it is now.
public func diffLines(_ diff: String) -> [DiffLine] {
    lines(of: diff).map { line in
        if let h = hunk.find(line) {
            return DiffLine(kind: .hunk, text: "line \(max(Int(h[2]) ?? 1, 1))")
        } else if line.utf16HasPrefix("+") {
            return DiffLine(kind: .added, text: line.utf16Dropping(1))
        } else if line.utf16HasPrefix("-") {
            return DiffLine(kind: .removed, text: line.utf16Dropping(1))
        } else if line.utf16HasPrefix(" ") {
            return DiffLine(kind: .context, text: line.utf16Dropping(1))
        } else {
            return DiffLine(kind: .note, text: line) // "\ No newline at end of file", or how much was cut
        }
    }
}

/// How many lines a diff adds and removes.
public func diffCounts(_ lines: [DiffLine]) -> (added: Int, removed: Int) {
    (lines.count { $0.kind == .added }, lines.count { $0.kind == .removed })
}

/// Kotlin's lines(): split at \r\n, \n and \r, empty lines kept.
private func lines(of text: String) -> [String] {
    let cr = UInt16(ascii: "\r"), lf = UInt16(ascii: "\n")
    let units = Array(text.utf16)
    var lines: [String] = []
    var start = 0
    var i = 0
    while i < units.count {
        if units[i] == cr || units[i] == lf {
            lines.append(String(utf16: units[start..<i]))
            i += units[i] == cr && i + 1 < units.count && units[i + 1] == lf ? 2 : 1
            start = i
        } else {
            i += 1
        }
    }
    lines.append(String(utf16: units[start...]))
    return lines
}
