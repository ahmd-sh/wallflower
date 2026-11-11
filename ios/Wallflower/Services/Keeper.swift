import AVFoundation
import Foundation

/**
 * Keeps the app running while it's away, so the connection to the Mac (and
 * with it notifications and the Live Activity) keeps up, as Android's
 * foreground service does: iOS suspends an app soon after it leaves the
 * screen otherwise. It plays silence, mixed with whatever else plays, which
 * iOS lets run in the background. Off unless the user keeps it on.
 */
@MainActor
final class Keeper {
    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private var observer: NSObjectProtocol?

    var keep = false {
        didSet {
            guard keep != oldValue else { return }
            keep ? start() : stop()
        }
    }

    private func start() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, options: [.mixWithOthers])
            try session.setActive(true)
        } catch {
            return
        }
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        let format = AVAudioFormat(standardFormatWithSampleRate: 8000, channels: 1)!
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        engine.mainMixerNode.outputVolume = 0
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 8000) else { return }
        buffer.frameLength = 8000 // a second of silence, looped
        do {
            try engine.start()
        } catch {
            return
        }
        player.scheduleBuffer(buffer, at: nil, options: .loops)
        player.play()
        self.engine = engine
        self.player = player
        // Back after a call or another app's audio took over.
        observer = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt, AVAudioSession.InterruptionType(rawValue: raw) == .ended else { return }
            MainActor.assumeIsolated {
                guard let self, self.keep else { return }
                self.stop()
                self.start()
            }
        }
    }

    private func stop() {
        player?.stop()
        engine?.stop()
        engine = nil
        player = nil
        if let o = observer { NotificationCenter.default.removeObserver(o) }
        observer = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
    }
}
