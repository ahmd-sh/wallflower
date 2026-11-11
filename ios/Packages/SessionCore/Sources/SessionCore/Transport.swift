import Foundation
import Network
import NIOCore
import NIOSSH
import NIOTransportServices

/** Why a connection can't go ahead without the user. */
public enum Blocked: Error, Equatable, Sendable {
    /** The key needs the user's Face ID or passcode. */
    case keyLocked
    /** First connection to this host: confirm its fingerprint. */
    case unknownHost(host: String, key: String)
    /** The host's key differs from the trusted one. */
    case hostKeyChanged(host: String, fingerprint: String)
    /** The computer doesn't accept this phone's key yet. */
    case notAuthorized
    /** The gateway turned the connection down before it started, e.g. for a multiplexer it doesn't have. */
    case refused(String)
    /** The computer's wallflower speaks protocol [gateway], this app [app]: the older needs updating. */
    case outdated(gateway: Int, app: Int)

    public var message: String {
        switch self {
        case .keyLocked: return "unlock your key to connect"
        case .unknownHost(_, let key): return "new host key \(HostKeys.fingerprint(openSSH: key))"
        case .hostKeyChanged(_, let f): return "host key changed: \(f)"
        case .notAuthorized: return "this phone's key isn't authorized on the computer"
        case .refused(let r): return r
        case .outdated(let g, let a): return "the computer's wallflower speaks protocol \(g), this app \(a)"
        }
    }
}

/** Hears a connection's bytes, on its own thread, and its end. */
public protocol ConnectionSink: AnyObject, Sendable {
    func received(_ bytes: Data)
    func closed(_ error: Error?)
}

/** A byte stream to a gateway. */
public final class Connection: @unchecked Sendable {
    private let channel: Channel
    private let wrap: (ByteBuffer) -> NIOAny

    init(channel: Channel, wrap: @escaping (ByteBuffer) -> NIOAny) {
        self.channel = channel
        self.wrap = wrap
    }

    /** Safe from any thread; writes go out in the order they're made. */
    public func write(_ bytes: Data) {
        var buffer = channel.allocator.buffer(capacity: bytes.count)
        buffer.writeBytes(bytes)
        let wrapped = wrap(buffer)
        channel.writeAndFlush(wrapped, promise: nil)
    }

    public func close() {
        channel.close(promise: nil)
        sshParent?.close(promise: nil)
    }

    var sshParent: Channel?
}

public protocol Transport: Sendable {
    /** Connects, its bytes going to [sink]. */
    func open(_ sink: ConnectionSink) async throws -> Connection
}

/** Plain TCP to `wallflower listen`, for development: the simulator reaches the computer's loopback as its own. */
public struct TCPTransport: Transport {
    public var host: String
    public var port: Int

    public init(host: String = "127.0.0.1", port: Int = 41777) {
        self.host = host
        self.port = port
    }

    public func open(_ sink: ConnectionSink) async throws -> Connection {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        let channel = try await NIOTSConnectionBootstrap(group: NIOTSEventLoopGroup.singleton)
            .connectTimeout(.seconds(5))
            .tcpOptions(tcp)
            .channelInitializer { $0.pipeline.addHandler(StreamHandler(sink: sink)) }
            .connect(host: host, port: port)
            .get()
        return Connection(channel: channel) { NIOAny($0) }
    }
}

/** Forwards a plain stream's bytes. */
private final class StreamHandler: ChannelInboundHandler, @unchecked Sendable {
    typealias InboundIn = ByteBuffer
    let sink: ConnectionSink
    private var failed: Error?

    init(sink: ConnectionSink) { self.sink = sink }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let buffer = unwrapInboundIn(data)
        sink.received(Data(buffer.readableBytesView))
    }

    func errorCaught(context: ChannelHandlerContext, error: Error) {
        failed = error
        context.close(promise: nil)
    }

    func channelInactive(context: ChannelHandlerContext) {
        sink.closed(failed)
    }
}

/** Connects with the phone's key and runs the gateway in an exec channel. */
public struct SSHTransport: Transport {
    public let host: Host
    public let key: PhoneKey
    public let hostKeys: HostKeys

    public init(host: Host, key: PhoneKey, hostKeys: HostKeys) {
        self.host = host
        self.key = key
        self.hostKeys = hostKeys
    }

    public func open(_ sink: ConnectionSink) async throws -> Connection {
        guard let privateKey = key.unlockedKey() else { throw Blocked.keyLocked }
        let check = HostCheck(host: host.address, hostKeys: hostKeys)
        let auth = KeyAuth(user: host.user, key: privateKey)
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        let parent: Channel
        do {
            parent = try await NIOTSConnectionBootstrap(group: NIOTSEventLoopGroup.singleton)
                .connectTimeout(.seconds(10))
                .tcpOptions(tcp)
                .channelInitializer { channel in
                    channel.eventLoop.makeCompletedFuture {
                        let ssh = NIOSSHHandler(
                            role: .client(.init(userAuthDelegate: auth, serverAuthDelegate: check)),
                            allocator: channel.allocator,
                            inboundChildChannelInitializer: nil
                        )
                        try channel.pipeline.syncOperations.addHandlers([ssh, CloseOnError()])
                    }
                }
                .connect(host: host.address, port: host.port)
                .get()
        } catch {
            throw error
        }
        // The handshake (keys, auth, the channel) gets as long as the connect did; a network that goes away meanwhile doesn't leave it waiting.
        let timeout = parent.eventLoop.scheduleTask(in: .seconds(10)) { parent.close(promise: nil) }
        defer { timeout.cancel() }
        do {
            let child: Channel = try await withTaskCancellationHandler {
                try await parent.eventLoop.flatSubmit {
                let promise = parent.eventLoop.makePromise(of: Channel.self)
                do {
                    let ssh = try parent.pipeline.syncOperations.handler(type: NIOSSHHandler.self)
                    ssh.createChannel(promise) { child, type in
                        guard type == .session else { return child.eventLoop.makeFailedFuture(SSHFailure.wrongChannel) }
                        return child.eventLoop.makeCompletedFuture {
                            try child.pipeline.syncOperations.addHandler(ExecHandler(command: host.command, sink: sink))
                        }
                    }
                } catch {
                    promise.fail(error)
                }
                return promise.futureResult
                }.get()
            } onCancel: {
                parent.close(promise: nil) // stopped while connecting
            }
            let conn = Connection(channel: child) { NIOAny(SSHChannelData(type: .channel, data: .byteBuffer($0))) }
            conn.sshParent = parent
            return conn
        } catch {
            parent.close(promise: nil)
            if let b = check.blocked { throw b }
            if auth.rejected { throw Blocked.notAuthorized }
            throw error
        }
    }
}

enum SSHFailure: Error, LocalizedError {
    case wrongChannel
    case execRefused

    var errorDescription: String? {
        switch self {
        case .wrongChannel: return "unexpected ssh channel"
        case .execRefused: return "the computer wouldn't run wallflower"
        }
    }
}

private final class CloseOnError: ChannelInboundHandler, Sendable {
    typealias InboundIn = Any

    func errorCaught(context: ChannelHandlerContext, error: Error) {
        context.close(promise: nil)
    }
}

/** Pins host keys on first use, once the user confirms. */
private final class HostCheck: NIOSSHClientServerAuthenticationDelegate, @unchecked Sendable {
    let host: String
    let hostKeys: HostKeys
    var blocked: Blocked?

    init(host: String, hostKeys: HostKeys) {
        self.host = host
        self.hostKeys = hostKeys
    }

    func validateHostKey(hostKey: NIOSSHPublicKey, validationCompletePromise: EventLoopPromise<Void>) {
        let key = String(openSSHPublicKey: hostKey)
        switch hostKeys.check(host: host, key: key) {
        case .trusted:
            validationCompletePromise.succeed(())
        case .unknown:
            blocked = .unknownHost(host: host, key: key)
            validationCompletePromise.fail(blocked!)
        case .changed:
            blocked = .hostKeyChanged(host: host, fingerprint: HostKeys.fingerprint(openSSH: key))
            validationCompletePromise.fail(blocked!)
        }
    }
}

/** Offers the phone's key, once. */
private final class KeyAuth: NIOSSHClientUserAuthenticationDelegate, @unchecked Sendable {
    let user: String
    let key: NIOSSHPrivateKey
    var offered = false
    var rejected = false

    init(user: String, key: NIOSSHPrivateKey) {
        self.user = user
        self.key = key
    }

    func nextAuthenticationType(availableMethods: NIOSSHAvailableUserAuthenticationMethods, nextChallengePromise: EventLoopPromise<NIOSSHUserAuthenticationOffer?>) {
        guard !offered, availableMethods.contains(.publicKey) else {
            rejected = true
            nextChallengePromise.fail(Blocked.notAuthorized)
            return
        }
        offered = true
        nextChallengePromise.succeed(NIOSSHUserAuthenticationOffer(username: user, serviceName: "", offer: .privateKey(.init(privateKey: key))))
    }
}

/** Runs the gateway on the exec channel, and relays its stdout. */
private final class ExecHandler: ChannelDuplexHandler, @unchecked Sendable {
    typealias InboundIn = SSHChannelData
    typealias InboundOut = ByteBuffer
    typealias OutboundIn = SSHChannelData
    typealias OutboundOut = SSHChannelData

    let command: String
    let sink: ConnectionSink
    private var failed: Error?

    init(command: String, sink: ConnectionSink) {
        self.command = command
        self.sink = sink
    }

    func handlerAdded(context: ChannelHandlerContext) {
        context.channel.setOption(ChannelOptions.allowRemoteHalfClosure, value: true).whenFailure { [weak self] error in
            self?.failed = error
            context.close(promise: nil)
        }
    }

    func channelActive(context: ChannelHandlerContext) {
        // The computer's authorized_keys forces `wallflower serve` for this key;
        // the command matters only for keys without that restriction.
        let exec = SSHChannelRequestEvent.ExecRequest(command: command, wantReply: true)
        context.triggerUserOutboundEvent(exec, promise: nil)
        context.fireChannelActive()
    }

    func userInboundEventTriggered(context: ChannelHandlerContext, event: Any) {
        switch event {
        case is ChannelSuccessEvent:
            break
        case is ChannelFailureEvent:
            failed = SSHFailure.execRefused
            context.close(promise: nil)
        case let status as SSHChannelRequestEvent.ExitStatus:
            if status.exitStatus != 0 { failed = failed ?? GatewayExit(status: status.exitStatus) }
        case ChannelEvent.inputClosed:
            context.close(promise: nil)
        default:
            context.fireUserInboundEventTriggered(event)
        }
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let message = unwrapInboundIn(data)
        guard case .byteBuffer(let buffer) = message.data, message.type == .channel else { return }
        sink.received(Data(buffer.readableBytesView))
    }

    func errorCaught(context: ChannelHandlerContext, error: Error) {
        failed = error
        context.close(promise: nil)
    }

    func channelInactive(context: ChannelHandlerContext) {
        sink.closed(failed)
    }
}

struct GatewayExit: Error, LocalizedError {
    let status: Int
    var errorDescription: String? { "the gateway exited (\(status))" }
}
