import ScreenReading
import SwiftUI
import WallflowerDesign

/**
 * What the send button does: [send] the box, or [queue] it for a program
 * at work to take later, or [hold] it for an answer the program waits on
 * first; [run] what's typed straight into the pane; [enter] the program's
 * own line; [stop] the program at work; or nothing, [idle], with nothing to
 * send and nothing running.
 */
enum SendAction { case send, queue, hold, run, enter, stop, idle }

/** The shape an action shows. */
private enum Mark { case arrow, queue, enter, stop }

private extension SendAction {
    var mark: Mark {
        switch self {
        case .send, .hold, .run, .idle: return .arrow
        case .queue: return .queue
        case .enter: return .enter
        case .stop: return .stop
        }
    }
}

/**
 * Its color says whether a tap sends (green), stops or waits (grey), or does
 * nothing; its mark (or the [label] of the keys it presses) turns into the
 * next one with a little give.
 */
struct SendButton: View {
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    let action: SendAction
    let connected: Bool
    let label: [String]?
    let onSend: (Bool) -> Void
    let onEnter: () -> Void
    let onStop: () -> Void

    var body: some View {
        let live = connected && action != .idle
        let background = !live ? palette.bg2 : (action == .stop || action == .hold) ? palette.bg3 : palette.green
        let ink = !live ? palette.dim : action == .hold ? palette.grey : action == .stop ? palette.fg : palette.bg0
        ZStack {
            Circle().fill(background)
            Group {
                if let label {
                    KeysFace(keys: label, color: ink, size: 14)
                } else {
                    MarkView(mark: action.mark, color: ink)
                }
            }
            .id(label?.joined() ?? "\(action.mark)")
            .transition(.asymmetric(insertion: .scale(scale: 0.5).combined(with: .opacity).animation(Motion.bounce), removal: .scale(scale: 0.5).combined(with: .opacity).animation(Motion.effect)))
        }
        .frame(width: 40, height: 40)
        .animation(Motion.effect, value: background)
        .contentShape(Circle())
        .onTapGesture {
            guard live else { return }
            if action != .hold { haptics.confirm() } // held, it's felt as turned back
            switch action {
            case .send, .queue, .hold: onSend(true)
            case .run, .enter: onEnter()
            case .stop: onStop()
            case .idle: break
            }
        }
        .onLongPressGesture(minimumDuration: 0.45) {
            // Held: type it in without Enter, to finish with the program's own completion.
            guard live, action == .send || action == .queue else { return }
            haptics.hold()
            onSend(false)
        }
        .accessibilityLabel(Text(String(describing: action)))
    }
}

private struct MarkView: View {
    let mark: Mark
    let color: Color

    var body: some View {
        Canvas { ctx, size in
            let c = CGPoint(x: size.width / 2, y: size.height / 2)
            let h: CGFloat = 7
            func line(_ x0: CGFloat, _ y0: CGFloat, _ x1: CGFloat, _ y1: CGFloat) {
                var p = Path()
                p.move(to: CGPoint(x: c.x + x0 * h, y: c.y + y0 * h))
                p.addLine(to: CGPoint(x: c.x + x1 * h, y: c.y + y1 * h))
                ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: 2.2, lineCap: .round))
            }
            switch mark {
            case .arrow:
                line(0, 1, 0, -1)
                line(-0.8, -0.2, 0, -1)
                line(0.8, -0.2, 0, -1)
            case .queue:
                // Up onto what's waiting.
                line(0, 0.35, 0, -1.1)
                line(-0.7, -0.4, 0, -1.1)
                line(0.7, -0.4, 0, -1.1)
                line(-0.9, 1, 0.9, 1)
            case .enter:
                line(0.8, -0.9, 0.8, 0.25)
                line(0.8, 0.25, -0.85, 0.25)
                line(-0.85, 0.25, -0.3, -0.3)
                line(-0.85, 0.25, -0.3, 0.8)
            case .stop:
                let half: CGFloat = 5.5
                ctx.fill(Path(roundedRect: CGRect(x: c.x - half, y: c.y - half, width: half * 2, height: half * 2), cornerRadius: 1.5), with: .color(color))
            }
        }
        .frame(width: 40, height: 40)
    }
}
