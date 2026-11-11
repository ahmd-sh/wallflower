import Foundation
import ScreenReading
import SessionCore
import UserNotifications
import Wire

/**
 * What wallflower puts in the phone's notifications: what the computer's
 * programs notify, the latest per pane, as their Program has it said; and
 * the connection, lost while the user is away. On the lock screen, what the
 * programs said stays hidden until the phone's unlocked.
 */
@MainActor
final class Notices: NSObject, UNUserNotificationCenterDelegate {
    private let center = UNUserNotificationCenter.current()
    var onOpen: ((String) -> Void)?

    override init() {
        super.init()
        center.delegate = self
    }

    /** Once, while connected: notifications are how the computer's programs reach the user while they're away. */
    func ask() {
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    /** What [pane]'s program said ([notice]), in place of what it said before. */
    func show(pane: String, notice: Notice, state: SessionState?) {
        let window = state?.windows.first { w in w.panes.contains { $0.id == pane } }
        let program = window?.panes.first { $0.id == pane }?.command
        let content = UNMutableNotificationContent()
        content.title = notice.title ?? (program?.isEmpty == false ? program! : "your computer")
        content.body = notice.body
        if let w = window { content.subtitle = "\(w.index) \(w.label())" }
        content.sound = .default
        content.threadIdentifier = pane
        content.userInfo = ["pane": pane]
        content.interruptionLevel = .timeSensitive
        center.add(UNNotificationRequest(identifier: "mac:" + pane, content: content, trigger: nil))
    }

    /** What [pane]'s program said has been seen. */
    func cancel(pane: String) {
        center.removeDeliveredNotifications(withIdentifiers: ["mac:" + pane])
        center.removePendingNotificationRequests(withIdentifiers: ["mac:" + pane])
    }

    /** The connection to [host] was lost, and can't come back without the user: [why]. */
    func lost(_ host: Host?, _ why: Blocked) {
        let computer = host.called
        let content = UNMutableNotificationContent()
        content.title = "Lost the connection to \(computer)"
        content.body = Self.what(why, computer)
        center.add(UNNotificationRequest(identifier: "lost", content: content, trigger: nil))
    }

    /** The connection's back, or the user is: what said it was lost goes. */
    func back() {
        center.removeDeliveredNotifications(withIdentifiers: ["lost"])
    }

    func clear() {
        center.removeAllDeliveredNotifications()
    }

    /** What the user does about [blocked], in a line. */
    private static func what(_ blocked: Blocked, _ computer: String) -> String {
        switch blocked {
        case .keyLocked: return "Tap to unlock and reconnect."
        case .unknownHost: return "Tap to confirm it's really \(computer)."
        case .hostKeyChanged: return "Its identity changed. Tap to check before reconnecting."
        case .notAuthorized: return "It doesn't let this phone in any more. Tap to see what to do."
        case .outdated(let g, let a): return g < a ? "Update wallflower on \(computer) to reconnect." : "Update this app to reconnect."
        case .refused: return "Tap to see why."
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard let pane = response.notification.request.content.userInfo["pane"] as? String else { return }
        await MainActor.run { onOpen?(pane) }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }
}
