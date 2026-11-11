import Foundation

// Strings as the Kotlin this was ported from reads them: indexed, measured and
// compared by UTF-16 unit, trimmed of what Kotlin calls whitespace.

/// Kotlin's `Char.isWhitespace`.
func isWhitespace(_ u: UInt16) -> Bool {
    switch u {
    case 0x09...0x0D, 0x1C...0x20, 0xA0, 0x1680, 0x2000...0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000: return true
    default: return false
    }
}

/// Kotlin's `Char.isLetterOrDigit`: a surrogate is neither.
func isLetterOrDigit(_ u: UInt16) -> Bool {
    guard let s = Unicode.Scalar(u) else { return false }
    switch s.properties.generalCategory {
    case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber: return true
    default: return false
    }
}

/// Kotlin's `from until to`: empty, not a trap, when `to` is below `from`.
func until(_ from: Int, _ to: Int) -> Range<Int> { from..<max(from, to) }

/// Kotlin's `from downTo to`.
func downTo(_ from: Int, _ to: Int) -> StrideThrough<Int> { stride(from: from, through: to, by: -1) }

/// The UTF-16 unit of a character in the Basic Multilingual Plane.
func unit(_ c: Unicode.Scalar) -> UInt16 { UInt16(c.value) }

extension String {
    init<C: Collection>(units: C) where C.Element == UInt16 { self.init(decoding: units, as: UTF16.self) }

    var units: [UInt16] { Array(utf16) }

    var length: Int { utf16.count }

    func substring(_ from: Int, _ to: Int? = nil) -> String {
        let u = units
        return String(units: u[from..<(to ?? u.count)])
    }

    func dropping(_ n: Int) -> String { String(units: units.dropFirst(n)) }

    var isBlank: Bool { utf16.allSatisfy(isWhitespace) }

    func trimmed() -> String { trimmingStart(isWhitespace).trimmedEnd() }

    func trimmedStart() -> String { trimmingStart(isWhitespace) }

    func trimmedEnd() -> String {
        let u = units
        var end = u.count
        while end > 0 && isWhitespace(u[end - 1]) { end -= 1 }
        return String(units: u[..<end])
    }

    func trimmingStart(_ drop: (UInt16) -> Bool) -> String {
        let u = units
        var start = 0
        while start < u.count && drop(u[start]) { start += 1 }
        return String(units: u[start...])
    }

    /// Kotlin's `trim(c)`: `c` off both ends.
    func trimming(_ c: Unicode.Scalar) -> String {
        let u = units, t = unit(c)
        var start = 0, end = u.count
        while start < end && u[start] == t { start += 1 }
        while end > start && u[end - 1] == t { end -= 1 }
        return String(units: u[start..<end])
    }

    func removingWhitespace() -> String { String(units: utf16.filter { !isWhitespace($0) }) }

    func startsWith(_ prefix: String) -> Bool { utf16.starts(with: prefix.utf16) }

    func endsWith(_ suffix: String) -> Bool { utf16.reversed().starts(with: suffix.utf16.reversed()) }

    /// Kotlin's `indexOf`: the first unit `s` starts at, from `from`; -1 if it's not there.
    func indexOf(_ s: String, from: Int = 0) -> Int {
        let u = units, n = s.units
        let from = Swift.max(from, 0)
        if n.isEmpty { return Swift.min(from, u.count) }
        guard u.count >= n.count else { return -1 }
        var i = from
        while i <= u.count - n.count {
            if u[i] == n[0] && u[i..<(i + n.count)].elementsEqual(n) { return i }
            i += 1
        }
        return -1
    }

    func removingPrefix(_ prefix: String) -> String { startsWith(prefix) ? dropping(prefix.length) : self }

    func removingSuffix(_ suffix: String) -> String { endsWith(suffix) ? substring(0, length - suffix.length) : self }

    func substringBefore(_ c: Unicode.Scalar) -> String {
        let u = units
        return u.firstIndex(of: unit(c)).map { String(units: u[..<$0]) } ?? self
    }

    func substringAfterLast(_ c: Unicode.Scalar) -> String {
        let u = units
        return u.lastIndex(of: unit(c)).map { String(units: u[($0 + 1)...]) } ?? self
    }

    func split(_ c: Unicode.Scalar) -> [String] {
        units.split(separator: unit(c), omittingEmptySubsequences: false).map { String(units: $0) }
    }

    func count(_ c: Unicode.Scalar...) -> Int {
        let wanted = c.map(unit)
        return utf16.reduce(0) { $0 + (wanted.contains($1) ? 1 : 0) }
    }

    func repeated(_ n: Int) -> String { String(repeating: self, count: Swift.max(n, 0)) }
}

/// A java.util.regex pattern as Kotlin matches it: ranges in UTF-16 units,
/// and `\s`, `\S` and `\d` ASCII, as Java's are.
struct Pattern: @unchecked Sendable {
    private let regex: NSRegularExpression
    private let whole: NSRegularExpression

    init(_ pattern: String) {
        let p = pattern
            .replacingOccurrences(of: "\\s", with: "[\\t-\\r ]")
            .replacingOccurrences(of: "\\S", with: "[^\\t-\\r ]")
            .replacingOccurrences(of: "\\d", with: "[0-9]")
        regex = try! NSRegularExpression(pattern: p)
        whole = try! NSRegularExpression(pattern: "\\A(?:" + p + ")\\z")
    }

    /// Kotlin's `matchEntire`.
    func matchEntire(_ s: String) -> Match? {
        let ns = s as NSString
        return whole.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)).map { Match(text: ns, result: $0) }
    }

    func matches(_ s: String) -> Bool { matchEntire(s) != nil }

    func find(_ s: String) -> Match? {
        let ns = s as NSString
        return regex.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)).map { Match(text: ns, result: $0) }
    }

    func findAll(_ s: String) -> [Match] {
        let ns = s as NSString
        return regex.matches(in: s, range: NSRange(location: 0, length: ns.length)).map { Match(text: ns, result: $0) }
    }

    func containsMatch(in s: String) -> Bool { find(s) != nil }

    func replace(_ s: String, with replacement: String) -> String {
        let ns = s as NSString
        return regex.stringByReplacingMatches(in: s, range: NSRange(location: 0, length: ns.length), withTemplate: NSRegularExpression.escapedTemplate(for: replacement))
    }
}

/// A match of a `Pattern`, its ranges in UTF-16 units.
struct Match {
    let text: NSString
    let result: NSTextCheckingResult

    var value: String { text.substring(with: result.range) }

    var range: Range<Int> { result.range.location..<(result.range.location + result.range.length) }

    /// The group's text; empty if it took no part, as Kotlin's `groupValues` has it.
    func groupValue(_ i: Int) -> String { group(i)?.value ?? "" }

    /// The group, if it took part.
    func group(_ i: Int) -> (value: String, range: Range<Int>)? {
        let r = result.range(at: i)
        guard r.location != NSNotFound else { return nil }
        return (text.substring(with: r), r.location..<(r.location + r.length))
    }
}
