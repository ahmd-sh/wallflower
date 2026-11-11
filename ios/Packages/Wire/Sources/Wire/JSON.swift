import Foundation

/// A JSON object written as kotlinx.serialization writes one: its "t" first, then fields in order, strings escaped as it escapes them.
struct JSONObject {
    private var bytes: [UInt8] = []

    init(_ type: String) {
        bytes.append(UInt8(ascii: "{"))
        key("t")
        string(type)
    }

    mutating func field(_ name: String, _ value: String) {
        comma()
        key(name)
        string(value)
    }

    mutating func field(_ name: String, _ value: String?) {
        if let value { field(name, value) }
    }

    mutating func field(_ name: String, _ value: some BinaryInteger) {
        comma()
        key(name)
        bytes.append(contentsOf: String(value).utf8)
    }

    mutating func field<T: BinaryInteger>(_ name: String, _ value: T?) {
        if let value { field(name, value) }
    }

    mutating func field(_ name: String, _ value: Bool) {
        comma()
        key(name)
        bytes.append(contentsOf: (value ? "true" : "false").utf8)
    }

    mutating func field(_ name: String, _ value: [String]) {
        comma()
        key(name)
        bytes.append(UInt8(ascii: "["))
        for (i, s) in value.enumerated() {
            if i > 0 { bytes.append(UInt8(ascii: ",")) }
            string(s)
        }
        bytes.append(UInt8(ascii: "]"))
    }

    mutating func end() -> [UInt8] {
        bytes.append(UInt8(ascii: "}"))
        return bytes
    }

    private mutating func comma() {
        bytes.append(UInt8(ascii: ","))
    }

    private mutating func key(_ name: String) {
        string(name)
        bytes.append(UInt8(ascii: ":"))
    }

    private mutating func string(_ s: String) {
        bytes.append(UInt8(ascii: "\""))
        for b in s.utf8 {
            switch b {
            case UInt8(ascii: "\""), UInt8(ascii: "\\"): escape(b)
            case 0x08: escape(UInt8(ascii: "b"))
            case 0x09: escape(UInt8(ascii: "t"))
            case 0x0a: escape(UInt8(ascii: "n"))
            case 0x0c: escape(UInt8(ascii: "f"))
            case 0x0d: escape(UInt8(ascii: "r"))
            case ..<0x20:
                escape(UInt8(ascii: "u"))
                bytes.append(contentsOf: [UInt8(ascii: "0"), UInt8(ascii: "0"), hex[Int(b >> 4)], hex[Int(b & 0xf)]])
            default: bytes.append(b)
            }
        }
        bytes.append(UInt8(ascii: "\""))
    }

    private mutating func escape(_ b: UInt8) {
        bytes.append(UInt8(ascii: "\\"))
        bytes.append(b)
    }
}

private let hex = Array("0123456789abcdef".utf8)

struct JSONKey: CodingKey, ExpressibleByStringLiteral {
    let stringValue: String
    var intValue: Int? { nil }

    init(stringLiteral value: String) {
        stringValue = value
    }

    init?(stringValue: String) {
        self.stringValue = stringValue
    }

    init?(intValue: Int) {
        nil
    }
}

extension KeyedDecodingContainer where Key == JSONKey {
    func required<T: Decodable>(_ key: JSONKey) throws -> T {
        try decode(T.self, forKey: key)
    }

    func optional<T: Decodable>(_ key: JSONKey) throws -> T? {
        try decodeIfPresent(T.self, forKey: key)
    }

    /// The field's value, or `fallback` when it's missing; a null is an error, as it is to kotlinx.serialization.
    func value<T: Decodable>(_ key: JSONKey, or fallback: T) throws -> T {
        contains(key) ? try decode(T.self, forKey: key) : fallback
    }
}
