import Foundation

/// Binary field on the wire: base64url without padding. Decoding accepts padding too.
public struct Base64URLData: Codable, Hashable, Sendable {
    public var data: Data

    public init(_ data: Data) { self.data = data }
    public init(data: Data) { self.data = data }

    public var encoded: String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public init?(encoded: String) {
        var s = encoded
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while s.hasSuffix("=") { s.removeLast() }
        guard s.count % 4 != 1 else { return nil }
        s += String(repeating: "=", count: (4 - s.count % 4) % 4)
        guard let d = Data(base64Encoded: s) else { return nil }
        self.data = d
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        let s = try c.decode(String.self)
        guard let v = Base64URLData(encoded: s) else {
            throw DecodingError.dataCorruptedError(in: c, debugDescription: "invalid base64url")
        }
        self = v
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(encoded)
    }
}
