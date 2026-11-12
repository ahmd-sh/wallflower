import ActivityKit
import Foundation
import ScreenReading
import SessionCore
import Wire

/**
 * The Live Activity: of the session's panes, the one at work to show — one
 * waiting for the user first, then the one working longest — on the Lock
 * Screen and in the Dynamic Island, as Android's Live Update shows it in the
 * status bar. Done, it says so a moment, then goes. While the app runs it's
 * kept fresh; an app iOS has stopped can't, so past a few minutes the
 * system takes it as stale, and it says so rather than tick on.
 */
@MainActor
final class LiveActivities {
    private var current: Activity<WallflowerActivity>?
    private var shown: WallflowerActivity.ContentState?
    private var ending: Task<Void, Never>?
    private var refreshing: Task<Void, Never>?

    init() {
        // One left by an app that was killed says what nobody's keeping up: it goes, and the next starts fresh.
        for a in Activity<WallflowerActivity>.activities {
            Task { await a.end(nil, dismissalPolicy: .immediate) }
        }
    }

    /** [state]'s panes as they are; nil when the setting is off, which ends it at once. */
    func update(state: SessionState?, connected: Bool, machine: String) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        guard let state else {
            if current != nil { end() }
            return
        }
        let all = state.windows.flatMap { w in w.panes.map { (w, $0) } }
        let waiting = all.first { $0.1.activity == Pane.WAITING }
        let working = all.filter { $0.1.activity == Pane.WORKING }.min { a, b in (a.1.since > 0 ? a.1.since : .max) < (b.1.since > 0 ? b.1.since : .max) }
        guard let (w, p) = waiting ?? working else {
            if let s = shown, !s.done, connected { finish(s) }
            return
        }
        ending?.cancel()
        ending = nil
        let about = Programs.of(p.program).label(p.title) ?? w.label()
        let others = all.filter { $0.1.id != p.id && ($0.1.activity == Pane.WORKING || $0.1.activity == Pane.WAITING) }.count
        let next = WallflowerActivity.ContentState(
            about: about,
            waiting: p.activity == Pane.WAITING,
            since: p.since > 0 ? Date(timeIntervalSince1970: Double(p.since) / 1000) : nil,
            others: others,
            done: false,
            stale: !connected,
            pane: p.id
        )
        show(next, machine: machine)
    }

    private func show(_ s: WallflowerActivity.ContentState, machine: String) {
        guard s != shown else { return }
        let was = shown
        let content = fresh(s)
        if let a = current, a.activityState == .active {
            shown = s
            // Felt and heard once, as it comes to need the user.
            let alert = s.waiting && was?.waiting != true ? AlertConfiguration(title: "\(s.about)", body: "needs your input", sound: .default) : nil
            Task { await a.update(content, alertConfiguration: alert) }
        } else {
            // Only the app on screen can start one: away, it's tried again when the user's back.
            current = try? Activity.request(attributes: WallflowerActivity(machine: machine), content: content, pushType: nil)
            shown = current == nil ? nil : s
        }
        keepFresh()
    }

    /** [s], good for a few minutes: past that, an app that hasn't said again isn't running to. */
    private func fresh(_ s: WallflowerActivity.ContentState) -> ActivityContent<WallflowerActivity.ContentState> {
        ActivityContent(state: s, staleDate: Date().addingTimeInterval(Self.freshFor), relevanceScore: s.waiting ? 100 : 50)
    }

    /** While it's shown and the app runs, it's said again before it goes stale. */
    private func keepFresh() {
        guard refreshing == nil else { return }
        refreshing = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(Self.freshFor / 2))
                guard let self, let a = self.current, let s = self.shown, !s.done else { break }
                await a.update(self.fresh(s))
            }
            self?.refreshing = nil
        }
    }

    private static let freshFor: TimeInterval = 240

    /** Nothing's at work any more: it says done a moment, then goes. */
    private func finish(_ s: WallflowerActivity.ContentState) {
        var done = s
        done.done = true
        done.waiting = false
        shown = done
        guard let a = current else { return }
        Task { await a.update(ActivityContent(state: done, staleDate: nil)) }
        ending = Task { [weak self] in
            try? await Task.sleep(for: .seconds(8))
            guard !Task.isCancelled else { return }
            await a.end(nil, dismissalPolicy: .immediate)
            self?.current = nil
            self?.shown = nil
        }
    }

    func end() {
        ending?.cancel()
        refreshing?.cancel()
        let a = current
        current = nil
        shown = nil
        Task { await a?.end(nil, dismissalPolicy: .immediate) }
        for a in Activity<WallflowerActivity>.activities { Task { await a.end(nil, dismissalPolicy: .immediate) } }
    }
}
