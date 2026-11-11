import Foundation
import Wire

/** Files sent to the computer, and pictures asked of it. */
@MainActor
final class Transfer {
    weak var link: Link?
    private var uploaded: [String: (Uploaded) -> Void] = [:]
    private var pictures: [String: [(Data?) -> Void]] = [:]
    private let picturesLock = NSLock()
    nonisolated(unsafe) private var arrivedPictures: [String: Data?] = [:]

    func uploaded(_ m: Uploaded) {
        uploaded.removeValue(forKey: m.id)?(m)
    }

    /** A picture's bytes, from the connection's thread. */
    nonisolated func picture(_ id: String, _ bytes: Data) {
        let got: Data? = bytes.isEmpty ? nil : bytes
        DispatchQueue.main.async {
            MainActor.assumeIsolated {
                let waiting = self.pictures.removeValue(forKey: id) ?? []
                for w in waiting { w(got) }
            }
        }
    }

    /** A picture entry's bytes, asked of the gateway; nil if it doesn't have them, or the connection drops. */
    func picture(pane: String, id: String) async -> Data? {
        guard let live = link?.current() else { return nil }
        return await withCheckedContinuation { c in
            var done = false
            let finish: (Data?) -> Void = { d in
                guard !done else { return }
                done = true
                c.resume(returning: d)
            }
            let first = pictures[id] == nil
            var watch = 0
            let settle: (Data?) -> Void = { d in
                live.forget(watch)
                finish(d)
            }
            pictures[id, default: []].append(settle)
            watch = live.whenEnded { [weak self] in
                self?.pictures[id] = nil
                finish(nil)
            }
            if first && link?.send(.picture(pane: pane, id: id), on: live) != true {
                let waiting = pictures.removeValue(forKey: id) ?? []
                for w in waiting { w(nil) }
            }
        }
    }

    /**
     * Sends [data] to the computer as upload [id] and returns where the gateway
     * saved it. It goes a chunk at a time, each followed by a ping, with only
     * [AHEAD] chunks unanswered: whatever's typed meanwhile waits behind
     * those alone rather than behind the whole file in the network's
     * buffers, and [progress] hears what has truly arrived. Throws if the
     * connection drops or the gateway won't save it.
     */
    func upload(id: String, name: String, data: Data, progress: @escaping (Int) -> Void) async throws -> String {
        guard let link, let live = link.current() else { throw TransferError("not connected") }
        let reply = Reply<Uploaded>()
        uploaded[id] = { reply.give($0) }
        let watch = live.whenEnded { reply.fail(TransferError("the connection dropped")) }
        defer {
            uploaded[id] = nil
            live.forget(watch)
        }
        guard link.send(.upload(id: id, name: name, size: Int64(data.count)), on: live) else { throw TransferError("the connection dropped") }
        var sent = 0
        var ahead: [(Int, Reply<Bool>)] = []
        func settle(_ down: Int) async throws {
            while ahead.count > down {
                let (through, arrived) = ahead.removeFirst()
                guard try await arrived.value() else { throw TransferError("the connection dropped") }
                progress(through)
            }
        }
        while sent < data.count {
            let n = min(Self.CHUNK, data.count - sent)
            let chunk = data.subdata(in: sent..<(sent + n))
            guard link.sendUpload(id, chunk, on: live) else { throw TransferError("the connection dropped") }
            sent += n
            let arrived = Reply<Bool>()
            link.ping(timeout: Link.pongTimeout, on: live) { arrived.give($0) }
            ahead.append((sent, arrived))
            try await settle(Self.AHEAD)
        }
        try await settle(0)
        let r = try await reply.value()
        guard let path = r.path else { throw TransferError(r.error ?? "not saved") }
        return path
    }

    static let CHUNK = 32 * 1024
    static let AHEAD = 8
}

public struct TransferError: Error, LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
}

/** A value that comes once, awaited. */
@MainActor
final class Reply<T> {
    private var result: Result<T, Error>?
    private var waiters: [CheckedContinuation<T, Error>] = []

    func give(_ v: T) { complete(.success(v)) }
    func fail(_ e: Error) { complete(.failure(e)) }

    private func complete(_ r: Result<T, Error>) {
        guard result == nil else { return }
        result = r
        for w in waiters { w.resume(with: r) }
        waiters = []
    }

    func value() async throws -> T {
        if let r = result { return try r.get() }
        return try await withCheckedThrowingContinuation { waiters.append($0) }
    }
}
