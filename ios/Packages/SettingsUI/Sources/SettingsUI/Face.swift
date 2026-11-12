import ScreenReading
import SwiftUI
import UIKit
import WallflowerDesign

/** A key's face: words, icons for the keys that have one, and the gaps between keys. Everywhere a key shows draws it with [faceText], and [faceWidth] says how wide that is. */
public typealias Face = [FacePart]

public enum FacePart: Hashable, Sendable {
    case text(String)
    case glyph(Glyph)
    /** Between one key and the next. */
    case gap
}

/** How [key] shows: as [faceOf] its keys, unless the user wrote its label. */
public func faceOf(_ key: BarKey) -> Face {
    if key.cluster { return [.glyph(.arrows)] }
    if key.shortcuts { return [.glyph(.bolt)] }
    if key.hasOwnLabel { return faceOf(key.keys) }
    return [.text(key.label)]
}

/** [keys] with icons for those that have one (tab, ⏎, the arrows, ctrl, shift…) and the rest as the bar names them (esc, alt, ⌘, which keyboards mark differently). */
public func faceOf(_ keys: [String]) -> Face {
    var parts: Face = []
    for (i, key) in keys.enumerated() {
        if i > 0 { parts.append(.gap) }
        let (held, rest) = KeyBar.held(key)
        for m in held {
            parts.append(glyphs["\(m)-"].map(FacePart.glyph) ?? .text(KeyBar.modifierLabel(m)))
        }
        parts.append(glyphs[rest].map(FacePart.glyph) ?? .text(KeyBar.label(of: [rest])))
    }
    return parts
}

/** Faces one after another, [gap] apart. */
public func joined(_ faces: [Face], gap: String) -> Face {
    var parts: Face = []
    for (i, f) in faces.enumerated() {
        if i > 0 { parts.append(.text(gap)) }
        parts += f
    }
    return parts
}

/** [face] as text in the terminal's font at [size], its icons drawn in [color] at the size of its letters, centered on the line. */
public func faceText(_ face: Face, size: CGFloat, color: Color) -> Text {
    let m = FaceMetrics(size)
    var text = Text(verbatim: "")
    for part in face {
        switch part {
        case .text(let s):
            text = text + Text(verbatim: s)
        case .glyph(let g):
            let image = Image(size: CGSize(width: m.glyph, height: m.glyph)) { ctx in
                GlyphIcon.draw(g, in: ctx, size: CGSize(width: m.glyph, height: m.glyph), color: color)
            }
            text = text + Text(image).baselineOffset(m.lift)
        case .gap:
            text = text + Text(Image(size: CGSize(width: m.gap, height: 1)) { _ in })
        }
    }
    return text.font(Fonts.mono(size)).foregroundColor(color)
}

/** How wide [faceText] draws [face] at [size]. */
public func faceWidth(_ face: Face, size: CGFloat) -> CGFloat {
    let m = FaceMetrics(size)
    var width: CGFloat = 0
    var words = ""
    for part in face {
        switch part {
        case .text(let s): words += s
        case .glyph: width += m.glyph
        case .gap: width += m.gap
        }
    }
    return (width + (words as NSString).size(withAttributes: [.font: m.font]).width).rounded(.up)
}

private struct FaceMetrics {
    let font: UIFont
    let glyph: CGFloat
    let gap: CGFloat
    let lift: CGFloat

    init(_ size: CGFloat) {
        font = Fonts.uiMono(size)
        glyph = (size * 1.15).rounded()
        gap = (size * 0.3).rounded()
        lift = (font.ascender + font.descender) / 2 - glyph / 2
    }
}

private let glyphs: [String: Glyph] = [
    "C-": .ctrl, "S-": .shift,
    "Tab": .tab, "BTab": .backTab, "Enter": .enter, "BSpace": .backspace, "Space": .space,
    "Up": .up, "Down": .down, "Left": .left, "Right": .right,
]

public extension EnvironmentValues {
    /** What the computer's multiplexers can press, for the keys a bar or shortcut can be given. */
    @Entry var pressing = Pressing(can: [])
}
