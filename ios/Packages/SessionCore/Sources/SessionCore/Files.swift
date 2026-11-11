import Foundation
import Wire

/** The Mac's files, as a gateway that can shows them: its directories listed, and its files fetched to the phone. */
@MainActor
final class Files {
    weak var link: Link?
    nonisolated let fetches = Fetches()
    private var listings: [String: (Listing) -> Void] = [:]
    private var asks = 0

    func listing(_ l: Listing) {
        listings.removeValue(forKey: l.id)?(l)
    }

    /** A directory's entries, by name; throws why it couldn't be read, or if the connection drops. */
    func list(_ path: String) async throws -> Listing {
        guard let link, let live = link.current() else { throw TransferError("not connected") }
        asks += 1
        let id = "l\(asks)"
        let reply = Reply<Listing>()
        listings[id] = { reply.give($0) }
        let watch = live.whenEnded { reply.fail(TransferError("the connection dropped")) }
        defer {
            listings[id] = nil
            live.forget(watch)
        }
        guard link.send(.list(id: id, path: path), on: live) else { throw TransferError("the connection dropped") }
        let l = try await reply.value()
        if let error = l.error { throw TransferError(error) }
        return l
    }

    /**
     * The file at [path] (all of it, or its first [limit] bytes), written to
     * [to] as it comes; [progress] hears the bytes that have, of how many.
     * Returns what the gateway said of it. Cancelled, the gateway stops
     * sending it. Throws why it couldn't be had, or if the connection drops.
     */
    func fetch(_ path: String, limit: Int64?, to url: URL, progress: @escaping @MainActor (Int64, Int64) -> Void) async throws -> FileReply {
        guard let link, let live = link.current() else { throw TransferError("not connected") }
        asks += 1
        let id = "f\(asks)"
        try? FileManager.default.removeItem(at: url)
        guard FileManager.default.createFile(atPath: url.path, contents: nil), let handle = try? FileHandle(forWritingTo: url) else {
            throw TransferError("there's no room for it on the phone")
        }
        let reply = Reply<FileReply>()
        fetches.start(id, handle, progress: progress) { result in
            switch result {
            case .success(let r): reply.give(r)
            case .failure(let e): reply.fail(e)
            }
        }
        let watch = live.whenEnded { [fetches] in
            fetches.drop(id)
            reply.fail(TransferError("the connection dropped"))
        }
        defer { live.forget(watch) }
        guard link.send(.fetch(id: id, path: path, limit: limit), on: live) else {
            fetches.drop(id)
            throw TransferError("the connection dropped")
        }
        return try await withTaskCancellationHandler {
            try await reply.value()
        } onCancel: { [fetches] in
            fetches.drop(id)
            Task { @MainActor [weak link] in
                link?.send(.cancel(id: id), on: live)
                reply.fail(CancellationError())
            }
        }
    }
}

/** Files on their way from the Mac, written as their frames arrive on the connection's thread. */
final class Fetches: @unchecked Sendable {
    private struct Fetching {
        let handle: FileHandle
        let progress: @MainActor (Int64, Int64) -> Void
        let done: @MainActor (Result<FileReply, Error>) -> Void
        var reply: FileReply?
        var got: Int64 = 0
    }

    private var open: [String: Fetching] = [:]
    private let lock = NSLock()

    func start(_ id: String, _ handle: FileHandle, progress: @escaping @MainActor (Int64, Int64) -> Void, done: @escaping @MainActor (Result<FileReply, Error>) -> Void) {
        lock.withLock { open[id] = Fetching(handle: handle, progress: progress, done: done) }
    }

    /** Stops writing a fetch; what comes of it after is passed over. */
    func drop(_ id: String) {
        let f = lock.withLock { open.removeValue(forKey: id) }
        try? f?.handle.close()
    }

    /** What the gateway says of a fetch: how much follows, or why none (or none more) does. */
    func reply(_ r: FileReply) {
        lock.lock()
        guard var f = open[r.id] else { return lock.unlock() }
        if let error = r.error {
            open[r.id] = nil
            lock.unlock()
            try? f.handle.close()
            main { f.done(.failure(TransferError(error))) }
            return
        }
        guard f.reply == nil else { return lock.unlock() }
        f.reply = r
        if r.size == 0 {
            open[r.id] = nil
            lock.unlock()
            try? f.handle.close()
            main { f.done(.success(r)) }
            return
        }
        open[r.id] = f
        lock.unlock()
        main { f.progress(0, r.size) }
    }

    /** The next of a fetch's bytes. */
    func bytes(_ id: String, _ data: Data) {
        lock.lock()
        guard var f = open[id], let r = f.reply else { return lock.unlock() }
        do {
            try f.handle.write(contentsOf: data)
        } catch {
            open[id] = nil
            lock.unlock()
            try? f.handle.close()
            main { f.done(.failure(TransferError("there's no room for it on the phone"))) }
            return
        }
        f.got += Int64(data.count)
        let got = f.got
        if got < r.size {
            open[id] = f
            lock.unlock()
            main { f.progress(got, r.size) }
            return
        }
        open[id] = nil
        lock.unlock()
        try? f.handle.close()
        main {
            f.progress(got, r.size)
            f.done(.success(r))
        }
    }

    private func main(_ work: @escaping @MainActor () -> Void) {
        DispatchQueue.main.async { MainActor.assumeIsolated { work() } }
    }
}
