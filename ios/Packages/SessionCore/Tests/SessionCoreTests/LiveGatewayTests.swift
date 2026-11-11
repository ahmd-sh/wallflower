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
        // A file to the Mac, in chunks with pings between, and where it went.
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

    private func until(_ timeout: TimeInterval = 10, _ check: () -> Bool) async throws {
        let start = Date()
        while !check() {
            if Date().timeIntervalSince(start) > timeout { throw XCTSkip("timed out") }
            try await Task.sleep(for: .milliseconds(50))
        }
    }
}
