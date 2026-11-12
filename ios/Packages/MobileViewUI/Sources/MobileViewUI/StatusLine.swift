import ScreenReading
import SwiftUI
import WallflowerDesign

/** The program working on its turn, as it puts it ([doing]) if it says, and for how long; gone when it's done. */
struct Working: View {
    let since: Int64
    let doing: String?
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    @Environment(\.mobileViewLive) private var live
    @Environment(\.accessibilityReduceMotion) private var still

    var body: some View {
        // The words change once a second; the light passing along them moves on its own, without laying them out again.
        TimelineView(.animation(minimumInterval: 1, paused: !live || since <= 0)) { timeline in
            let label = (doing?.lowercased() ?? "working") + (since > 0 ? " · " + duration(elapsed(since, timeline.date)) : "")
            HStack(spacing: 0) {
                Running()
                Text(label)
                    .words(.label)
                    .foregroundColor(palette.grey)
                    .overlay {
                        if live && !still {
                            Sheen { Text(label).font(look.font(.label)).foregroundColor(palette.purple) }
                                .accessibilityHidden(true)
                        }
                    }
                    .padding(.leading, 8)
            }
            .padding(.vertical, 2)
        }
    }
}

private func elapsed(_ since: Int64, _ now: Date) -> Int64 {
    since > 0 ? max((Int64(now.timeIntervalSince1970 * 1_000) - since) / 1_000, 0) : 0
}

private func duration(_ seconds: Int64) -> String {
    if seconds < 60 { return "\(seconds)s" }
    if seconds < 3_600 { return "\(seconds / 60)m \(seconds % 60)s" }
    return "\(seconds / 3_600)h \(seconds % 3_600 / 60)m"
}

/**
 * The program needs an answer that isn't on its screen as a menu wallflower
 * can read; or it [asked] questions that wait to be brought up, which a tap
 * on them does ([onBring]), and then they're a menu.
 */
struct Waiting: View {
    let asked: Asked?
    let onBring: () -> Void
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics

    var body: some View {
        HStack(spacing: 0) {
            Circle().fill(palette.orange).frame(width: 6, height: 6)
            if let asked {
                Text("\(asked.what) for you")
                    .words(.label)
                    .foregroundColor(palette.grey)
                    .padding(.leading, 8)
                Button {
                    haptics.tick()
                    onBring()
                } label: {
                    Text("answer")
                        .words(.label)
                        .fontWeight(.semibold)
                        .foregroundColor(palette.orange)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 4)
                        .background(Capsule().fill(palette.orange.opacity(0.14)))
                }
                .buttonStyle(.plain)
                .padding(.leading, 10)
            } else {
                Text("waiting for you · answer in the terminal")
                    .words(.label)
                    .foregroundColor(palette.grey)
                    .padding(.leading, 8)
            }
        }
        .padding(.vertical, 2)
    }
}
