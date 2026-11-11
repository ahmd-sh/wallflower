import Foundation
import XCTest
@testable import Wire

final class FramesTests: XCTestCase {
    private func golden(_ name: String) throws -> Data {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<6 { root.deleteLastPathComponent() }
        return try Data(contentsOf: root.appendingPathComponent("testdata").appendingPathComponent(name))
    }

    func testEncodesClientFramesLikeTheGateway() throws {
        var out = Data()
        let messages: [ClientMessage] = [
            .hello(ClientHello(cols: 56, rows: 20)),
            .hello(ClientHello(cols: 56, rows: 20, session: "o")),
            .hello(ClientHello(cols: 56, rows: 20, away: true)),
            .hello(ClientHello(cols: 56, rows: 20, mux: "rex", session: "work")),
            .resize(cols: 70, rows: 25),
            .open(pane: "%1"),
            .open(pane: "%1", history: 500),
            .close(pane: "%1"),
            .keys(pane: "%1", keys: ["C-c", "Up", "M-Enter"]),
            .keys(pane: "%1", keys: ["prefix", "o"], bindings: true),
            .submit(pane: "%1", text: "fix the tests ✓", enter: true, clear: ["A", "C-u", "C-u", "C-u"]),
            .submit(pane: "%1", text: "two\nlines"),
            .select(window: "@2"),
            .select(pane: "%3"),
            .focus(pane: "%2"),
            .split(pane: "%2"),
            .new,
            .rename(window: "@2", name: "logs ✓"),
            .kill(pane: "%2"),
            .kill(window: "@2"),
            .read(pane: "%2", on: true),
            .read(pane: "%2", on: false),
            .picture(pane: "%2", id: "u2.0"),
            .upload(id: "u1", name: "PXL 1 ✓.jpg", size: 3),
            .ping(id: 3),
            .away(on: true),
            .away(on: false),
            .sessions,
            .switchTo(mux: "rex", session: "session:1"),
            .newSession(mux: "tmux", name: "work ✓"),
            .newSession(mux: "tmux"),
            .renameSession(mux: "tmux", session: "$2", name: "play"),
            .killSession(mux: "tmux", session: "$2"),
            .detach,
            .list(id: "l1", path: "~/dev ✓"),
            .list(id: "l2", path: ""),
            .fetch(id: "f1", path: "/Users/me/shot.png"),
            .fetch(id: "f2", path: "~/log.txt", limit: 65536),
            .got(id: "f1", size: 65536),
            .cancel(id: "f1"),
        ]
        for m in messages { out.append(FrameEncoder.message(m)) }
        out.append(FrameEncoder.data(pane: "%1", bytes: Data("\u{1b}[<64;10;5M".utf8)))
        out.append(FrameEncoder.upload(id: "u1", bytes: Data([0xff, 0xd8, 0xff, 0]).prefix(3)))

        let want = try golden("client.bin")
        XCTAssertEqual(String(decoding: want, as: UTF8.self), String(decoding: out, as: UTF8.self))
        XCTAssertEqual(want, out)
    }

    func testDecodesServerFramesFromTheGateway() throws {
        let bytes = try golden("server.bin")
        for chunk in [bytes.count, 1, 7, 4096] {
            try decodesServerFrames(bytes, chunk: chunk)
        }
    }

    /// The golden server frames, as they come in pieces of `chunk` bytes.
    private func decodesServerFrames(_ bytes: Data, chunk: Int) throws {
        var frames = try decodeAll(bytes, chunk: chunk)[...]
        func frame(file: StaticString = #filePath, line: UInt = #line) throws -> Frame {
            try XCTUnwrap(frames.popFirst(), "chunk \(chunk)", file: file, line: line)
        }
        func message(file: StaticString = #filePath, line: UInt = #line) throws -> ServerMessage {
            let f = try frame(file: file, line: line)
            guard case let .message(m) = f else { throw Failure("chunk \(chunk): \(f) isn't a message") }
            return m
        }

        XCTAssertEqual(.hello(ServerHello(v: VERSION, uploads: "/Users/me/Library/Caches/wallflower/uploads", machine: "vishingstar", can: [ServerHello.FILES])), try message())
        XCTAssertEqual(.attached(Attached(mux: Mux(name: "tmux", version: "3.8", can: [Mux.BINDINGS]))), try message())
        guard case let .state(state) = try message() else { throw Failure("not state") }
        XCTAssertEqual("o", state.name)
        XCTAssertEqual(1, state.windows.count)
        let panes = state.windows[0].panes
        XCTAssertEqual(3, panes.count)
        XCTAssertEqual(
            Pane(
                id: "%2", index: 0, active: false, cols: 28, rows: 20, alt: true, title: "✳ Fix tests", path: "/Users/ahmedshaikh/dev/wallflower",
                command: "claude", program: "claude", view: true, activity: Pane.WAITING, since: 1792209270006
            ),
            panes[0]
        )
        XCTAssertEqual(Pane(id: "%3", index: 1, active: true, cols: 28, rows: 20, command: "zsh", program: "zsh"), panes[1])
        XCTAssertEqual(Pane(id: "%4", index: 2, active: false, cols: 28, rows: 20, command: "htop"), panes[2])
        XCTAssertEqual(.reset(Reset(pane: "%2", cols: 28, rows: 20)), try message())

        guard case let .data(pane, data) = try frame() else { throw Failure("not data") }
        XCTAssertEqual("%2", pane)
        XCTAssertEqual("\u{1b}[0m\u{1b}[H\u{1b}[2J\u{1b}[42mgreen\u{1b}[49m ✓\r\n", String(decoding: data, as: UTF8.self))

        XCTAssertEqual(.error(ErrorMessage(message: "tmux: bad id \"x\"")), try message())
        XCTAssertEqual(.pong(Pong(id: 4)), try message())
        XCTAssertEqual(
            .entries(
                Entries(
                    pane: "%2", reset: true, more: true,
                    entries: [
                        WireEntry(id: "u1", from: "user", kind: "said", text: "fix the tests ✓"),
                        WireEntry(id: "a0", from: "program", kind: "thought", text: "the tests"),
                        WireEntry(id: "a1", from: "program", kind: "step", text: "go test ./...", name: "Bash", status: "done", output: "ok", outputCut: 1200),
                        WireEntry(id: "a2", from: "program", kind: "step", text: "rm -rf /", name: "Bash", status: "failed", output: "denied"),
                        WireEntry(
                            id: "a4", from: "program", kind: "step", text: "main.go", name: "Edit", status: "done", output: "updated",
                            diff: "@@ -3,2 +3,2 @@\n x\n-old\n+new ✓", diffCut: 12
                        ),
                        WireEntry(id: "a5", from: "program", kind: "step", text: "ls", name: "Bash", status: "running"),
                        WireEntry(id: "u2.0", from: "user", kind: "picture", mime: "image/png", size: 3),
                        WireEntry(id: "a3", from: "program", kind: "said", text: "**Done.** All `3` pass."),
                    ],
                    context: 17967
                )
            ),
            try message()
        )
        XCTAssertEqual(.entries(Entries(pane: "%2", entries: [WireEntry(id: "n1", from: "user", kind: "note", text: "[Request interrupted by user]")])), try message())
        XCTAssertEqual(.entries(Entries(pane: "%2", context: 0)), try message())
        XCTAssertEqual(.entries(Entries(pane: "%2", reset: true, error: "the conversation ended")), try message())
        XCTAssertEqual(.notification(ProgramNotification(pane: "%2", title: "Claude Code", body: "Claude needs your permission to use Bash ✓")), try message())
        XCTAssertEqual(.notification(ProgramNotification(pane: "%3", body: "build done")), try message())
        XCTAssertEqual(.notification(ProgramNotification(pane: "%4", bell: true)), try message())
        guard case let .picture(id, picture) = try frame() else { throw Failure("not a picture") }
        XCTAssertEqual("u2.0", id)
        XCTAssertEqual([0x89, UInt8(ascii: "P"), UInt8(ascii: "N")], [UInt8](picture))
        guard case let .picture(goneID, gone) = try frame() else { throw Failure("not a picture") }
        XCTAssertEqual("gone", goneID)
        XCTAssertEqual(0, gone.count)
        XCTAssertEqual(.uploaded(Uploaded(id: "u1", path: "/Users/me/Library/Caches/wallflower/uploads/PXL-1.jpg")), try message())
        XCTAssertEqual(.uploaded(Uploaded(id: "u2", error: "too big")), try message())
        XCTAssertEqual(.detached(Detached()), try message())
        XCTAssertEqual(.detached(Detached(reason: "tmux exited")), try message())
        XCTAssertEqual(
            .sessions(
                SessionList(muxes: [
                    MuxSessions(name: "rex"),
                    MuxSessions(
                        name: "tmux", running: true, sessions: [SessionInfo(id: "$1", name: "o", windows: 3), SessionInfo(id: "$2", name: "work ✓", windows: 1)],
                        can: [MuxSessions.CAN_NEW, MuxSessions.CAN_RENAME, MuxSessions.CAN_KILL]
                    ),
                    MuxSessions(name: "zellij", can: [MuxSessions.CAN_NEW], error: "zellij: permission denied"),
                ])
            ),
            try message()
        )
        XCTAssertEqual(
            .listing(
                Listing(
                    id: "l1", path: "/Users/me/dev ✓",
                    entries: [
                        FileEntry(name: ".git", kind: FileEntry.DIR, mtime: 1792209270006, hidden: true),
                        FileEntry(name: "latest", kind: FileEntry.DIR, link: true, mtime: 1792209270007),
                        FileEntry(name: "notes ✓.md", kind: FileEntry.FILE, size: 1234, mtime: 1792209270008),
                        FileEntry(name: "wf.sock", kind: FileEntry.OTHER),
                    ],
                    more: true
                )
            ),
            try message()
        )
        XCTAssertEqual(.listing(Listing(id: "l2", path: "/Users/me/Library/Mail", error: "operation not permitted")), try message())
        XCTAssertEqual(.file(FileReply(id: "f1", path: "/Users/me/shot.png", size: 3)), try message())
        guard case let .file(fileID, fileBytes) = try frame() else { throw Failure("not a file frame") }
        XCTAssertEqual("f1", fileID)
        XCTAssertEqual([0x89, UInt8(ascii: "P"), UInt8(ascii: "N")], [UInt8](fileBytes))
        XCTAssertEqual(.file(FileReply(id: "f2", path: "/Users/me/log.txt", error: "permission denied")), try message())
        XCTAssertEqual(.bye(Bye(reason: "tmux exited")), try message())
        XCTAssertTrue(frames.isEmpty, "chunk \(chunk)")
    }

    func testUnknownMessageTypesAreSkippable() throws {
        var d = FrameDecoder()
        d.append(Data(frame(FRAME_JSON, Array(#"{"t":"from-the-future","x":1}"#.utf8))))
        guard case .unknown = try d.next() else { return XCTFail("not unknown") }
    }

    func testFramesOfANewerTypeArePassedOver() throws {
        var d = FrameDecoder()
        d.append(Data(frame(9, [0]) + frame(FRAME_DATA, [2, UInt8(ascii: "%"), UInt8(ascii: "1"), UInt8(ascii: "x")])))
        guard case let .data(pane, _) = try d.next() else { return XCTFail("not data") }
        XCTAssertEqual("%1", pane)
    }

    func testAMessageItCantReadIsPassedOverAndSaidSo() throws {
        var d = FrameDecoder()
        d.append(Data(frame(FRAME_JSON, Array(#"{"t":"reset","pane":1}"#.utf8))))
        guard case let .unknown(_, problem) = try d.next() else { return XCTFail("not unknown") }
        XCTAssertNotNil(problem)
    }

    func testRejectsBadFrames() {
        let cases: [(String, [UInt8])] = [
            ("zero length", [0, 0, 0, 0]),
            ("truncated pane", frame(FRAME_DATA, [5, UInt8(ascii: "x")])),
        ]
        for (name, bytes) in cases {
            var d = FrameDecoder()
            d.append(Data(bytes))
            XCTAssertThrowsError(try d.next(), name) { XCTAssertTrue($0 is ProtocolError, name) }
        }
    }

    private func frame(_ type: UInt8, _ payload: [UInt8]) -> [UInt8] {
        let n = payload.count + 1
        return [UInt8(truncatingIfNeeded: n >> 24), UInt8(truncatingIfNeeded: n >> 16), UInt8(truncatingIfNeeded: n >> 8), UInt8(truncatingIfNeeded: n), type] + payload
    }

    /// Every frame in `bytes`, appended `chunk` bytes at a time.
    private func decodeAll(_ bytes: Data, chunk: Int) throws -> [Frame] {
        var d = FrameDecoder()
        var frames: [Frame] = []
        var at = bytes.startIndex
        while at < bytes.endIndex {
            let end = min(at + chunk, bytes.endIndex)
            d.append(bytes[at..<end])
            at = end
            while let f = try d.next() { frames.append(f) }
        }
        return frames
    }

    private struct Failure: Error, CustomStringConvertible {
        let description: String
        init(_ description: String) { self.description = description }
    }
}
