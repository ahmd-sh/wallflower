import CryptoKit
import Foundation
import LocalAuthentication
import NIOSSH
import Security

/**
 * The phone's SSH key: ECDSA P-256 made inside the Secure Enclave, where the
 * private key can't leave. Signing needs the user's Face ID or passcode
 * within [unlockSeconds] of it; an unlock lets the app reconnect on its own
 * meanwhile. Where there's no Secure Enclave (the simulator), a key kept in
 * the keychain, which needs no unlock.
 */
public final class PhoneKey: @unchecked Sendable {
    private let account: String
    private let defaults: UserDefaults
    private let lock = NSLock()
    private var context: LAContext?
    private var unlockedAt: Date?

    public init(account: String = "wallflower-ssh", defaults: UserDefaults = .standard) {
        self.account = account
        self.defaults = defaults
    }

    public static let ALG = "ecdsa-sha2-nistp256"
    public static let AUTH_SECONDS = 300

    public var exists: Bool { stored() != nil }

    /** Whether it's kept in the Secure Enclave, so signing needs an unlock. */
    public var hardware: Bool { defaults.bool(forKey: account + ".enclave") }

    /** How long an unlock lets it sign; nil without one. */
    public var unlockSeconds: Int? {
        guard exists else { return nil }
        let s = defaults.integer(forKey: account + ".seconds")
        return s > 0 ? s : Self.AUTH_SECONDS
    }

    /** Makes the key, in place of any before, usable for [seconds] after an unlock. */
    public func generate(seconds: Int = AUTH_SECONDS) throws {
        delete()
        if let key = Self.enclaveKey() {
            try save(key.dataRepresentation)
            defaults.set(true, forKey: account + ".enclave")
        } else {
            // No enclave to make it in (the simulator), or none that will (no passcode set): kept in the keychain, signing without an unlock.
            let key = P256.Signing.PrivateKey()
            try save(key.rawRepresentation)
            defaults.set(false, forKey: account + ".enclave")
        }
        defaults.set(seconds, forKey: account + ".seconds")
        lock.withLock {
            context = nil
            unlockedAt = nil
        }
    }

    private static func enclaveKey() -> SecureEnclave.P256.Signing.PrivateKey? {
        #if targetEnvironment(simulator)
        return nil
        #else
        guard SecureEnclave.isAvailable,
              let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, [.privateKeyUsage, .userPresence], nil) else { return nil }
        return try? SecureEnclave.P256.Signing.PrivateKey(accessControl: access)
        #endif
    }

    /** SSH wire format: string "ecdsa-sha2-nistp256", string "nistp256", string Q. */
    public var publicKeyBlob: Data? {
        guard let q = publicPoint() else { return nil }
        return Self.sshStrings([Data(Self.ALG.utf8), Data("nistp256".utf8), q])
    }

    public func authorizedKey(comment: String) -> String {
        "\(Self.ALG) \(publicKeyBlob?.base64EncodedString() ?? "") \(comment)"
    }

    public var fingerprint: String { publicKeyBlob.map(HostKeys.fingerprint(blob:)) ?? "" }

    /** Whether the next connection needs the user to unlock the key first. */
    public var needsAuth: Bool {
        guard hardware else { return false }
        return lock.withLock {
            guard let at = unlockedAt, context != nil else { return true }
            return Date().timeIntervalSince(at) > Double(unlockSeconds ?? Self.AUTH_SECONDS)
        }
    }

    /** Face ID or the passcode; once it's given, the key signs for [unlockSeconds]. */
    @MainActor
    public func unlock(reason: String = "Unlock your SSH key to connect to your computer") async -> Bool {
        guard hardware else { return true }
        let ctx = LAContext()
        do {
            guard try await ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) else { return false }
        } catch {
            return false
        }
        ctx.interactionNotAllowed = true // signing with it never asks again: past its time, it fails, and the app asks
        lock.withLock {
            context = ctx
            unlockedAt = Date()
        }
        return true
    }

    /** The key to sign with, if it can sign without asking the user. */
    public func unlockedKey() -> NIOSSHPrivateKey? {
        guard let data = stored() else { return nil }
        if !hardware {
            return (try? P256.Signing.PrivateKey(rawRepresentation: data)).map { NIOSSHPrivateKey(p256Key: $0) }
        }
        if needsAuth { return nil }
        guard let ctx = lock.withLock({ context }),
              let key = try? SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: data, authenticationContext: ctx) else { return nil }
        // The unlock can stop counting before its time (the phone locked, a new face enrolled): found out here, it's locked, rather than failing in the handshake.
        guard (try? key.signature(for: Data("wallflower".utf8))) != nil else {
            lock.withLock {
                context = nil
                unlockedAt = nil
            }
            return nil
        }
        return NIOSSHPrivateKey(secureEnclaveP256Key: key)
    }

    private func publicPoint() -> Data? {
        guard let data = stored() else { return nil }
        if hardware {
            return (try? SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: data))?.publicKey.x963Representation
        }
        return (try? P256.Signing.PrivateKey(rawRepresentation: data))?.publicKey.x963Representation
    }

    // The key as the keychain keeps it: the enclave's wrapped key, or a software key's bytes.

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "sh.ahmd.wallflower", kSecAttrAccount as String: account]
    }

    private func stored() -> Data? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        if SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data { return d }
        return try? Data(contentsOf: file)
    }

    private func save(_ data: Data) throws {
        var q = query
        q[kSecValueData as String] = data
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(q as CFDictionary, nil)
        if status == errSecSuccess { return }
        // Where the keychain won't have it (an app signed without the entitlement for it, as the simulator's can be), a file only the app can read, encrypted while the phone's locked after a restart.
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private func delete() {
        SecItemDelete(query as CFDictionary)
        try? FileManager.default.removeItem(at: file)
    }

    private var file: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent(account + ".key")
    }

    static func sshStrings(_ parts: [Data]) -> Data {
        var out = Data()
        for p in parts {
            var n = UInt32(p.count).bigEndian
            out.append(Data(bytes: &n, count: 4))
            out.append(p)
        }
        return out
    }
}

/** Trusted host keys, one per host, pinned on first use after the user confirms. */
public final class HostKeys: @unchecked Sendable {
    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public enum Check { case trusted, unknown, changed }

    public func check(host: String, key: String) -> Check {
        guard let pinned = defaults.string(forKey: Self.prefix + host) else { return .unknown }
        return pinned == key ? .trusted : .changed
    }

    public func trust(host: String, key: String) {
        defaults.set(key, forKey: Self.prefix + host)
    }

    public func forget(host: String) {
        defaults.removeObject(forKey: Self.prefix + host)
    }

    /** OpenSSH's format, as ssh-keygen -l prints it. */
    public static func fingerprint(blob: Data) -> String {
        "SHA256:" + Data(SHA256.hash(data: blob)).base64EncodedString().replacingOccurrences(of: "=", with: "")
    }

    /** The fingerprint of a key as OpenSSH writes it ("type base64"). */
    public static func fingerprint(openSSH key: String) -> String {
        let parts = key.split(separator: " ")
        guard parts.count >= 2, let blob = Data(base64Encoded: String(parts[1])) else { return key }
        return fingerprint(blob: blob)
    }

    private static let prefix = "hostkey:"
}
