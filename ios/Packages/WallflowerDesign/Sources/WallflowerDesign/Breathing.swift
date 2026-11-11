import SwiftUI
import UIKit

/**
 * A dot that breathes, fading down and back, while [breathing]: as Core
 * Animation runs it, outside the app, so a program at work costs the phone
 * nothing to show.
 */
public struct BreathingDot: UIViewRepresentable {
    let color: Color
    let size: CGFloat
    let breathing: Bool
    let low: Float
    let period: Double

    public init(color: Color, size: CGFloat = 6, breathing: Bool, low: Float = 0.35, period: Double = 0.9) {
        self.color = color
        self.size = size
        self.breathing = breathing
        self.low = low
        self.period = period
    }

    public func makeUIView(context: Context) -> DotView { DotView() }

    public func updateUIView(_ v: DotView, context: Context) {
        v.layer.cornerRadius = size / 2
        UIView.animate(withDuration: 0.25) { v.backgroundColor = UIColor(color) }
        v.set(breathing: breathing, low: low, period: period)
    }

    public func sizeThatFits(_ proposal: ProposedViewSize, uiView: DotView, context: Context) -> CGSize? {
        CGSize(width: size, height: size)
    }

    public final class DotView: UIView {
        private var breathing = false
        private var low: Float = 0.35
        private var period = 0.9

        func set(breathing: Bool, low: Float, period: Double) {
            guard breathing != self.breathing || low != self.low || period != self.period else { return }
            self.breathing = breathing
            self.low = low
            self.period = period
            animate()
        }

        public override func didMoveToWindow() {
            super.didMoveToWindow()
            animate() // what leaves the screen, or the app going away, takes its animations with it
        }

        private func animate() {
            layer.removeAnimation(forKey: "breath")
            guard breathing, window != nil, !UserDefaults.standard.bool(forKey: "wfStill") else { return } // still for UI tests, which wait for the app to come to rest
            let a = CABasicAnimation(keyPath: "opacity")
            a.fromValue = 1
            a.toValue = low
            a.duration = period
            a.autoreverses = true
            a.repeatCount = .infinity
            a.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
            layer.add(a, forKey: "breath")
        }
    }
}
