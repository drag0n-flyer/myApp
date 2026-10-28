import Foundation

public struct LabPayload: Codable, Equatable, Sendable {
    public enum Kind: String, Codable, Sendable { case offer, answer, candidate, bye }

    public var kind: Kind
    public var sdp: String?
    public var candidate: String?
    public var sdpMid: String?
    public var sdpMLineIndex: Int32?
    public var knobs: LabKnobs?

    public init(kind: Kind, sdp: String? = nil, candidate: String? = nil, sdpMid: String? = nil,
                sdpMLineIndex: Int32? = nil, knobs: LabKnobs? = nil) {
        self.kind = kind; self.sdp = sdp; self.candidate = candidate
        self.sdpMid = sdpMid; self.sdpMLineIndex = sdpMLineIndex; self.knobs = knobs
    }

    /// Base64 of the JSON encoding (the server frame's `payload`).
    public func encoded() throws -> String {
        let enc = JSONEncoder()
        enc.outputFormatting = [.sortedKeys]
        // base64url without padding: the server's httpx.Bytes rejects anything else ("bad call frame").
        return try enc.encode(self).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public static func decode(_ base64: String) throws -> LabPayload {
        // Accepts base64url without padding (what the server sends) as well as standard base64.
        var std = base64.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        std += String(repeating: "=", count: (4 - std.count % 4) % 4)
        guard let data = Data(base64Encoded: std) else {
            throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "invalid base64"))
        }
        return try JSONDecoder().decode(LabPayload.self, from: data)
    }
}
