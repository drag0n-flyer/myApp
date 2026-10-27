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
        return try enc.encode(self).base64EncodedString()
    }

    public static func decode(_ base64: String) throws -> LabPayload {
        guard let data = Data(base64Encoded: base64) else {
            throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "invalid base64"))
        }
        return try JSONDecoder().decode(LabPayload.self, from: data)
    }
}
