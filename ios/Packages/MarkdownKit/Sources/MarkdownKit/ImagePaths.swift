import Foundation

/**
 * Pictures a program mentions by their paths in what it says: absolute
 * (/Users/me/shot.png), from home (~/a.png), or relative to where it runs
 * (./out/screen.png, out/screen.png, shot.png). Not parts of URLs; spaces
 * only in code that's a path and nothing else.
 */
public enum ImagePaths {
    static let extensions = "png|jpe?g|gif|webp|heic|heif|bmp|avif"

    private static let inText = try! NSRegularExpression(
        pattern: #"(?<![\w./~@+%:\\-])(?:~/|\.{1,2}/|/)?(?:[\w.@+%-]+/)*[\w@+%-][\w.@+%-]*\.(?:"# + extensions + #")(?!\.?[\w/-])"#,
        options: [.caseInsensitive]
    )

    private static let alone = try! NSRegularExpression(
        pattern: #"^(?:~/|\.{1,2}/|/)?[^\n`]*[^\s/]\.(?:"# + extensions + #")$"#,
        options: [.caseInsensitive]
    )

    /** Where in [text] pictures' paths are. */
    public static func find(in text: String) -> [Range<String.Index>] {
        let ns = text as NSString
        return inText.matches(in: text, range: NSRange(location: 0, length: ns.length)).compactMap { m in
            let s = ns.substring(with: m.range)
            return s.contains("//") ? nil : Range(m.range, in: text)
        }
    }

    /** Whether [code] is a picture's path and nothing else (spaces and all, as code can quote one). */
    public static func whole(_ code: String) -> Bool {
        let ns = code as NSString
        guard !code.contains("://"), !code.hasPrefix(" ") else { return false }
        return alone.firstMatch(in: code, range: NSRange(location: 0, length: ns.length)) != nil
    }

    /** [path] as the Mac has it: as it is from / or ~, else from [cwd] (the home if there's none). */
    public static func resolve(_ path: String, in cwd: String) -> String {
        if path.hasPrefix("/") || path.hasPrefix("~") { return path }
        var parts = cwd.isEmpty ? ["~"] : cwd.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        let absolute = cwd.hasPrefix("/")
        for p in path.split(separator: "/", omittingEmptySubsequences: true) {
            switch p {
            case ".": continue
            case "..": if !parts.isEmpty && parts.last != "~" { parts.removeLast() }
            default: parts.append(String(p))
            }
        }
        return (absolute ? "/" : "") + parts.joined(separator: "/")
    }
}
