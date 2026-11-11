import Foundation
import Observation
import ReadModel
import ScreenReading
import SessionCore
import SettingsUI
import UIKit
import WallflowerDesign
import Wire

/**
 * The app's connection to the Mac and what it keeps on the phone: the Mac
 * to reach, its key, the settings, drafts and key bars. While the user is
 * away it keeps the connection (as long as iOS lets it, longer with
 * [Preferences.keepConnected]), and what the Mac's programs notify shows in
 * the phone's notifications, as the pane's Program has it said; programs at
 * work show in a Live Activity. It ends when the user disconnects.
 */
@MainActor
@Observable
final class AppModel {
    let key = PhoneKey()
    let hostKeys = HostKeys()
    let settings = Preferences()
    let keyBar = KeyBarStore()
    let drafts = Drafts()
    let haptics = Haptics()
    let uploads = Uploads()
    let rehearsal = Rehearsal()
    @ObservationIgnored let notices = Notices()
    @ObservationIgnored let activities = LiveActivities()
    @ObservationIgnored let keeper = Keeper()
    @ObservationIgnored private let hidden = KeptHidden()

    private(set) var host: Host?
    private(set) var session: Session?
    private(set) var hasKey: Bool
    private(set) var unlockSeconds: Int?
    /** A pane to show, from a notification tapped. */
    var opening: String?
    /** The files page, open where it was asked for. */
    var files: FilesAt?
    /** The viewer, open on a picture or a file. */
    var showing: Showing?
    /** The user isn't looking at the app. */
    private(set) var away = true
    @ObservationIgnored private var latest: [String: ProgramNotification] = [:]
    @ObservationIgnored private var watching: Task<Void, Never>?

    init() {
        host = Self.launchHost() ?? Host.load()
        hasKey = key.exists
        unlockSeconds = key.unlockSeconds
        haptics.on = settings.haptics
        notices.onOpen = { [weak self] pane in self?.opening = pane }
        Fetched.clear()
        ensure()
    }

    /** In development, a gateway named on the command line (`-wfDev 41778`), over plain TCP. */
    private static func launchHost() -> Host? {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-wfDev"), i + 1 < args.count, let port = Int(args[i + 1]) {
            let session = args.firstIndex(of: "-wfSession").flatMap { $0 + 1 < args.count ? args[$0 + 1] : nil }
            return Host(address: "127.0.0.1", port: port, user: "dev", mux: "tmux", session: session, dev: true)
        }
        // Over ssh to a server of the developer's (`-wfSSH 22022 -wfUser me`), the key made if need be and left where it can be read off.
        if let i = args.firstIndex(of: "-wfSSH"), i + 1 < args.count, let port = Int(args[i + 1]) {
            let user = args.firstIndex(of: "-wfUser").flatMap { $0 + 1 < args.count ? args[$0 + 1] : nil } ?? "dev"
            let key = PhoneKey()
            var problem = ""
            if !key.exists {
                do { try key.generate() } catch { problem = "\(error)" }
            }
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            try? (problem.isEmpty ? key.authorizedKey(comment: "wallflower@simulator") : "# " + problem).write(to: docs.appendingPathComponent("authorized_key"), atomically: true, encoding: .utf8)
            return Host(address: "127.0.0.1", port: port, user: user, mux: "tmux", dev: false)
        }
        #endif
        return nil
    }

    func configure(_ h: Host) {
        h.save()
        host = h
        connect(h)
    }

    /** Connects to the configured Mac, unless already connected. */
    func ensure() {
        if session == nil, let h = host { connect(h) }
    }

    private func connect(_ h: Host) {
        session?.stop()
        let transport: Transport = h.dev ? TCPTransport(host: h.address.isEmpty ? "127.0.0.1" : h.address, port: h.port == 22 ? 41777 : h.port) : SSHTransport(host: h, key: key, hostKeys: hostKeys)
        let d = UserDefaults.standard
        let cols = d.integer(forKey: "grid_cols")
        let grid = cols > 0 ? (cols, max(d.integer(forKey: "grid_rows"), 10)) : (80, 24)
        let s = Session(transport: transport, mux: h.mux, session: h.session, hidden: hidden, grid: grid)
        s.setAway(away)
        s.onBack = { [weak self] place in self?.update { $0.mux = place?.mux ?? $0.mux; $0.session = place?.session } }
        s.onMachine = { [weak self] name in self?.update { $0.machine = name } }
        s.onSize = { cols, rows in
            d.set(cols, forKey: "grid_cols")
            d.set(rows, forKey: "grid_rows")
        }
        s.onNotification = { [weak self] n in self?.notified(n) }
        s.onUndelivered = { [weak self] u in
            self?.drafts.restore(u.pane, u.text)
            self?.haptics.reject()
        }
        session = s
        watch(s)
    }

    private func update(_ change: (inout Host) -> Void) {
        guard var h = host else { return }
        let before = h
        change(&h)
        guard h != before else { return }
        h.save()
        host = h
    }

    /** Keeps the Live Activity, the drafts and what's been noticed up with the session. */
    private func watch(_ s: Session) {
        watching?.cancel()
        watching = Task { [weak self] in
            var lastStatus: Status?
            while !Task.isCancelled {
                guard let self else { return }
                let state = s.state
                let status = s.status
                if let state {
                    self.drafts.keep(Set(state.windows.flatMap { $0.panes.map(\.id) }))
                }
                if status == .connected && (state != nil || s.place == nil) { self.rehearsal.begin() }
                self.uploads.dir = s.uploadDir
                self.activities.update(state: self.settings.liveActivity ? state : nil, connected: status == .connected, machine: self.host.called)
                if status != lastStatus {
                    if status == .connected { self.notices.back() }
                    if case .needsUser(let b) = status, self.away { self.notices.lost(self.host, b) }
                    lastStatus = status
                }
                self.keeper.keep = self.away && self.settings.keepConnected && self.session != nil
                let next = Once()
                await withTaskCancellationHandler {
                    await withCheckedContinuation { c in
                        next.wait(c)
                        withObservationTracking {
                            _ = s.state
                            _ = s.status
                            _ = s.uploadDir
                            _ = s.place
                            _ = self.settings.liveActivity
                            _ = self.settings.keepConnected
                            _ = self.away
                        } onChange: {
                            next.go()
                        }
                    }
                } onCancel: {
                    next.go() // a session left behind isn't kept waiting for a change it'll never see
                }
            }
        }
    }

    /**
     * Shows [n] as its pane's program has it said, from the pane's screen if
     * the program reads it; nothing it says takes away what it said before.
     * Unless a newer one came, or the pane's been seen.
     */
    private func notified(_ n: ProgramNotification) {
        guard let s = session, away || n.pane != s.shownPane() else { return }
        latest[n.pane] = n
        Task {
            let program = Programs.of(s.state?.windows.lazy.flatMap(\.panes).first { $0.id == n.pane }?.program)
            let screen = program.readsScreenToNotify ? await s.peek(n.pane) : nil
            let notice = program.notice(title: n.title, body: n.bell ? nil : n.body, screen: screen)
            guard latest[n.pane] == n else { return }
            latest[n.pane] = nil
            if let notice {
                notices.show(pane: n.pane, notice: notice, state: s.state)
            } else {
                notices.cancel(pane: n.pane)
            }
        }
    }

    /** Ends the session: the app's done with the Mac until it's opened again. */
    func disconnect() {
        watching?.cancel()
        session?.stop()
        session = nil
        latest = [:]
        notices.clear()
        activities.end()
        keeper.keep = false
    }

    /** The user left the app (it's no longer on screen), or came back to it. */
    func setAway(_ on: Bool) {
        away = on
        haptics.on = settings.haptics
        if on {
            session?.setAway(true)
            keeper.keep = settings.keepConnected && session != nil
            return
        }
        keeper.keep = false
        ensure()
        session?.setAway(false)
        notices.back()
    }

    /** The pane the app shows: what the Mac's programs said there has been seen. */
    func seen(_ pane: String) {
        latest[pane] = nil
        notices.cancel(pane: pane)
    }

    /** Creates the key if needed; returns why it couldn't. */
    func createKey() -> String? { key.exists ? nil : newKey(PhoneKey.AUTH_SECONDS) }

    /** Makes a new key, usable for [seconds] after an unlock, in place of the old; it needs authorizing on the Mac. */
    func newKey(_ seconds: Int) -> String? {
        do {
            try key.generate(seconds: seconds)
            hasKey = true
            unlockSeconds = key.unlockSeconds
            return nil
        } catch {
            return "Couldn't make a key: \(error.localizedDescription). Set a passcode first: the key is guarded by Face ID or your passcode."
        }
    }

    /** The command that authorizes this phone's key on the Mac. */
    func authorizeCommand() -> String {
        let name = UIDevice.current.name.replacingOccurrences(of: " ", with: "-").filter { $0.isLetter || $0.isNumber || $0 == "-" }
        return "~/go/bin/wallflower authorize '\(key.authorizedKey(comment: "wallflower@" + (name.isEmpty ? "iPhone" : name)))'"
    }

    /** Face ID or the passcode, then the connection goes again. */
    func unlock() {
        Task {
            if await key.unlock() { session?.retry() }
        }
    }
}

/** A wait that goes once, whichever comes first: the change it waits for, or its task being cancelled. */
private final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var waiting: CheckedContinuation<Void, Never>?
    private var gone = false

    func wait(_ c: CheckedContinuation<Void, Never>) {
        let now = lock.withLock { () -> Bool in
            if gone { return true }
            waiting = c
            return false
        }
        if now { c.resume() }
    }

    func go() {
        let c = lock.withLock { () -> CheckedContinuation<Void, Never>? in
            guard !gone else { return nil }
            gone = true
            defer { waiting = nil }
            return waiting
        }
        c?.resume()
    }
}
