import CryptoKit
import Foundation
import NIOCore
import NIOSSH

/**
 * The code `wallflower pair` shows, as a QR code of
 * `wallflower://pair?h=<address>&u=<user>[&p=<port>][&n=<name>]&k=<host key>&c=<seed>`:
 * where to reach the computer, the key it shows (so the phone needn't ask
 * whether to trust it), and a one-time key it lets in for a few minutes to
 * do one thing, take this phone's key ([claim]).
 */
public struct Pairing: Equatable, Sendable {
    public let address: String
    public let port: Int
    public let user: String
    /** The computer's name, if the code says it. */
    public let machine: String?
    /** The computer's host key, as HostKeys keeps it: "ssh-ed25519 <base64>". */
    public let hostKey: String
    /** The one-time key's Ed25519 seed. */
    public let seed: Data

    /** The code a URL holds; nil if it isn't one, or something it needs is missing or malformed. */
    public init?(url: URL) {
        guard let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              parts.scheme?.lowercased() == "wallflower", parts.host?.lowercased() == "pair" else { return nil }
        func value(_ name: String) -> String? {
            parts.queryItems?.first { $0.name == name }?.value?.trimmingCharacters(in: .whitespaces)
        }
        guard let address = value("h"), !address.isEmpty,
              let user = value("u"), !user.isEmpty,
              let blob = value("k").flatMap(Self.base64URL), let type = Self.firstString(blob),
              let key = try? NIOSSHPublicKey(openSSHPublicKey: type + " " + blob.base64EncodedString()),
              let seed = value("c").flatMap(Self.base64URL), seed.count == 32 else { return nil }
        var port = 22
        if let p = value("p") {
            guard let n = Int(p), (1 ... 65535).contains(n) else { return nil }
            port = n
        }
        self.address = address
        self.port = port
        self.user = user
        self.machine = value("n").flatMap { $0.isEmpty ? nil : $0 }
        self.hostKey = String(openSSHPublicKey: key)
        self.seed = seed
    }

    /** What to call the computer: its name, or else its address. */
    public var called: String { machine ?? address }

    /** The computer, as the app keeps it. */
    public var host: Host { Host(address: address, port: port, user: user, machine: machine) }

    /** Unpadded base64url, as the code's bytes are written. */
    static func base64URL(_ s: String) -> Data? {
        guard !s.isEmpty, s.unicodeScalars.allSatisfy({ $0.isASCII && (CharacterSet.alphanumerics.contains($0) || $0 == "-" || $0 == "_") }) else { return nil }
        let b = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        return Data(base64Encoded: b + String(repeating: "=", count: (4 - b.count % 4) % 4))
    }

    /** The first string of SSH's wire format: a key's type. */
    private static func firstString(_ blob: Data) -> String? {
        let bytes = [UInt8](blob)
        guard bytes.count >= 4 else { return nil }
        let n = bytes[0..<4].reduce(0) { $0 << 8 | Int($1) }
        guard n > 0, bytes.count >= 4 + n else { return nil }
        return String(bytes: bytes[4 ..< 4 + n], encoding: .utf8)
    }
}

/** Why pairing didn't work, said plainly. */
public enum PairingFailed: Error, Equatable, LocalizedError {
    /** Nothing answered at the code's address. */
    case unreachable
    /** Something answered, but not with the key the code named. */
    case wrongComputer
    /** The computer doesn't let the code's key in any more: it ran out, or was used. */
    case used
    /** The computer wouldn't take this phone's key, saying why. */
    case refused(String)
    /** Anything else, as it went. */
    case failed(String)

    public var message: String {
        switch self {
        case .unreachable: return "The computer couldn't be reached over Tailscale. Is Tailscale on, on this phone and the computer?"
        case .wrongComputer: return "This isn't the computer the code came from."
        case .used: return "The code has run out or been used: run wallflower pair on the computer for a new one."
        case .refused(let why): return why
        case .failed(let why): return "Couldn't pair: \(why)."
        }
    }

    public var errorDescription: String? { message }
}

public extension Pairing {
    /**
     * Signs in with the code's key and hands the computer's `wallflower
     * claim` this phone's [authorizedKey] line, which it lets in from then
     * on, to run `wallflower serve`. Throws a PairingFailed.
     */
    func claim(sending authorizedKey: String) async throws {
        let check = PinnedHost(key: hostKey)
        let auth: KeyAuth
        do {
            auth = KeyAuth(user: user, key: NIOSSHPrivateKey(ed25519Key: try Curve25519.Signing.PrivateKey(rawRepresentation: seed)))
        } catch {
            throw PairingFailed.failed("the code's key doesn't read")
        }
        let parent: Channel
        do {
            parent = try await sshConnect(to: address, port: port, auth: auth, check: check)
        } catch {
            throw PairingFailed.unreachable
        }
        defer { parent.close(promise: nil) }
        let done = parent.eventLoop.makePromise(of: Claimed.self)
        do {
            _ = try await sshSession(on: parent) { ClaimHandler(line: authorizedKey, done: done) }
        } catch {
            done.fail(error)
            if check.wrong { throw PairingFailed.wrongComputer }
            if auth.rejected { throw PairingFailed.used }
            throw PairingFailed.failed((error as? LocalizedError)?.errorDescription ?? String(describing: error))
        }
        // Claim reads a line and answers at once: a computer that doesn't, in as long as the handshake gets, isn't going to.
        let timeout = parent.eventLoop.scheduleTask(in: .seconds(10)) { parent.close(promise: nil) }
        defer { timeout.cancel() }
        let claimed: Claimed
        do {
            claimed = try await withTaskCancellationHandler {
                try await done.futureResult.get()
            } onCancel: {
                parent.close(promise: nil)
            }
        } catch {
            throw PairingFailed.failed((error as? LocalizedError)?.errorDescription ?? String(describing: error))
        }
        switch claimed.status {
        case 0?: return
        case let status?: throw PairingFailed.refused(Self.said(claimed.stderr, status: status))
        case nil: throw PairingFailed.failed("the computer didn't answer")
        }
    }

    /** What the gateway said on stderr as it failed, as a sentence: its last line, without its "wallflower: ". */
    internal static func said(_ stderr: String, status: Int) -> String {
        let last = stderr.split(whereSeparator: \.isNewline).last { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        var why = last.map { String($0).trimmingCharacters(in: .whitespaces) } ?? ""
        if why.hasPrefix("wallflower:") { why = why.dropFirst("wallflower:".count).trimmingCharacters(in: .whitespaces) }
        if why.isEmpty { return "The computer wouldn't take this phone's key (\(status))." }
        why = why.prefix(1).uppercased() + why.dropFirst()
        return why.last.map { ".!?".contains($0) } == true ? why : why + "."
    }
}

/** How a claim ended: its exit status, if it gave one, and what it said on stderr. */
private struct Claimed: Sendable {
    var status: Int?
    var stderr: String
}

/** Lets in only the computer whose key the code named. */
private final class PinnedHost: NIOSSHClientServerAuthenticationDelegate, @unchecked Sendable {
    let key: String
    var wrong = false

    init(key: String) { self.key = key }

    func validateHostKey(hostKey: NIOSSHPublicKey, validationCompletePromise: EventLoopPromise<Void>) {
        if String(openSSHPublicKey: hostKey) == key {
            validationCompletePromise.succeed(())
        } else {
            wrong = true
            validationCompletePromise.fail(PairingFailed.wrongComputer)
        }
    }
}

/** Runs claim on the exec channel, writes it the key's line and ends its input, then hears it out: what it says, and its exit status. */
private final class ClaimHandler: ChannelInboundHandler, @unchecked Sendable {
    typealias InboundIn = SSHChannelData
    typealias OutboundOut = SSHChannelData

    let line: String
    let done: EventLoopPromise<Claimed>
    private var status: Int?
    private var stderr = ""
    private var failed: Error?

    init(line: String, done: EventLoopPromise<Claimed>) {
        self.line = line
        self.done = done
    }

    func handlerAdded(context: ChannelHandlerContext) {
        context.channel.setOption(ChannelOptions.allowRemoteHalfClosure, value: true).whenFailure { [weak self] error in
            self?.failed = error
            context.close(promise: nil)
        }
    }

    func channelActive(context: ChannelHandlerContext) {
        // The code's key runs claim, whatever's asked; the command is for keys without that restriction.
        context.triggerUserOutboundEvent(SSHChannelRequestEvent.ExecRequest(command: "wallflower claim", wantReply: true), promise: nil)
        context.fireChannelActive()
    }

    func userInboundEventTriggered(context: ChannelHandlerContext, event: Any) {
        switch event {
        case is ChannelSuccessEvent:
            let buffer = context.channel.allocator.buffer(string: line + "\n")
            context.writeAndFlush(wrapOutboundOut(SSHChannelData(type: .channel, data: .byteBuffer(buffer))), promise: nil)
            context.close(mode: .output, promise: nil)
        case is ChannelFailureEvent:
            failed = SSHFailure.execRefused
            context.close(promise: nil)
        case let s as SSHChannelRequestEvent.ExitStatus:
            status = s.exitStatus
        default:
            context.fireUserInboundEventTriggered(event)
        }
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let message = unwrapInboundIn(data)
        guard case .byteBuffer(let buffer) = message.data, message.type == .stdErr else { return }
        stderr += String(buffer: buffer)
    }

    func errorCaught(context: ChannelHandlerContext, error: Error) {
        failed = error
        context.close(promise: nil)
    }

    func channelInactive(context: ChannelHandlerContext) {
        if status == nil, let failed {
            done.fail(failed)
        } else {
            done.succeed(Claimed(status: status, stderr: stderr))
        }
        context.fireChannelInactive()
    }

    func handlerRemoved(context: ChannelHandlerContext) {
        done.fail(ChannelError.eof) // never made active; once it's done, this does nothing
    }
}
