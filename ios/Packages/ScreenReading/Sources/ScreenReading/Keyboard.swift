import Foundation

/**
 * What the phone's keyboard has typed into a pane since it last started
 * afresh, as the keyboard sees it: its own text, where its caret is, and
 * the part it's still composing (dictation, a swiped word, a word in
 * kana). Ranges are UTF-16, as UIKit's are. The pane has all of it, typed
 * as it went (see [Edit]).
 */
public struct KeyboardText: Equatable {
    public private(set) var text = ""
    public private(set) var selection = NSRange(location: 0, length: 0)
    public private(set) var marked: NSRange?

    public init() {}

    public var length: Int { (text as NSString).length }

    /** [s] in place of [range], the caret after it. */
    public mutating func replace(_ range: NSRange, with s: String) {
        let r = clamp(range)
        text = (text as NSString).replacingCharacters(in: r, with: s)
        selection = NSRange(location: r.location + (s as NSString).length, length: 0)
        marked = nil
    }

    /** Typed: in place of what's being composed, or of what's selected. */
    public mutating func insert(_ s: String) { replace(marked ?? selection, with: s) }

    /** ⌫: what's being composed or selected goes, else the character before the caret. False if there's nothing to delete. */
    public mutating func deleteBackward() -> Bool {
        if let m = marked, m.length > 0 { replace(m, with: ""); return true }
        if selection.length > 0 { replace(selection, with: ""); return true }
        guard selection.location > 0 else { return false }
        replace((text as NSString).rangeOfComposedCharacterSequence(at: selection.location - 1), with: "")
        return true
    }

    /** Composing [s] in place of what was being composed (or what's selected), [selected] within it. */
    public mutating func setMarked(_ s: String, selected: NSRange) {
        let r = clamp(marked ?? selection)
        text = (text as NSString).replacingCharacters(in: r, with: s)
        let n = (s as NSString).length
        marked = n > 0 ? NSRange(location: r.location, length: n) : nil
        selection = clamp(NSRange(location: r.location + min(selected.location, n), length: selected.length))
    }

    public mutating func unmark() { marked = nil }

    public mutating func select(_ range: NSRange) { selection = clamp(range) }

    public func text(in range: NSRange) -> String { (text as NSString).substring(with: clamp(range)) }

    public func clamp(_ r: NSRange) -> NSRange {
        let start = min(max(r.location, 0), length)
        return NSRange(location: start, length: min(max(r.length, 0), length - start))
    }

    /**
     * What a change of the keyboard's text comes to in a pane, which only
     * takes keys at its cursor (the text's end): ⌫ back to where the two
     * part, a character at a time, then the rest typed.
     */
    public struct Edit: Equatable {
        public let deletes: Int
        public let typed: String

        public init(deletes: Int, typed: String) {
            self.deletes = deletes
            self.typed = typed
        }

        public init(from old: String, to new: String) {
            let o = Array(old.unicodeScalars), n = Array(new.unicodeScalars)
            var same = 0
            while same < o.count && same < n.count && o[same] == n[same] { same += 1 }
            deletes = o.count - same
            var rest = String.UnicodeScalarView()
            rest.append(contentsOf: n[same...])
            typed = String(rest)
        }

        public var isEmpty: Bool { deletes == 0 && typed.isEmpty }
    }
}
