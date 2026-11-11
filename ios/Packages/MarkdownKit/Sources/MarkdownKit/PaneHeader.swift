/// What a pane's header names it by.
public protocol HeaderPane {
    var id: String { get }
    var path: String { get }
    var command: String { get }
}

/// What each of a window's `panes` is called in its header, by id: its directory (see `placeNames`), or its command if it has none.
public func headerNames(_ panes: [some HeaderPane]) -> [String: String] {
    let places = placeNames(panes.map(\.path))
    var names: [String: String] = [:]
    for pane in panes { names[pane.id] = places[pane.path] ?? pane.command }
    return names
}

/// What each of `paths` is called beside the others: its last directory,
/// with as many above it as it takes to tell it from theirs. A home
/// (/Users/name, /home/name) is ~, unless two homes' directories would
/// read the same. Empty paths are left out.
public func placeNames(_ paths: some Collection<String>) -> [String: String] {
    var full: [String: [[UInt16]]] = [:]
    for path in paths where !path.isEmpty { full[path] = directories(of: path) }
    let homedParts = full.mapValues(homed)
    var parts: [String: [[UInt16]]] = [:]
    for (path, p) in full {
        let h = homedParts[path]!
        parts[path] = full.contains { other, q in q != p && homedParts[other] == h } ? p : h
    }
    let all = Set(parts.values)
    return parts.mapValues { p in
        let k = stride(from: 1, through: p.count, by: 1).first { k in !all.contains { $0 != p && $0.suffix(k) == p.suffix(k) } } ?? p.count
        let name = String(utf16: Array(p.suffix(k).joined(separator: [slash])))
        return name.isEmpty ? "/" : name
    }
}

/// A path's directories from the top, after "" for the root if it starts there.
/// In UTF-16 code units, which tell é from e and a combining accent, as Kotlin's strings do.
private func directories(of path: String) -> [[UInt16]] {
    (path.utf16.first == slash ? [[]] : []) + path.utf16.split(separator: slash).map(Array.init)
}

/// `parts` with ~ in place of the home they start in, if they do.
private func homed(_ parts: [[UInt16]]) -> [[UInt16]] {
    parts.count >= 3 && parts[0].isEmpty && homes.contains(parts[1]) ? [Array("~".utf16)] + parts.dropFirst(3) : parts
}

private let homes: Set = [Array("Users".utf16), Array("home".utf16)]
private let slash = UInt16(ascii: "/")
