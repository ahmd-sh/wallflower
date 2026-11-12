import SwiftUI
import UIKit

/**
 * [content] lit by a band of light passing along it, over and over: as Core
 * Animation runs it, outside the app, as BreathingDot is, so a program at
 * work costs the phone nothing to show.
 */
struct Sheen<Content: View>: UIViewRepresentable {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    func makeUIView(context: Context) -> SheenView<Content> { SheenView(content) }

    func updateUIView(_ v: SheenView<Content>, context: Context) {
        v.host.rootView = content
        v.animate()
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: SheenView<Content>, context: Context) -> CGSize? {
        CGSize(width: proposal.width ?? 0, height: proposal.height ?? 0)
    }
}

final class SheenView<Content: View>: UIView {
    let host: UIHostingController<Content>
    /** What shows of the content: a band two thirds as wide as it, faded at both ends, by where it is along it. */
    private let band = CAGradientLayer()

    init(_ content: Content) {
        host = UIHostingController(rootView: content)
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        host.safeAreaRegions = []
        host.view.backgroundColor = .clear
        addSubview(host.view)
        band.colors = [UIColor.clear.cgColor, UIColor.black.cgColor, UIColor.clear.cgColor]
        band.startPoint = CGPoint(x: 0, y: 0.5)
        band.endPoint = CGPoint(x: 1, y: 0.5)
        band.locations = Self.before
        host.view.layer.mask = band
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        host.view.frame = bounds
        // Where it is goes by the width, so the words changing their width doesn't start it again.
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        band.frame = bounds
        CATransaction.commit()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        animate()
    }

    /** Sets it going if it isn't: what leaves the screen, or the app going away, takes its animation with it. */
    func animate() {
        guard window != nil, band.animation(forKey: "sheen") == nil else { return }
        guard !UserDefaults.standard.bool(forKey: "wfStill") else { return } // still for UI tests, which wait for the app to come to rest
        let a = CABasicAnimation(keyPath: "locations")
        a.fromValue = Self.before
        a.toValue = Self.past
        a.duration = 1.6
        a.repeatCount = .infinity
        band.add(a, forKey: "sheen")
    }

    /** The band wholly before the content, and wholly past it. */
    private static var before: [NSNumber] { [-5.0 / 6, -0.5, -1.0 / 6].map { NSNumber(value: $0) } }
    private static var past: [NSNumber] { [7.0 / 6, 1.5, 11.0 / 6].map { NSNumber(value: $0) } }
}
