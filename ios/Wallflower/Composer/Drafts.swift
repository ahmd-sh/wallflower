import Foundation
import Observation

/**
 * What's typed in the composer, per pane. It outlives reconnects, locking the
 * phone and the app being killed, and takes back text a dropped connection
 * may not have delivered. A pane's draft goes with the pane.
 */
@MainActor
@Observable
final class Drafts {
    private(set) var texts: [String: String]
    @ObservationIgnored private var saving: Task<Void, Never>?

    init() {
        texts = (UserDefaults.standard.dictionary(forKey: "drafts") as? [String: String]) ?? [:]
    }

    func text(_ pane: String) -> String { texts[pane] ?? "" }

    func set(_ pane: String, _ text: String) {
        guard texts[pane] ?? "" != text else { return }
        texts[pane] = text.isEmpty ? nil : text
        save()
    }

    /** Forgets the drafts of panes that are gone (their ids can come back for new ones). */
    func keep(_ panes: Set<String>) {
        let gone = texts.keys.filter { !panes.contains($0) }
        guard !gone.isEmpty else { return }
        for p in gone { texts[p] = nil }
        save()
    }

    /** Puts back text that may not have been sent, ahead of anything typed since. */
    func restore(_ pane: String, _ text: String) {
        let now = self.text(pane)
        set(pane, now.isEmpty ? text : text + "\n" + now)
    }

    /**
     * Puts [text] in at the end of [pane]'s draft as a word of its own, as a
     * file dropped on a terminal is, and a line after it, to go on writing.
     */
    func insert(_ pane: String, _ text: String) {
        let now = self.text(pane)
        let before = now.isEmpty || now.last!.isWhitespace ? "" : " "
        set(pane, now + before + text + "\n")
    }

    private func save() {
        saving?.cancel()
        let snapshot = texts
        saving = Task {
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled else { return }
            UserDefaults.standard.set(snapshot, forKey: "drafts")
        }
    }
}
