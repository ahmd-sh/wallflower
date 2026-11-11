import CoreHaptics
import SwiftUI
import UIKit

/**
 * What a touch feels like, named by what happened rather than by effect, so
 * each kind feels the same wherever it happens. Quiet when settings turn it
 * off.
 */
@MainActor
public final class Haptics {
    public var on = true
    private let selection = UISelectionFeedbackGenerator()
    private let light = UIImpactFeedbackGenerator(style: .light)
    private let soft = UIImpactFeedbackGenerator(style: .soft)
    private let rigid = UIImpactFeedbackGenerator(style: .rigid)
    private let medium = UIImpactFeedbackGenerator(style: .medium)
    private let notification = UINotificationFeedbackGenerator()
    private var engine: CHHapticEngine?

    public init() {
        if CHHapticEngine.capabilitiesForHardware().supportsHaptics {
            engine = try? CHHapticEngine()
            engine?.isAutoShutdownEnabled = true
            engine?.resetHandler = { [weak self] in try? self?.engine?.start() }
        }
    }

    /** A selection moved: a tab, a pane, an option. */
    public func tick() { if on { selection.selectionChanged() } }

    /** A drag crossed the point where letting go acts, either way. */
    public func threshold(_ reached: Bool) { if on { rigid.impactOccurred(intensity: reached ? 0.9 : 0.5) } }

    public func toggle(_ value: Bool) { if on { light.impactOccurred(intensity: value ? 1 : 0.6) } }

    /** Something was done: sent, closed, copied. */
    public func confirm() { if on { notification.notificationOccurred(.success) } }

    /** Something didn't go through. */
    public func reject() { if on { notification.notificationOccurred(.warning) } }

    public func hold() { if on { medium.impactOccurred() } }

    public func key() { if on { light.impactOccurred(intensity: 0.75) } }

    /** A held key repeating: lighter, for many in a row. */
    public func repeating() { if on { soft.impactOccurred(intensity: 0.45) } }

    /**
     * Something sliding open or shut over [seconds]: a soft swell that peaks
     * as it moves fastest and eases off as it settles. Without Core Haptics,
     * one light tick.
     */
    public func slide(_ seconds: Double) {
        guard on, seconds > 0 else { return }
        guard let engine else {
            soft.impactOccurred(intensity: 0.5)
            return
        }
        let points = 12
        let controls = (0...points).map { i -> CHHapticParameterCurve.ControlPoint in
            let u = Double(i) / Double(points)
            return .init(relativeTime: seconds * u, value: Float(Self.peak * Self.swell(u)))
        }
        let event = CHHapticEvent(eventType: .hapticContinuous, parameters: [
            CHHapticEventParameter(parameterID: .hapticIntensity, value: 1),
            CHHapticEventParameter(parameterID: .hapticSharpness, value: Self.sharpness),
        ], relativeTime: 0, duration: seconds)
        let curve = CHHapticParameterCurve(parameterID: .hapticIntensityControl, controlPoints: controls, relativeTime: 0)
        do {
            try engine.start()
            let player = try engine.makePlayer(with: CHHapticPattern(events: [event], parameterCurves: [curve]))
            try player.start(atTime: CHHapticTimeImmediate)
        } catch {
            soft.impactOccurred(intensity: 0.5)
        }
    }

    /** How strongly it's felt [u] of the way through: up to all of it early on, then easing off to almost nothing. */
    private static func swell(_ u: Double) -> Double {
        let x = u / 0.18
        return pow(x * exp(1 - x), 0.7)
    }

    private static let peak = 0.45 // gentle
    private static let sharpness: Float = 0.15 // soft and round rather than crisp
}

private struct HapticsKey: EnvironmentKey {
    @MainActor public static let defaultValue = Haptics()
}

extension EnvironmentValues {
    public var haptics: Haptics {
        get { self[HapticsKey.self] }
        set { self[HapticsKey.self] = newValue }
    }
}
