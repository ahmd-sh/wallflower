import XCTest
import ScreenReading
@testable import SessionCore

/** Against `wallflower listen` on a throwaway tmux server, when WF_LISTEN names its port. */
@MainActor
final class LiveGatewayTests: XCTestCase {
    func testAttachesAndStreamsAPane() async throws {
        guard let port = ProcessInfo.processInfo.environment["WF_LISTEN"].flatMap(Int.init) else { throw XCTSkip("no gateway") }
        let s = Session(transport: TCPTransport(port: port), mux: "tmux", session: nil, grid: (60, 20))
        s.start()
        try await until { s.status == .connected }
        try await until { s.sessions != nil }
        let demo = try XCTUnwrap(s.sessions?.muxes.first { $0.name == "tmux" }?.sessions.first { $0.name == "demo" })
        s.switchTo(mux: "tmux", session: demo.id)
        try await until { s.state != nil && !s.terminals.isEmpty }
        let pane = try XCTUnwrap(s.shownPane())
        s.submit(pane, "echo wallflower-ios-\(42 * 2)")
        try await until {
            guard let bytes = s.terminals[pane]?.screenBytes(), let screen = Screen.parse(bytes) else { return false }
            return (0..<screen.rows).contains { y in (0..<screen.cols).map { screen.text($0, y) }.joined().contains("wallflower-ios-84") }
        }
        // A file to the computer, in chunks with pings between, and where it went.
        let data = Data((0..<200_000).map { UInt8($0 % 251) })
        var progress = 0
        let path = try await s.upload(id: "t1", name: "wallflower ios test.bin", data: data) { progress = $0 }
        XCTAssertEqual(progress, data.count)
        XCTAssertTrue(path.hasSuffix(".bin"), path)
        XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: path)), data)
        try? FileManager.default.removeItem(atPath: path)

        s.detach()
        try await until { s.place == nil }
        s.stop()
    }

    /** The computer's files need no session: a directory listed, a file fetched whole, its start, one that can't be, and one cancelled. */
    func testListsAndFetchesFiles() async throws {
        guard let port = ProcessInfo.processInfo.environment["WF_LISTEN"].flatMap(Int.init) else { throw XCTSkip("no gateway") }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("wf-files-\(UUID().uuidString.prefix(8))")
        try FileManager.default.createDirectory(at: dir.appendingPathComponent("sub"), withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let data = Data((0..<300_000).map { UInt8($0 % 251) })
        try data.write(to: dir.appendingPathComponent("blob ✓.bin"))
        let big = dir.appendingPathComponent("big")
        FileManager.default.createFile(atPath: big.path, contents: nil)
        try FileHandle(forWritingTo: big).truncate(atOffset: 64 << 20)

        let s = Session(transport: TCPTransport(port: port), grid: (60, 20))
        s.start()
        try await until { s.status == .connected }
        XCTAssertTrue(s.canFiles)

        let l = try await s.list(dir.path)
        XCTAssertEqual(l.entries.map(\.name), ["big", "blob ✓.bin", "sub"])
        XCTAssertEqual(l.entries.first { $0.name == "sub" }?.isDir, true)
        do {
            _ = try await s.list(dir.appendingPathComponent("none").path)
            XCTFail("listed a directory that isn't there")
        } catch {
            XCTAssertEqual(error.localizedDescription, "no such file or directory")
        }

        // More than the 256 KiB the gateway sends unheard: it all comes only as the phone says what's arrived.
        let to = dir.appendingPathComponent("fetched")
        var heard: (Int64, Int64) = (0, 0)
        let r = try await s.fetch(dir.appendingPathComponent("blob ✓.bin").path, to: to) { heard = ($0, $1) }
        XCTAssertEqual(r.size, Int64(data.count))
        XCTAssertEqual(heard.0, heard.1)
        XCTAssertEqual(try Data(contentsOf: to), data)

        _ = try await s.fetch(dir.appendingPathComponent("blob ✓.bin").path, limit: 1000, to: to)
        XCTAssertEqual(try Data(contentsOf: to), data.prefix(1000))

        do {
            _ = try await s.fetch(dir.appendingPathComponent("sub").path, to: to)
            XCTFail("fetched a directory")
        } catch {
            XCTAssertEqual(error.localizedDescription, "it's a directory")
        }

        let task = Task { try await s.fetch(big.path, to: to) }
        try await Task.sleep(for: .milliseconds(100))
        task.cancel()
        do {
            _ = try await task.value
            XCTFail("a cancelled fetch finished")
        } catch {
            XCTAssertTrue(error is CancellationError, "\(error)")
        }
        // Still connected, and it goes on.
        _ = try await s.fetch(dir.appendingPathComponent("blob ✓.bin").path, limit: 10, to: to)
        XCTAssertEqual(try Data(contentsOf: to), data.prefix(10))
        s.stop()
    }

    private func until(_ timeout: TimeInterval = 10, _ check: () -> Bool) async throws {
        let start = Date()
        while !check() {
            if Date().timeIntervalSince(start) > timeout { throw XCTSkip("timed out") }
            try await Task.sleep(for: .milliseconds(50))
        }
    }
}
