import SessionCore
import SettingsUI
import SwiftUI

/** What the connection needs of the user, while it does: the card that says so, its answers going to the app. */
struct Blocking: View {
    let session: Session
    let model: AppModel
    let machine: String
    let onSetup: () -> Void

    var body: some View {
        if case .needsUser(let blocked) = session.status {
            BlockedCard(
                blocked: blocked, machine: machine, unlockSeconds: model.unlockSeconds, authorizeCommand: model.authorizeCommand,
                onUnlock: { model.unlock() },
                onTrust: { h, k in model.hostKeys.trust(host: h, key: k); session.retry() },
                onForget: { h in model.hostKeys.forget(host: h); session.retry() },
                onRetry: { session.retry() },
                onSetup: onSetup
            )
            .transition(.opacity.combined(with: .move(edge: .top)))
        }
    }
}

/**
 * A screen keeping up with the connection: it's down ([saysDown]) once it's
 * been down a moment, and a locked key asks for Face ID with the app in
 * front, as it locks or as the app comes back to it.
 */
struct ConnectionWatch: ViewModifier {
    @Environment(\.scenePhase) private var phase
    let status: Status
    let model: AppModel
    @Binding var saysDown: Bool
    @State private var left = false

    func body(content: Content) -> some View {
        content
            .task(id: status.isDown) {
                // Down a moment, as across a network change, it isn't worth saying.
                let down = status.isDown
                if down { try? await Task.sleep(for: .seconds(1.5)) }
                guard !Task.isCancelled else { return }
                saysDown = down
            }
            .task(id: status.isKeyLocked) {
                // Connecting is the moment to ask for Face ID, so ask right away.
                if status.isKeyLocked && phase == .active { model.unlock() }
            }
            .onChange(of: phase) { _, p in
                // Back from the background, not from Face ID's own sheet (which only makes the app inactive), to a key that locked meanwhile.
                if p == .background { left = true }
                guard p == .active, left else { return }
                left = false
                if status.isKeyLocked { model.unlock() }
            }
    }
}
