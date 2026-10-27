import Foundation

/// The plaintext of a sealed push handle (server spec §7.4): APNs token (hex), kind, environment, bundle ID.
public struct PushHandle: Codable, Equatable, Sendable {
    public var t: String
    public var k: String
    public var e: String
    public var b: String

    public init(t: String, k: String, e: String, b: String) {
        self.t = t
        self.k = k
        self.e = e
        self.b = b
    }

    public static func hex(_ token: Data) -> String {
        token.map { String(format: "%02x", $0) }.joined()
    }
}
