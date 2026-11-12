import ActivityKit
import SwiftUI
import WidgetKit

@main
struct WallflowerWidgets: WidgetBundle {
    var body: some Widget {
        WallflowerLiveActivity()
    }
}

/** Everforest's, the desktop's colors, whatever the app's theme: the Lock Screen and the island are always dark. */
private enum Ink {
    static let bg = Color(red: 0x1E / 255, green: 0x23 / 255, blue: 0x26 / 255)
    static let fg = Color(red: 0xD3 / 255, green: 0xC6 / 255, blue: 0xAA / 255)
    static let grey = Color(red: 0x85 / 255, green: 0x92 / 255, blue: 0x89 / 255)
    static let purple = Color(red: 0xD6 / 255, green: 0x99 / 255, blue: 0xB6 / 255)
    static let orange = Color(red: 0xE6 / 255, green: 0x98 / 255, blue: 0x75 / 255)
    static let green = Color(red: 0xA7 / 255, green: 0xC0 / 255, blue: 0x80 / 255)
}

/**
 * A program at work on the Mac: on the Lock Screen, what it's on and how
 * long it's been at it, or that it needs the user; in the Dynamic Island,
 * a dot that says which, and the time.
 */
struct WallflowerLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: WallflowerActivity.self) { context in
            LockScreen(state: seen(context), machine: context.attributes.machine)
                .widgetURL(context.state.link)
                .activityBackgroundTint(Ink.bg)
                .activitySystemActionForegroundColor(Ink.fg)
        } dynamicIsland: { context in
            let s = seen(context)
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    HStack(spacing: 8) {
                        Flower(size: 22)
                        Dot(state: s)
                    }
                    .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Elapsed(state: s).font(.system(.title3, design: .monospaced).weight(.semibold)).foregroundColor(color(s)).padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(s.about).font(.system(.headline, design: .rounded)).foregroundColor(Ink.fg).lineLimit(1).privacySensitive() // hidden while locked, as on Android
                        Text(doing(s) + " · on \(context.attributes.machine)" + (s.others > 0 ? " · \(s.others) more at work" : ""))
                            .font(.system(.subheadline, design: .monospaced)).foregroundColor(Ink.grey).lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 4)
                }
            } compactLeading: {
                Dot(state: s).padding(.leading, 2)
            } compactTrailing: {
                if s.waiting {
                    Text("input").font(.system(.caption, design: .monospaced).weight(.semibold)).foregroundColor(Ink.orange)
                } else {
                    Elapsed(state: s).font(.system(.caption, design: .monospaced).weight(.semibold)).foregroundColor(color(s)).frame(maxWidth: 52)
                }
            } minimal: {
                Dot(state: s)
            }
            .keylineTint(color(s))
            .widgetURL(s.link)
        }
    }
}

/** What the app last said, or, past its stale date (the app's been stopped too long to say again), that it's out of date. */
private func seen(_ context: ActivityViewContext<WallflowerActivity>) -> WallflowerActivity.ContentState {
    var s = context.state
    if context.isStale && !s.done { s.stale = true }
    return s
}

private func color(_ s: WallflowerActivity.ContentState) -> Color {
    s.done ? Ink.green : s.waiting ? Ink.orange : Ink.purple
}

private func doing(_ s: WallflowerActivity.ContentState) -> String {
    if s.done { return "done" }
    if s.stale { return "out of date" }
    return s.waiting ? "needs your input" : "working"
}

private struct LockScreen: View {
    let state: WallflowerActivity.ContentState
    let machine: String

    var body: some View {
        HStack(spacing: 14) {
            Flower(size: 34)
            VStack(alignment: .leading, spacing: 3) {
                Text(state.about).font(.system(.headline, design: .rounded)).foregroundColor(Ink.fg).lineLimit(1).privacySensitive() // hidden while locked, as on Android
                HStack(spacing: 6) {
                    Dot(state: state)
                    Text(doing(state)).foregroundColor(color(state)).lineLimit(1)
                }
                .font(.system(.subheadline, design: .monospaced))
                Text("on \(machine)" + (state.others > 0 ? " · \(state.others) more" : ""))
                    .font(.system(.caption, design: .monospaced))
                    .foregroundColor(Ink.grey)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            Elapsed(state: state).font(.system(.title3, design: .monospaced).weight(.semibold)).foregroundColor(color(state)).frame(maxWidth: 96, alignment: .trailing)
        }
        .padding(16)
    }
}

/** How long it's been working, ticking on its own; nothing while it waits or doesn't say. */
private struct Elapsed: View {
    let state: WallflowerActivity.ContentState

    var body: some View {
        if let since = state.since, !state.waiting, !state.done, !state.stale {
            Text(timerInterval: since...Date.distantFuture, countsDown: false)
                .monospacedDigit()
                .multilineTextAlignment(.trailing)
        } else if state.done {
            Text("✓")
        } else if state.waiting {
            Text("!")
        }
    }
}

/** What it's doing, as the app's tabs show it: purple working, orange waiting. */
private struct Dot: View {
    let state: WallflowerActivity.ContentState

    var body: some View {
        Circle().fill(color(state)).frame(width: 8, height: 8)
    }
}

/** wallflower's flower, as the app icon draws it. */
private struct Flower: View {
    let size: CGFloat

    var body: some View {
        Canvas { ctx, sz in
            let k = sz.width / 40
            ctx.translateBy(x: sz.width / 2, y: sz.height * 0.42)
            ctx.scaleBy(x: k * 0.95, y: k * 0.95)
            var stem = Path()
            stem.move(to: CGPoint(x: 0, y: 5))
            stem.addCurve(to: CGPoint(x: 0, y: 22), control1: CGPoint(x: 0.4, y: 10), control2: CGPoint(x: 1, y: 16))
            ctx.stroke(stem, with: .color(Ink.green), style: StrokeStyle(lineWidth: 3, lineCap: .round))
            var petal = Path()
            petal.move(to: CGPoint(x: -1, y: -3.6))
            petal.addCurve(to: CGPoint(x: -6.6, y: -12.78), control1: CGPoint(x: -4.09, y: -5.8), control2: CGPoint(x: -6.93, y: -9))
            petal.addCurve(to: CGPoint(x: 0, y: -18), control1: CGPoint(x: -6.27, y: -16.2), control2: CGPoint(x: -3.3, y: -18))
            petal.addCurve(to: CGPoint(x: 6.6, y: -12.78), control1: CGPoint(x: 3.3, y: -18), control2: CGPoint(x: 6.27, y: -16.2))
            petal.addCurve(to: CGPoint(x: 1, y: -3.6), control1: CGPoint(x: 6.93, y: -9), control2: CGPoint(x: 4.09, y: -5.8))
            petal.addQuadCurve(to: CGPoint(x: -1, y: -3.6), control: CGPoint(x: 0, y: -3.1))
            for deg in [-8.0, 64, 136, 208, 280] {
                var g = ctx
                g.rotate(by: .degrees(deg))
                g.fill(petal, with: .color(Ink.orange))
            }
            ctx.fill(Path(ellipseIn: CGRect(x: -3, y: -3, width: 6, height: 6)), with: .color(Color(red: 0xDB / 255, green: 0xBC / 255, blue: 0x7F / 255)))
        }
        .frame(width: size, height: size)
    }
}
