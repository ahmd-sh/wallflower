import SessionCore
import SwiftUI
import WallflowerDesign

// The connection, as the session's screen shows it while it isn't up.

/** How the connection is, as a color: green connected, yellow on its way, orange waiting for the user. */
public func statusColor(_ status: Status, _ palette: Colors) -> Color {
    switch status {
    case .connected: return palette.green
    case .connecting, .disconnected: return palette.yellow
    case .needsUser: return palette.orange
    }
}

extension Status {
    /** Trying to connect this moment, rather than connected or waiting (to try again, or for the user). */
    var attempting: Bool {
        if case .disconnected(_, _, _, let trying) = self { return trying }
        return self == .connecting
    }

    /** Waiting to try again, which a retry cuts short. */
    var waiting: Bool {
        if case .disconnected(_, _, _, false) = self { return true }
        return false
    }
}

/** The connection as a dot in its [statusColor]; while it tries to connect, rings ripple out of it. */
public struct ConnectionDot: View {
    @Environment(\.palette) private var palette
    let status: Status

    public init(status: Status) { self.status = status }

    public var body: some View {
        let color = statusColor(status, palette)
        ZStack {
            if status.attempting {
                TimelineView(.animation) { t in
                    let f = ripple.value(at: t.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.4) / 1.4)
                    let r = 3.5 + 3.5 * f
                    Circle()
                        .stroke(color.opacity(0.7 * (1 - f)), lineWidth: 1.2)
                        .frame(width: r * 2, height: r * 2)
                }
            }
            Circle().fill(color).frame(width: 7, height: 7)
        }
        .frame(width: 14, height: 14)
        .animation(Motion.effect, value: color)
    }

    /** How a ring slows as it spreads (Material's linear out, slow in). */
    private let ripple = UnitCurve.bezier(startControlPoint: UnitPoint(x: 0, y: 0), endControlPoint: UnitPoint(x: 0.2, y: 1))
}

/**
 * The connection's way in, at the end of the tab bar: its dot says how it
 * is. Its menu names the Mac ([machine], at [address]) and the [session],
 * says when it tries next and why the last try failed, and offers a retry
 * then, the sessions, settings and disconnecting (iOS apps don't quit).
 */
public struct ConnectionButton: View {
    @Environment(\.haptics) private var haptics
    let status: Status
    let machine: String
    let address: String
    let session: String?
    let onRetry: () -> Void
    let onSessions: () -> Void
    let onSettings: () -> Void
    let onExit: () -> Void

    public init(status: Status, machine: String, address: String, session: String?, onRetry: @escaping () -> Void, onSessions: @escaping () -> Void, onSettings: @escaping () -> Void, onExit: @escaping () -> Void) {
        self.status = status
        self.machine = machine
        self.address = address
        self.session = session
        self.onRetry = onRetry
        self.onSessions = onSessions
        self.onSettings = onSettings
        self.onExit = onExit
    }

    public var body: some View {
        Ticking(status: status) { now in
            Menu {
                SwiftUI.Section(machine) {
                    Button {} label: {
                        Text(statusLine(status, session: session, now: now))
                        Text(details)
                    }
                    .disabled(true)
                }
                SwiftUI.Section {
                    if status.waiting { Button("retry now") { pick(onRetry) } }
                    Button("sessions") { pick(onSessions) }
                    Button("settings") { pick(onSettings) }
                    Button("disconnect", role: .destructive) { pick(onExit) }
                }
            } label: {
                ConnectionDot(status: status)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 7)
                    .contentShape(RoundedRectangle(cornerRadius: 8))
            }
            .menuOrder(.fixed)
            .menuIndicator(.hidden)
            .accessibilityLabel("connection to \(machine)")
        }
        .padding(.trailing, 6)
    }

    /** Why the last try failed, if it did, and where the Mac is. */
    private var details: String {
        var lines: [String] = []
        if case .disconnected(let reason, _, _, _) = status { lines.append("last try: \(reason)") }
        lines.append(address)
        return lines.joined(separator: "\n")
    }

    private func pick(_ action: () -> Void) {
        haptics.tick()
        action()
    }
}

/** How the connection is, in words, and where it has the phone ([session]: its name and multiplexer), if anywhere. */
public func statusLine(_ status: Status, session: String?) -> String { statusLine(status, session: session, now: Date()) }

func statusLine(_ status: Status, session: String?, now: Date) -> String {
    switch status {
    case .connected: return (["connected"] + [session].compactMap { $0 }).joined(separator: " · ")
    case .connecting: return "connecting…"
    case .disconnected(_, _, let at, let trying):
        let left = trying ? 0 : secondsLeft(at, now)
        return left > 0 ? "reconnecting in \(left)s" : "reconnecting…"
    case .needsUser: return "waiting for you"
    }
}

/**
 * The connection on its way back, over what was shown (dimmed beneath it
 * meanwhile): when it tries next, with a retry now, and after a while
 * down, what might be wrong.
 */
public struct Reconnecting: View {
    @Environment(\.palette) private var palette
    let status: Status
    let machine: String
    let onRetry: () -> Void

    public init(status: Status, machine: String, onRetry: @escaping () -> Void) {
        self.status = status
        self.machine = machine
        self.onRetry = onRetry
    }

    public var body: some View {
        let shape = RoundedRectangle(cornerRadius: 12)
        HStack(spacing: 0) {
            ConnectionDot(status: status)
            VStack(alignment: .leading, spacing: 0) {
                Text("reconnecting to \(machine)…").words(.label).foregroundColor(palette.fg)
                Ticking(status: status) { now in
                    if let next = nextTry(status, machine, now) { Text(next).words(.caption).foregroundColor(palette.grey) }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, 10)
            RetryNow(status: status, onRetry: onRetry)
        }
        .padding(.leading, 12)
        .padding(.trailing, 4)
        .padding(.vertical, 8)
        .background(shape.fill(palette.bg1))
        .overlay(shape.strokeBorder(palette.border, lineWidth: 1))
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }
}

/**
 * What shows before there's anything of the Mac's to: the flower, swaying
 * while it tries to connect, the Mac ([machine], at [address]), and when it
 * tries next, with a retry now. While it waits for the user, the card
 * above says what to do, and only the flower stays.
 */
public struct Connecting: View {
    @Environment(\.palette) private var palette
    let status: Status
    let machine: String
    let address: String
    let onRetry: () -> Void
    let opening: String?

    public init(status: Status, machine: String, address: String, onRetry: @escaping () -> Void, opening: String? = nil) {
        self.status = status
        self.machine = machine
        self.address = address
        self.onRetry = onRetry
        self.opening = opening
    }

    public var body: some View {
        let said: Bool = if case .needsUser = status { false } else { true }
        VStack(spacing: 0) {
            Flower(sway: status.attempting).frame(width: 144, height: 144)
            Ticking(status: status) { now in
                VStack(spacing: 4) {
                    Text(status == .connected && opening != nil ? "opening \(opening ?? "")…" : "connecting to \(machine)…")
                        .words(.body)
                        .foregroundColor(palette.fg)
                    Text(address).font(Fonts.mono(11)).foregroundColor(palette.dim)
                    if let next = nextTry(status, machine, now) {
                        Text(next).words(.caption).foregroundColor(palette.grey).multilineTextAlignment(.center)
                    }
                    RetryNow(status: status, onRetry: onRetry)
                }
            }
            .opacity(said ? 1 : 0)
            .animation(Motion.effect, value: said)
        }
        .padding(.horizontal, 32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/** wallflower's flower, swaying from its stem while [sway]. */
public struct Flower: View {
    let sway: Bool
    /** When it started swaying; nil while it's still, or settling back. */
    @State private var since: Date?

    public init(sway: Bool) { self.sway = sway }

    public var body: some View {
        TimelineView(.animation(paused: since == nil)) { t in
            FlowerDrawing()
                .rotationEffect(.degrees(since.map { Flower.angle(t.date.timeIntervalSince($0)) } ?? 0), anchor: UnitPoint(x: 0.5, y: 0.73)) // the foot of its stem
        }
        .onAppear { if sway { since = Date() } }
        .onChange(of: sway) { _, on in
            if on {
                since = Date()
            } else {
                withAnimation(Motion.spatial) { since = nil }
            }
        }
        .accessibilityHidden(true)
    }

    /** [seconds] into swaying: over to one side, then back past the middle to the other, and so on. */
    private static func angle(_ seconds: TimeInterval) -> Double {
        let leg = Int(seconds / swing)
        let to = leg % 2 == 0 ? swayDegrees : -swayDegrees
        let from = leg == 0 ? 0 : -to
        return from + (to - from) * ease.value(at: (seconds - Double(leg) * swing) / swing)
    }

    private static let swayDegrees = 4.0
    private static let swing: TimeInterval = 1.6
    /** Material's fast out, slow in. */
    private static let ease = UnitCurve.bezier(startControlPoint: UnitPoint(x: 0.4, y: 0), endControlPoint: UnitPoint(x: 0.2, y: 1))
}

/** The flower, still, as wide as it's tall. */
private struct FlowerDrawing: View {
    var body: some View {
        Canvas { ctx, size in FlowerDrawing.draw(&ctx, size) }
            .aspectRatio(1, contentMode: .fit)
    }

    /** The flower as drawn in a 108-point square: its stem and leaf, five petals and its middle. */
    private static func draw(_ ctx: inout GraphicsContext, _ size: CGSize) {
        let s = min(size.width, size.height) / 108
        ctx.translateBy(x: (size.width - 108 * s) / 2, y: (size.height - 108 * s) / 2)
        ctx.scaleBy(x: s, y: s)
        ctx.translateBy(x: 54, y: 42)
        ctx.scaleBy(x: 0.95, y: 0.95)
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }

        var stem = Path()
        stem.move(to: p(0, 5))
        stem.addCurve(to: p(0, 39), control1: p(0.6, 15), control2: p(1.8, 27))
        ctx.stroke(stem, with: .color(green), style: StrokeStyle(lineWidth: 3.2, lineCap: .round))

        var leaf = Path()
        leaf.move(to: p(1.2, 30.5))
        leaf.addCurve(to: p(13.6, 21.4), control1: p(2.4, 24.6), control2: p(7.4, 21))
        leaf.addCurve(to: p(1.2, 30.5), control1: p(12.4, 27.4), control2: p(7.4, 31))
        leaf.closeSubpath()
        ctx.fill(leaf, with: .color(green))

        var petal = Path()
        petal.move(to: p(-1, -3.6))
        petal.addCurve(to: p(-6.6, -12.78), control1: p(-4.09, -5.8), control2: p(-6.93, -9))
        petal.addCurve(to: p(0, -18), control1: p(-6.27, -16.2), control2: p(-3.3, -18))
        petal.addCurve(to: p(6.6, -12.78), control1: p(3.3, -18), control2: p(6.27, -16.2))
        petal.addCurve(to: p(1, -3.6), control1: p(6.93, -9), control2: p(4.09, -5.8))
        petal.addQuadCurve(to: p(-1, -3.6), control: p(0, -3.1))
        petal.closeSubpath()
        for degrees in [-8.0, 64, 136, 208, 280] {
            var g = ctx
            g.rotate(by: .degrees(degrees))
            g.fill(petal, with: .color(orange))
        }

        ctx.fill(Path(ellipseIn: CGRect(x: -3, y: -3, width: 6, height: 6)), with: .color(yellow))
    }

    private static let green = Color(hex: 0xA7C080)
    private static let orange = Color(hex: 0xE69875)
    private static let yellow = Color(hex: 0xDBBC7F)
}

/** When it tries next, and after a while down, what might be wrong; nil before a first try has failed. */
private func nextTry(_ status: Status, _ machine: String, _ now: Date) -> String? {
    guard case .disconnected(_, let since, let at, let trying) = status else { return nil }
    let left = trying ? 0 : secondsLeft(at, now)
    let next = left > 0 ? "next try in \(left)s" : "trying now…"
    return now.timeIntervalSince(since) < stillDown ? next : "\(next) · is \(machine) awake and online?"
}

/** Cuts the wait for the next try short; faded out, and in its place, while there's none to cut. */
private struct RetryNow: View {
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    let status: Status
    let onRetry: () -> Void

    var body: some View {
        let waiting = status.waiting
        Button {
            haptics.tick()
            onRetry()
        } label: {
            Text("retry now")
                .words(.label)
                .foregroundColor(palette.green)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .contentShape(RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(Pressable())
        .disabled(!waiting)
        .opacity(waiting ? 1 : 0)
        .animation(Motion.effect, value: waiting)
    }
}

/**
 * [content] as of now, again whenever what it says of the connection
 * changes with time: each second of the wait for the next try, and once
 * it's been down long enough to say what might be wrong.
 */
struct Ticking<Content: View>: View {
    let status: Status
    @ViewBuilder let content: (Date) -> Content

    var body: some View {
        TimelineView(Moments(status: status)) { context in content(context.date) }
    }

    private struct Moments: TimelineSchedule {
        let status: Status

        func entries(from start: Date, mode: TimelineScheduleMode) -> [Date] {
            guard case .disconnected(_, let since, let at, let trying) = status else { return [start] }
            var dates = [start]
            if !trying {
                let left = secondsLeft(at, start)
                if left > 0 {
                    dates += stride(from: left - 1, through: 0, by: -1).map { at.addingTimeInterval(-Double($0)) }
                }
            }
            let long = since.addingTimeInterval(stillDown)
            if long > start { dates.append(long) }
            return dates.sorted()
        }
    }
}

/** Whole seconds until [at], counting down to 0. */
private func secondsLeft(_ at: Date, _ now: Date) -> Int { max(0, Int(ceil(at.timeIntervalSince(now)))) }

/** Down this long, it says what might be wrong. */
private let stillDown: TimeInterval = 20

/** What to do about a connection to [machine] that's waiting on the user, in plain words. */
public struct BlockedCard: View {
    @Environment(\.palette) private var palette
    let blocked: Blocked
    let machine: String
    let unlockSeconds: Int?
    let authorizeCommand: () -> String
    let onUnlock: () -> Void
    let onTrust: (_ host: String, _ key: String) -> Void
    let onForget: (_ host: String) -> Void
    let onRetry: () -> Void
    let onSetup: () -> Void

    public init(blocked: Blocked, machine: String, unlockSeconds: Int?, authorizeCommand: @escaping () -> String, onUnlock: @escaping () -> Void, onTrust: @escaping (_ host: String, _ key: String) -> Void, onForget: @escaping (_ host: String) -> Void, onRetry: @escaping () -> Void, onSetup: @escaping () -> Void) {
        self.blocked = blocked
        self.machine = machine
        self.unlockSeconds = unlockSeconds
        self.authorizeCommand = authorizeCommand
        self.onUnlock = onUnlock
        self.onTrust = onTrust
        self.onForget = onForget
        self.onRetry = onRetry
        self.onSetup = onSetup
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            switch blocked {
            case .keyLocked:
                CardTitle("unlock to connect")
                Paragraph("This phone's key to \(machine) is kept in its secure hardware. \(Unlock.capitalized) unlocks it for \(unlockLabel(unlockSeconds ?? PhoneKey.AUTH_SECONDS)).")
                HStack(spacing: 8) {
                    WordButton("unlock", primary: true, action: onUnlock)
                    WordButton("settings", action: onSetup)
                }
            case .unknownHost(let host, let key):
                CardTitle("is this \(machine)?")
                Paragraph("It's the first time this phone connects to \(host). Trust it if this matches the Mac's own key, which this shows on the Mac:")
                Code("ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub")
                Text(HostKeys.fingerprint(openSSH: key)).font(Fonts.mono(12)).foregroundColor(palette.aqua).textSelection(.enabled)
                HStack(spacing: 8) {
                    WordButton("trust", primary: true) { onTrust(host, key) }
                    WordButton("settings", action: onSetup)
                }
            case .hostKeyChanged(let host, let fingerprint):
                CardTitle("\(machine)'s key changed", color: palette.red)
                Paragraph("\(host) now shows a different key, \(fingerprint). If you didn't reinstall the Mac or change its keys, someone may be listening in: don't connect.")
                HStack(spacing: 8) {
                    WordButton("forget old key") { onForget(host) }
                    WordButton("settings", action: onSetup)
                }
            case .notAuthorized:
                CardTitle("\(machine) doesn't know this phone yet")
                Paragraph("Run this on the Mac to let it in (it can only run wallflower), then retry:")
                let command = authorizeCommand()
                Code(command)
                HStack(spacing: 8) {
                    WordButton("retry", primary: true, action: onRetry)
                    CopyButton(text: command)
                    ShareButton(text: command)
                }
            case .outdated(let gateway, let app):
                let macOlder = gateway < app
                CardTitle(macOlder ? "update wallflower on \(machine)" : "update this app")
                if macOlder {
                    Paragraph("\(machine)'s wallflower speaks protocol \(gateway), this app \(app). Update it from wallflower's folder on the Mac, then retry:")
                    Code("go install ./cmd/wallflower")
                } else {
                    Paragraph("\(machine)'s wallflower speaks protocol \(gateway), this app \(app). Install this app's latest build, then retry.")
                }
                HStack(spacing: 8) {
                    WordButton("retry", primary: true, action: onRetry)
                    WordButton("settings", action: onSetup)
                }
            case .refused(let reason):
                CardTitle("\(machine) turned the connection down")
                Paragraph("Its wallflower said: \(reason)")
                HStack(spacing: 8) {
                    WordButton("retry", primary: true, action: onRetry)
                    WordButton("settings", action: onSetup)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 12).fill(palette.bg2))
        .padding(12)
    }
}

private struct CardTitle: View {
    @Environment(\.palette) private var palette
    @Environment(\.look) private var look
    let text: String
    var color: Color?

    init(_ text: String, color: Color? = nil) {
        self.text = text
        self.color = color
    }

    var body: some View {
        Text(text)
            .font(look.words(look.size(mono: 14, sans: 17), weight: look == .grotesque ? .semibold : .bold))
            .foregroundColor(color ?? palette.fg)
            .fixedSize(horizontal: false, vertical: true)
    }
}
