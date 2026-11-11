import SwiftUI
import UIKit
import WallflowerDesign

/** Whether the conversation is on screen, so its working marks move. */
private struct LiveKey: EnvironmentKey {
    static let defaultValue = true
}

extension EnvironmentValues {
    var mobileViewLive: Bool {
        get { self[LiveKey.self] }
        set { self[LiveKey.self] = newValue }
    }
}

/** What was left out, as "… 12 more lines"; nil if nothing was. */
func more(_ n: Int, _ thing: String) -> String? {
    n > 0 ? "… \(n.formatted(.number)) more \(thing)\(n == 1 ? "" : "s")" : nil
}

/** The space between lines that sets words of a size [lineHeight] apart, line to line. */
@MainActor
enum Leading {
    static func words(_ look: Look, _ size: CGFloat, _ lineHeight: CGFloat, weight: Font.Weight = .regular) -> CGFloat {
        spacing(name(look, weight), size, lineHeight)
    }

    static func mono(_ size: CGFloat, _ lineHeight: CGFloat) -> CGFloat {
        spacing(Fonts.monoName, size, lineHeight)
    }

    /** The bundled face [look] draws words of [weight] in. */
    nonisolated static func name(_ look: Look, _ weight: Font.Weight) -> String {
        switch look {
        case .terminal: return weight == .bold || weight == .semibold ? Fonts.monoBoldName : Fonts.monoName
        case .grotesque:
            switch weight {
            case .bold, .heavy, .black: return "BricolageGrotesque-24ptBold"
            case .semibold, .medium: return "BricolageGrotesque-14ptSemiBold"
            default: return "BricolageGrotesque-14pt"
            }
        }
    }

    private static var natural: [String: CGFloat] = [:]

    private static func spacing(_ name: String, _ size: CGFloat, _ lineHeight: CGFloat) -> CGFloat {
        let key = "\(name) \(size)"
        if let height = natural[key] { return max(lineHeight - height, 0) }
        // Before the fonts are registered there's nothing to measure, and nothing to keep.
        guard let font = UIFont(name: name, size: size) else { return max(lineHeight - size * 1.2, 0) }
        natural[key] = font.lineHeight
        return max(lineHeight - font.lineHeight, 0)
    }
}

/** A face slanted as a missing italic is made up: none of the bundled ones have one. */
func slanted(_ name: String, _ size: CGFloat) -> Font {
    var skew = CGAffineTransform(a: 1, b: 0, c: 0.2, d: 1, tx: 0, ty: 0)
    return Font(CTFontCreateWithName(name as CFString, size, &skew))
}

extension Motion {
    /** How long [spatial] takes to move something [distance] points, until it's within half a point of where it stops. */
    static func settleSeconds(_ distance: CGFloat) -> Double {
        // Critically damped: what's left is d(1 + ωt)e^(-ωt).
        let d = Double(abs(distance))
        let w = 2 * Double.pi / 0.42
        var t = 0.0
        while t < 2, d * (1 + w * t) * exp(-w * t) > 0.5 { t += 0.004 }
        return t
    }
}

/**
 * Feels a fold opening or shutting: after a [tap], its next change of height
 * plays a slide lasting as long as the spring takes to move that far.
 */
@MainActor
final class Fold {
    private var tapped = false
    private var height: CGFloat = 0
    private var open: Bool?

    func tap() {
        tapped = true
    }

    /** Its content goes as it shuts, all of it at once (it animates out after). */
    func leave(_ haptics: Haptics) {
        if tapped { feel(height, haptics) }
        tapped = false
        height = 0
    }

    /** Whether it's open now, for a fold that opens by itself: a change is felt as if tapped; laid out already, at once. */
    func shown(_ open: Bool, _ haptics: Haptics) {
        let was = self.open
        self.open = open
        if was == nil || was == open { return }
        tap()
        if !open {
            leave(haptics)
        } else if height > 0 {
            feel(height, haptics)
        }
    }

    /** Its content's height, laid out at the size it's animating to. */
    func laidOut(_ h: CGFloat, _ haptics: Haptics) {
        let moved = abs(h - height)
        height = h
        if tapped && moved > 0 { feel(moved, haptics) }
    }

    private func feel(_ distance: CGFloat, _ haptics: Haptics) {
        tapped = false
        haptics.slide(Motion.settleSeconds(distance))
    }
}

/** Puts text on the clipboard, felt, and says it [copied] for a moment after. */
@MainActor
@Observable
final class Copier {
    private(set) var copied = false
    @ObservationIgnored private var reset: Task<Void, Never>?

    func copy(_ text: String, _ haptics: Haptics) {
        UIPasteboard.general.string = text
        haptics.confirm()
        copied = true
        reset?.cancel()
        reset = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(1_500))
            if !Task.isCancelled { self?.copied = false }
        }
    }
}

/** Pressed, a row lights a little, as a touch on a list's row does. */
struct Lit: ButtonStyle {
    var radius: CGFloat
    @Environment(\.palette) private var palette

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .overlay(RoundedRectangle(cornerRadius: radius).fill(palette.fg.opacity(configuration.isPressed ? 0.08 : 0)))
            .animation(Motion.effect, value: configuration.isPressed)
    }
}
