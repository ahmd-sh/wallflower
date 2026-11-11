import Foundation

/// Kotlin's Char.isWhitespace, on a UTF-16 code unit.
func isWhitespace(_ c: UInt16) -> Bool {
    if (0x09...0x0D).contains(c) || (0x1C...0x1F).contains(c) { return true }
    guard let scalar = Unicode.Scalar(c) else { return false }
    switch scalar.properties.generalCategory {
    case .spaceSeparator, .lineSeparator, .paragraphSeparator: return true
    default: return false
    }
}

/// Kotlin's Char.isLetterOrDigit, on a UTF-16 code unit.
func isLetterOrDigit(_ c: UInt16) -> Bool {
    guard let scalar = Unicode.Scalar(c) else { return false }
    switch scalar.properties.generalCategory {
    case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber: return true
    default: return false
    }
}

extension String {
    init(utf16 units: some Collection<UInt16>) {
        self.init(decoding: units, as: UTF16.self)
    }

    /// Kotlin's trimStart().
    func trimmingLeadingWhitespace() -> String {
        String(utf16: utf16.drop(while: isWhitespace))
    }

    /// Kotlin's trim().
    func trimmingWhitespace() -> String {
        let units = Array(utf16)
        guard let first = units.firstIndex(where: { !isWhitespace($0) }),
              let last = units.lastIndex(where: { !isWhitespace($0) })
        else { return "" }
        return String(utf16: units[first...last])
    }

    /// Kotlin's isBlank().
    var isBlank: Bool { utf16.allSatisfy(isWhitespace) }

    /// Kotlin's drop(n): the first `count` UTF-16 code units off.
    func utf16Dropping(_ count: Int) -> String {
        String(utf16: utf16.dropFirst(count))
    }

    /// Kotlin's startsWith: by UTF-16 code unit, not by character.
    func utf16HasPrefix(_ prefix: String) -> Bool {
        utf16.starts(with: prefix.utf16)
    }
}

/// A pattern as java.util.regex reads it: \s and \d are ASCII only, and
/// . stops only at \n, \r, \u0085, \u2028 and \u2029 (ICU's also stops at \v and \f).
func pattern(_ source: String) -> NSRegularExpression {
    let s = Array(source)
    var java = ""
    var inClass = false
    var i = 0
    while i < s.count {
        if s[i] == "\\" && i + 1 < s.count {
            switch s[i + 1] {
            case "s": java += inClass ? #" \t\n\x0B\f\r"# : #"[ \t\n\x0B\f\r]"#
            case "d": java += inClass ? "0-9" : "[0-9]"
            default: java += String(s[i...(i + 1)])
            }
            i += 2
            continue
        }
        if s[i] == "[" { inClass = true } else if s[i] == "]" { inClass = false }
        java += s[i] == "." && !inClass ? #"[^\n\r\u0085\u2028\u2029]"# : String(s[i])
        i += 1
    }
    return try! NSRegularExpression(pattern: java)
}

extension NSRegularExpression {
    /// Kotlin's find: the first match's groupValues, "" for a group that took no part.
    func find(_ s: String) -> [String]? {
        let ns = s as NSString
        guard let m = firstMatch(in: s, range: NSRange(location: 0, length: ns.length)) else { return nil }
        return (0..<m.numberOfRanges).map { m.range(at: $0).location == NSNotFound ? "" : ns.substring(with: m.range(at: $0)) }
    }

    /// Kotlin's matches: whether all of `s` matches.
    func matchesWhole(_ s: String) -> Bool {
        let length = (s as NSString).length
        guard let m = firstMatch(in: s, options: .anchored, range: NSRange(location: 0, length: length)) else { return false }
        return m.range.length == length
    }

    /// Kotlin's split: the pieces between matches, empty ones kept.
    func split(_ s: String) -> [String] {
        let ns = s as NSString
        var pieces: [String] = []
        var last = 0
        for m in matches(in: s, range: NSRange(location: 0, length: ns.length)) {
            pieces.append(ns.substring(with: NSRange(location: last, length: m.range.location - last)))
            last = m.range.location + m.range.length
        }
        pieces.append(ns.substring(from: last))
        return pieces
    }
}

extension UInt16 {
    init(ascii c: Unicode.Scalar) {
        self.init(UInt8(ascii: c))
    }
}
