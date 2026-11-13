import ScreenReading
import UIKit

/**
 * The terminal as a text field to the keyboard, so what it does in one
 * works here: a swiped word, dictation, autocorrect's picks, ⌫ held. Its
 * text is what's been typed since the keyboard last started afresh
 * ([TerminalView.keyboardText]); each change the keyboard makes to it goes
 * to the pane at once, as keys at its cursor: ⌫ back to where old and new
 * part, then the rest (KeyboardText.Edit). Return, and keys the keyboard
 * didn't type, start it afresh.
 *
 * Its text, to the keyboard, starts with a space that's never sent
 * ([lead]): a keyboard with nothing before its caret deletes once and no
 * more, so ⌫ held would stop at the pane's own line; and a word swiped
 * first goes in without a space before it.
 */
extension TerminalView: UITextInput {
    var hasText: Bool { true } // so ⌫ always goes, to the pane's line if not the keyboard's text

    func insertText(_ text: String) {
        if text == "\n" {
            forgetTyped()
            onTyped?(.return)
            return
        }
        edit { $0.insert(text) }
    }

    func deleteBackward() {
        var deleted = false
        edit { deleted = $0.deleteBackward() }
        if !deleted { onTyped?(.key("BSpace")) } // nothing of its own (the lead stays): the pane's line
    }

    /** The space before what's typed, to the keyboard; offsets it sees are past it. */
    private static let lead = " "
    private var leadLength: Int { 1 }

    /** A range the keyboard sees, in what's typed, the lead left out. */
    private func typed(_ r: NSRange) -> NSRange {
        let start = max(r.location - leadLength, 0)
        return NSRange(location: start, length: max(NSMaxRange(r) - leadLength - start, 0))
    }

    private func seen(_ r: NSRange) -> NSRange { NSRange(location: r.location + leadLength, length: r.length) }

    /** One of the keyboard's changes, gone to the pane as keys. */
    private func edit(_ change: (inout KeyboardText) -> Void) {
        let old = keyboardText.text
        inKeyboardEdit = true
        defer { inKeyboardEdit = false }
        change(&keyboardText)
        let e = KeyboardText.Edit(from: old, to: keyboardText.text)
        for _ in 0..<e.deletes { onTyped?(.key("BSpace")) }
        if !e.typed.isEmpty { onTyped?(.text(e.typed)) }
    }

    // MARK: Its text

    func text(in range: UITextRange) -> String? {
        guard let r = range as? TypedRange else { return nil }
        let all = (Self.lead + keyboardText.text) as NSString
        let start = min(max(r.range.location, 0), all.length)
        return all.substring(with: NSRange(location: start, length: min(max(r.range.length, 0), all.length - start)))
    }

    func replace(_ range: UITextRange, withText text: String) {
        guard let r = range as? TypedRange else { return }
        edit { $0.replace(typed(r.range), with: text) }
    }

    var selectedTextRange: UITextRange? {
        get { TypedRange(seen(keyboardText.selection)) }
        set { if let r = newValue as? TypedRange { keyboardText.select(typed(r.range)) } }
    }

    var markedTextRange: UITextRange? { keyboardText.marked.map { TypedRange(seen($0)) } }

    var markedTextStyle: [NSAttributedString.Key: Any]? {
        get { nil }
        set {}
    }

    func setMarkedText(_ markedText: String?, selectedRange: NSRange) {
        edit { $0.setMarked(markedText ?? "", selected: selectedRange) }
    }

    func unmarkText() { keyboardText.unmark() }

    // MARK: Places in it

    var beginningOfDocument: UITextPosition { TypedPosition(0) }
    var endOfDocument: UITextPosition { TypedPosition(leadLength + keyboardText.length) }

    func textRange(from: UITextPosition, to: UITextPosition) -> UITextRange? {
        guard let a = from as? TypedPosition, let b = to as? TypedPosition else { return nil }
        return TypedRange(NSRange(location: min(a.offset, b.offset), length: abs(b.offset - a.offset)))
    }

    func position(from position: UITextPosition, offset: Int) -> UITextPosition? {
        guard let p = position as? TypedPosition else { return nil }
        let o = p.offset + offset
        return o >= 0 && o <= leadLength + keyboardText.length ? TypedPosition(o) : nil
    }

    func position(from position: UITextPosition, in direction: UITextLayoutDirection, offset: Int) -> UITextPosition? {
        switch direction {
        case .left: self.position(from: position, offset: -offset)
        case .right: self.position(from: position, offset: offset)
        case .up: beginningOfDocument // one line
        default: endOfDocument
        }
    }

    func compare(_ position: UITextPosition, to other: UITextPosition) -> ComparisonResult {
        let a = (position as? TypedPosition)?.offset ?? 0, b = (other as? TypedPosition)?.offset ?? 0
        return a < b ? .orderedAscending : a > b ? .orderedDescending : .orderedSame
    }

    func offset(from: UITextPosition, to: UITextPosition) -> Int {
        ((to as? TypedPosition)?.offset ?? 0) - ((from as? TypedPosition)?.offset ?? 0)
    }

    func position(within range: UITextRange, farthestIn direction: UITextLayoutDirection) -> UITextPosition? {
        direction == .left || direction == .up ? range.start : range.end
    }

    func characterRange(byExtending position: UITextPosition, in direction: UITextLayoutDirection) -> UITextRange? {
        direction == .left || direction == .up
            ? textRange(from: beginningOfDocument, to: position)
            : textRange(from: position, to: endOfDocument)
    }

    func baseWritingDirection(for position: UITextPosition, in direction: UITextStorageDirection) -> NSWritingDirection { .leftToRight }

    func setBaseWritingDirection(_ writingDirection: NSWritingDirection, for range: UITextRange) {}

    // MARK: Where it is on screen: at the pane's cursor

    func firstRect(for range: UITextRange) -> CGRect { caretFrame() }
    func caretRect(for position: UITextPosition) -> CGRect { caretFrame() }
    func selectionRects(for range: UITextRange) -> [UITextSelectionRect] { [] }
    func closestPosition(to point: CGPoint) -> UITextPosition? { endOfDocument }
    func closestPosition(to point: CGPoint, within range: UITextRange) -> UITextPosition? { range.end }
    func characterRange(at point: CGPoint) -> UITextRange? { nil }
}

/** A place in what the keyboard's typed, in UTF-16 units. */
final class TypedPosition: UITextPosition {
    let offset: Int
    init(_ offset: Int) { self.offset = offset }
}

final class TypedRange: UITextRange {
    let range: NSRange
    init(_ range: NSRange) { self.range = range }
    override var start: UITextPosition { TypedPosition(range.location) }
    override var end: UITextPosition { TypedPosition(NSMaxRange(range)) }
    override var isEmpty: Bool { range.length == 0 }
}
