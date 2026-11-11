import CryptoKit
import NIOCore
import NIOSSH
import NIOTransportServices
import XCTest
@testable import SessionCore

/** Reading the code `wallflower pair` shows, and the exchange it starts, against an ssh server of the test's own that answers as claim does. */
final class PairingTests: XCTestCase {
    private let seed = Data((0 ..< 32).map { UInt8($0 * 7 % 256) })
    private let ed25519 = PhoneKey.sshStrings([Data("ssh-ed25519".utf8), Curve25519.Signing.PrivateKey().publicKey.rawRepresentation])

    func testReadsACode() throws {
        let p = try XCTUnwrap(Pairing(url: code("h=100.101.102.103&u=marwan&p=2222&n=Marwan%27s%20Laptop&k=\(b64url(ed25519))&c=\(b64url(seed))")))
        XCTAssertEqual(p.address, "100.101.102.103")
        XCTAssertEqual(p.port, 2222)
        XCTAssertEqual(p.user, "marwan")
        XCTAssertEqual(p.machine, "Marwan's Laptop")
        XCTAssertEqual(p.hostKey, "ssh-ed25519 " + ed25519.base64EncodedString())
        XCTAssertEqual(p.seed, seed)
        XCTAssertEqual(p.called, "Marwan's Laptop")
        XCTAssertEqual(p.host, Host(address: "100.101.102.103", port: 2222, user: "marwan", machine: "Marwan's Laptop"))
    }

    /** The host key as the transport's check writes the key the server shows, so a code's pin is trusted the next time. */
    func testHostKeyIsAsTheTransportSeesIt() throws {
        let host = NIOSSHPrivateKey(ed25519Key: Curve25519.Signing.PrivateKey())
        let p256 = P256.Signing.PrivateKey()
        let ecdsa = PhoneKey.sshStrings([Data("ecdsa-sha2-nistp256".utf8), Data("nistp256".utf8), p256.publicKey.x963Representation])
        let fromWire = try XCTUnwrap(Pairing(url: code("h=h&u=u&k=\(b64url(Self.wire(host.publicKey)))&c=\(b64url(seed))")))
        XCTAssertEqual(fromWire.hostKey, String(openSSHPublicKey: host.publicKey))
        let p = try XCTUnwrap(Pairing(url: code("h=h&u=u&k=\(b64url(ecdsa))&c=\(b64url(seed))")))
        XCTAssertEqual(p.hostKey, String(openSSHPublicKey: NIOSSHPrivateKey(p256Key: p256).publicKey))
        XCTAssertTrue(p.hostKey.hasPrefix("ecdsa-sha2-nistp256 "))
    }

    func testDefaults() throws {
        let p = try XCTUnwrap(Pairing(url: code("h=100.64.0.1&u=me&k=\(b64url(ed25519))&c=\(b64url(seed))")))
        XCTAssertEqual(p.port, 22)
        XCTAssertNil(p.machine)
        XCTAssertEqual(p.called, "100.64.0.1")
        XCTAssertEqual(Pairing(url: code("h=h&u=u&n=a+b&k=\(b64url(ed25519))&c=\(b64url(seed))"))?.machine, "a+b") // the gateway writes spaces as %20
        XCTAssertNil(Pairing(url: code("h=h&u=u&n=&k=\(b64url(ed25519))&c=\(b64url(seed))"))?.machine)
    }

    func testRejectsWhatIsntACode() {
        let k = "k=\(b64url(ed25519))"
        let c = "c=\(b64url(seed))"
        let bad = [
            "https://pair?h=h&u=u&\(k)&\(c)",
            "wallflower://pairing?h=h&u=u&\(k)&\(c)",
            "wallflower://pair?u=u&\(k)&\(c)",
            "wallflower://pair?h=&u=u&\(k)&\(c)",
            "wallflower://pair?h=h&\(k)&\(c)",
            "wallflower://pair?h=h&u=u&\(c)",
            "wallflower://pair?h=h&u=u&\(k)",
            "wallflower://pair?h=h&u=u&\(k)&c=\(b64url(seed.prefix(31)))",
            "wallflower://pair?h=h&u=u&\(k)&c=\(seed.base64EncodedString())",
            "wallflower://pair?h=h&u=u&k=\(b64url(Data("not a key".utf8)))&\(c)",
            "wallflower://pair?h=h&u=u&k=\(b64url(ed25519.prefix(20)))&\(c)",
            "wallflower://pair?h=h&u=u&k=%%%&\(c)",
            "wallflower://pair?h=h&u=u&p=0&\(k)&\(c)",
            "wallflower://pair?h=h&u=u&p=65536&\(k)&\(c)",
            "wallflower://pair?h=h&u=u&p=ssh&\(k)&\(c)",
        ]
        for s in bad {
            guard let url = URL(string: s) else { continue }
            XCTAssertNil(Pairing(url: url), s)
        }
    }

    func testSaysWhatTheGatewaySaid() {
        XCTAssertEqual(Pairing.said("wallflower: this code has been used, or stopped: run wallflower pair for a new one\n", status: 1), "This code has been used, or stopped: run wallflower pair for a new one.")
        XCTAssertEqual(Pairing.said("a warning\nwallflower: no key came\n\n", status: 1), "No key came.")
        XCTAssertEqual(Pairing.said("", status: 2), "The computer wouldn't take this phone's key (2).")
    }

    func testPairs() async throws {
        let computer = FakeComputer()
        let port = try await computer.start()
        defer { computer.stop() }
        try await computer.pairing(port: port).claim(sending: "ecdsa-sha2-nistp256 AAAA wallflower@iPhone")
        XCTAssertEqual(computer.received, "ecdsa-sha2-nistp256 AAAA wallflower@iPhone\n")
    }

    func testUsedCodeSaysSo() async throws {
        let computer = FakeComputer()
        computer.status = 1
        computer.stderr = "wallflower: this code has been used, or stopped: run wallflower pair for a new one\n"
        let port = try await computer.start()
        defer { computer.stop() }
        await expect(.refused("This code has been used, or stopped: run wallflower pair for a new one.")) {
            try await computer.pairing(port: port).claim(sending: "key")
        }
    }

    func testExpiredCodeIsntLetIn() async throws {
        let computer = FakeComputer()
        computer.lets = false
        let port = try await computer.start()
        defer { computer.stop() }
        await expect(.used) { try await computer.pairing(port: port).claim(sending: "key") }
    }

    func testOtherComputer() async throws {
        let computer = FakeComputer()
        let port = try await computer.start()
        defer { computer.stop() }
        let other = PhoneKey.sshStrings([Data("ssh-ed25519".utf8), Curve25519.Signing.PrivateKey().publicKey.rawRepresentation])
        await expect(.wrongComputer) { try await computer.pairing(port: port, hostKey: other).claim(sending: "key") }
        XCTAssertNil(computer.received)
    }

    func testNothingThere() async throws {
        let computer = FakeComputer()
        let port = try await computer.start()
        computer.stop()
        try await Task.sleep(for: .milliseconds(100))
        await expect(.unreachable) { try await computer.pairing(port: port).claim(sending: "key") }
    }

    private func code(_ query: String) -> URL { URL(string: "wallflower://pair?" + query)! }

    fileprivate static func wire(_ key: NIOSSHPublicKey) -> Data {
        let parts = String(openSSHPublicKey: key).split(separator: " ")
        return Data(base64Encoded: String(parts[1]))!
    }

    private func expect(_ want: PairingFailed, file: StaticString = #filePath, line: UInt = #line, _ run: () async throws -> Void) async {
        do {
            try await run()
            XCTFail("paired", file: file, line: line)
        } catch {
            XCTAssertEqual(error as? PairingFailed, want, file: file, line: line)
        }
    }
}

private func b64url(_ d: Data) -> String {
    d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
}

/** An ssh server on loopback that lets in the code's key ([lets]) and, for an exec, reads a line to its EOF and answers with [status] and [stderr], as claim does. */
private final class FakeComputer: @unchecked Sendable {
    let hostKey = NIOSSHPrivateKey(ed25519Key: Curve25519.Signing.PrivateKey())
    let codeKey = Curve25519.Signing.PrivateKey()
    var lets = true
    var status = 0
    var stderr = ""
    private let lock = NSLock()
    private var heard: String?
    private var listener: Channel?

    var received: String? { lock.withLock { heard } }

    func hear(_ s: String) { lock.withLock { heard = s } }

    func pairing(port: Int, hostKey: Data? = nil) -> Pairing {
        let seed = codeKey.rawRepresentation
        let k = hostKey ?? PairingTests.wire(self.hostKey.publicKey)
        return Pairing(url: URL(string: "wallflower://pair?h=127.0.0.1&u=me&p=\(port)&n=test&k=\(b64url(k))&c=\(b64url(seed))")!)!
    }

    func start() async throws -> Int {
        let auth = OnlyKey(key: lets ? NIOSSHPrivateKey(ed25519Key: codeKey).publicKey : nil)
        let hostKey = self.hostKey
        let channel = try await NIOTSListenerBootstrap(group: NIOTSEventLoopGroup.singleton)
            .childChannelInitializer { channel in
                channel.eventLoop.makeCompletedFuture {
                    let ssh = NIOSSHHandler(
                        role: .server(.init(hostKeys: [hostKey], userAuthDelegate: auth, globalRequestDelegate: nil, banner: nil)),
                        allocator: channel.allocator,
                        inboundChildChannelInitializer: { child, _ in
                            child.eventLoop.makeCompletedFuture { try child.pipeline.syncOperations.addHandler(Claim(computer: self)) }
                        }
                    )
                    try channel.pipeline.syncOperations.addHandler(ssh)
                }
            }
            .bind(host: "127.0.0.1", port: 0)
            .get()
        listener = channel
        return try XCTUnwrap(channel.localAddress?.port)
    }

    func stop() { listener?.close(promise: nil) }
}

private final class OnlyKey: NIOSSHServerUserAuthenticationDelegate, @unchecked Sendable {
    let key: NIOSSHPublicKey?

    init(key: NIOSSHPublicKey?) { self.key = key }

    var supportedAuthenticationMethods: NIOSSHAvailableUserAuthenticationMethods { .publicKey }

    func requestReceived(request: NIOSSHUserAuthenticationRequest, responsePromise: EventLoopPromise<NIOSSHUserAuthenticationOutcome>) {
        guard case .publicKey(let k) = request.request, k.publicKey == key else {
            responsePromise.succeed(.failure)
            return
        }
        responsePromise.succeed(.success)
    }
}

private final class Claim: ChannelInboundHandler, @unchecked Sendable {
    typealias InboundIn = SSHChannelData
    typealias OutboundOut = SSHChannelData

    let computer: FakeComputer
    private var line = ""

    init(computer: FakeComputer) { self.computer = computer }

    func handlerAdded(context: ChannelHandlerContext) {
        context.channel.setOption(ChannelOptions.allowRemoteHalfClosure, value: true).whenFailure { _ in context.close(promise: nil) }
    }

    func userInboundEventTriggered(context: ChannelHandlerContext, event: Any) {
        switch event {
        case let exec as SSHChannelRequestEvent.ExecRequest:
            if exec.wantReply { context.triggerUserOutboundEvent(ChannelSuccessEvent(), promise: nil) }
        case ChannelEvent.inputClosed:
            computer.hear(line)
            if computer.status == 0 { write(context, .channel, "paired\n") }
            if !computer.stderr.isEmpty { write(context, .stdErr, computer.stderr) }
            context.triggerUserOutboundEvent(SSHChannelRequestEvent.ExitStatus(exitStatus: computer.status), promise: nil)
            context.close(promise: nil)
        default:
            context.fireUserInboundEventTriggered(event)
        }
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let message = unwrapInboundIn(data)
        guard case .byteBuffer(let b) = message.data, message.type == .channel else { return }
        line += String(buffer: b)
    }

    private func write(_ context: ChannelHandlerContext, _ type: SSHChannelData.DataType, _ s: String) {
        context.writeAndFlush(wrapOutboundOut(SSHChannelData(type: type, data: .byteBuffer(context.channel.allocator.buffer(string: s)))), promise: nil)
    }
}
