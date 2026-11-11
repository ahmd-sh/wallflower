import LocalAuthentication
import SwiftUI
import UIKit
import WallflowerDesign

typealias Section = WallflowerDesign.Section
typealias Toggle = WallflowerDesign.Toggle
/** WallflowerDesign's Body, by a name a view's own Body doesn't hide. */
typealias Paragraph = WallflowerDesign.Body

/** A full-screen page over the terminal, with a back arrow. */
struct Page<Content: View>: View {
    @Environment(\.palette) private var palette
    let title: String
    let onBack: () -> Void
    var scrolls = true
    @ViewBuilder let content: () -> Content

    init(_ title: String, onBack: @escaping () -> Void, scrolls: Bool = true, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.onBack = onBack
        self.scrolls = scrolls
        self.content = content
    }

    var body: some View {
        VStack(spacing: 0) {
            PageHeader(title, onBack: onBack)
            ScrollView {
                VStack(alignment: .leading, spacing: 12) { content() }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20)
                    .padding(.bottom, 24)
            }
            .scrollDisabled(!scrolls)
            .scrollDismissesKeyboard(.interactively)
        }
        .background(palette.bg0.ignoresSafeArea())
    }
}

/** A row that opens a page: what it's about, what's set there now, and ›. */
struct PageRow: View {
    @Environment(\.palette) private var palette
    let title: String
    let summary: String
    let action: () -> Void

    init(_ title: String, _ summary: String, action: @escaping () -> Void) {
        self.title = title
        self.summary = summary
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).words(.body).foregroundColor(palette.fg)
                    Text(summary).words(.caption).foregroundColor(palette.grey).lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Text("›").words(.title).foregroundColor(palette.grey)
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 12).fill(palette.bg1))
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(Pressable())
    }
}

/** A button of words, as WordButton draws one, for what isn't a plain Button (a ShareLink). */
struct WordLabel: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    let label: String

    init(_ label: String) { self.label = label }

    var body: some View {
        Text(label)
            .font(look.words(look.size(mono: 13, sans: 14)))
            .foregroundColor(palette.fg)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(RoundedRectangle(cornerRadius: 8).fill(palette.bg3))
    }
}

/** Copies [text], felt as done. */
struct CopyButton: View {
    @Environment(\.haptics) private var haptics
    let text: String

    var body: some View {
        WordButton("copy") {
            UIPasteboard.general.string = text
            haptics.confirm()
        }
    }
}

/** Shares [text] through the phone's share sheet. */
struct ShareButton: View {
    let text: String

    var body: some View {
        ShareLink(item: text, subject: Text("Authorize wallflower")) { WordLabel("share") }
            .buttonStyle(Pressable())
    }
}

/** Its views in a row, wrapping onto as many more as they take. */
struct Flow: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrange(proposal.width ?? .infinity, subviews).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let origins = arrange(bounds.width, subviews).origins
        for (s, o) in zip(subviews, origins) {
            s.place(at: CGPoint(x: bounds.minX + o.x, y: bounds.minY + o.y), proposal: .unspecified)
        }
    }

    private func arrange(_ width: CGFloat, _ subviews: Subviews) -> (size: CGSize, origins: [CGPoint]) {
        var origins: [CGPoint] = []
        var x: CGFloat = 0
        var y: CGFloat = 0
        var row: CGFloat = 0
        var widest: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width {
                x = 0
                y += row + spacing
                row = 0
            }
            origins.append(CGPoint(x: x, y: y))
            widest = max(widest, x + size.width)
            x += size.width + spacing
            row = max(row, size.height)
        }
        return (CGSize(width: widest, height: y + row), origins)
    }
}

/** What unlocks this phone's key, said in a sentence: Face ID or your passcode. */
enum Unlock {
    static let words: String = {
        let context = LAContext()
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        switch context.biometryType {
        case .faceID: return "Face ID or your passcode"
        case .touchID: return "Touch ID or your passcode"
        case .opticID: return "Optic ID or your passcode"
        default: return "your passcode"
        }
    }()

    /** [words], to start a sentence with. */
    static var capitalized: String { words.prefix(1).uppercased() + words.dropFirst() }
}

/** How long an unlock lasts, shortly: 5 min, 2 h. */
func unlockLabel(_ seconds: Int) -> String { seconds < 3600 ? "\(seconds / 60) min" : "\(seconds / 3600) h" }

extension Optional where Wrapped == String {
    /** The string, unless it's missing or empty. */
    var nonEmpty: String? { self.flatMap { $0.isEmpty ? nil : $0 } }
}
