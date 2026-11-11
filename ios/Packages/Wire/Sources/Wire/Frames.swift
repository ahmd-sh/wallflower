import Foundation

public let FRAME_JSON: UInt8 = 1
public let FRAME_DATA: UInt8 = 2
public let FRAME_UPLOAD: UInt8 = 3
public let FRAME_PICTURE: UInt8 = 4
public let FRAME_FILE: UInt8 = 5

/// Bounds a frame's length field, so a corrupt stream can't make a reader allocate without limit.
public let MAX_FRAME = 16 << 20

public struct ProtocolError: Error, Equatable, CustomStringConvertible {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var description: String { message }
}

public enum Frame: Sendable, Equatable {
    case message(ServerMessage)

    /// A message newer than this client, safe to ignore, or one it couldn't read (`problem`).
    case unknown(json: String, problem: String? = nil)

    /// Pane output from the gateway.
    case data(pane: String, bytes: Data)

    /// A picture entry's bytes; none if they're gone.
    case picture(id: String, bytes: Data)

    /// The next of a fetched file's bytes, by the fetch's id.
    case file(id: String, bytes: Data)
}

/// Frames from bytes as they arrive, however they're split.
public struct FrameDecoder: Sendable {
    private var buffer: [UInt8] = []
    private var start = 0
    /// What's left of a frame of a type newer than this client, passed over as it arrives.
    private var skip = 0

    public init() {}

    public mutating func append(_ data: Data) {
        if start == buffer.count {
            buffer.removeAll(keepingCapacity: true)
            start = 0
        } else if start >= 64 * 1024 && start >= buffer.count / 2 {
            buffer.removeSubrange(..<start)
            start = 0
        }
        buffer.append(contentsOf: data)
    }

    /// The next frame, passing over any of a type newer than this client; nil until all of one has arrived.
    public mutating func next() throws -> Frame? {
        while true {
            if skip > 0 {
                let n = min(skip, buffer.count - start)
                start += n
                skip -= n
                if skip > 0 { return nil }
            }
            guard buffer.count - start >= 4 else { return nil }
            let length = Int(Int32(bitPattern: UInt32(buffer[start]) << 24 | UInt32(buffer[start + 1]) << 16 | UInt32(buffer[start + 2]) << 8 | UInt32(buffer[start + 3])))
            guard (1...MAX_FRAME).contains(length) else { throw ProtocolError("bad frame length \(length)") }
            guard buffer.count - start >= 5 else { return nil }
            let type = buffer[start + 4]
            switch type {
            case FRAME_JSON, FRAME_DATA, FRAME_PICTURE, FRAME_FILE: break
            default:
                start += 5
                skip = length - 1
                continue
            }
            let end = start + 4 + length
            guard buffer.count >= end else { return nil }
            let payload = start + 5 ..< end
            start = end

            switch type {
            case FRAME_JSON:
                return Self.decodeMessage(String(decoding: buffer[payload], as: UTF8.self))
            case FRAME_DATA:
                let (id, bytes) = try self.id(payload)
                return .data(pane: id, bytes: bytes)
            case FRAME_FILE:
                let (id, bytes) = try self.id(payload)
                return .file(id: id, bytes: bytes)
            default:
                let (id, bytes) = try self.id(payload)
                return .picture(id: id, bytes: bytes)
            }
        }
    }

    /// A frame of an id, then bytes.
    private func id(_ payload: Range<Int>) throws -> (String, Data) {
        let n = payload.isEmpty ? -1 : Int(buffer[payload.lowerBound])
        if n < 0 || n > payload.count - 1 { throw ProtocolError("truncated id") }
        let at = payload.lowerBound + 1 + n
        return (String(decoding: buffer[payload.lowerBound + 1 ..< at], as: UTF8.self), Data(buffer[at ..< payload.upperBound]))
    }

    private static func decodeMessage(_ json: String) -> Frame {
        do {
            guard let type = try typeOf(json), let message = try ServerMessage.decode(type, from: Data(json.utf8)) else {
                return .unknown(json: json)
            }
            return .message(message)
        } catch {
            return .unknown(json: json, problem: problem(error))
        }
    }

    /// The message's "t", which the gateway writes first; anyone else's is looked for.
    private static func typeOf(_ json: String) throws -> String? {
        if let type = leadingType(json) { return type }
        guard let object = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any] else {
            throw Unreadable(problem: "not a JSON object")
        }
        switch object["t"] {
        case let type as String: return type
        case is [Any], is [String: Any]: throw Unreadable(problem: "\"t\" isn't a JSON primitive")
        default: return nil
        }
    }

    private static func leadingType(_ json: String) -> String? {
        let bytes = json.utf8
        guard bytes.starts(with: #"{"t":""#.utf8) else { return nil }
        let from = bytes.index(bytes.startIndex, offsetBy: 6)
        var i = from
        while i < bytes.endIndex, (UInt8(ascii: "a")...UInt8(ascii: "z")).contains(bytes[i]) {
            i = bytes.index(after: i)
        }
        guard i > from, i < bytes.endIndex, bytes[i] == UInt8(ascii: "\"") else { return nil }
        return String(decoding: bytes[from ..< i], as: UTF8.self)
    }

    private static func problem(_ error: Error) -> String {
        switch error {
        case let error as Unreadable: return error.problem
        case let DecodingError.keyNotFound(key, context): return "\(path(context.codingPath + [key])) is missing"
        case let DecodingError.typeMismatch(_, context), let DecodingError.valueNotFound(_, context), let DecodingError.dataCorrupted(context):
            return "\(path(context.codingPath)): \(context.debugDescription)"
        default: return error.localizedDescription
        }
    }

    private static func path(_ keys: [CodingKey]) -> String {
        keys.reduce("$") { path, key in key.intValue.map { "\(path)[\($0)]" } ?? "\(path).\(key.stringValue)" }
    }

    private struct Unreadable: Error {
        let problem: String
    }
}

/// Frames as bytes, to write.
public enum FrameEncoder {
    public static func message(_ message: ClientMessage) -> Data {
        let body = message.json
        var out = Data(capacity: 5 + body.count)
        header(&out, length: 1 + body.count, type: FRAME_JSON)
        out.append(contentsOf: body)
        return out
    }

    public static func data(pane: String, bytes: Data) -> Data {
        frame(FRAME_DATA, id: pane, bytes: bytes)
    }

    /// The next of upload `id`'s bytes.
    public static func upload(id: String, bytes: Data) -> Data {
        frame(FRAME_UPLOAD, id: id, bytes: bytes)
    }

    public static func encode(_ message: ClientMessage) -> Data {
        Data(message.json)
    }

    private static func frame(_ type: UInt8, id: String, bytes: Data) -> Data {
        let idBytes = Array(id.utf8)
        precondition(idBytes.count <= 255, "id longer than 255 bytes")
        var out = Data(capacity: 6 + idBytes.count + bytes.count)
        header(&out, length: 2 + idBytes.count + bytes.count, type: type)
        out.append(UInt8(idBytes.count))
        out.append(contentsOf: idBytes)
        out.append(contentsOf: bytes)
        return out
    }

    private static func header(_ out: inout Data, length: Int, type: UInt8) {
        out.append(UInt8(truncatingIfNeeded: length >> 24))
        out.append(UInt8(truncatingIfNeeded: length >> 16))
        out.append(UInt8(truncatingIfNeeded: length >> 8))
        out.append(UInt8(truncatingIfNeeded: length))
        out.append(type)
    }
}
