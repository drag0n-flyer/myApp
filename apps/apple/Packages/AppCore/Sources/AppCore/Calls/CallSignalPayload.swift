import Foundation

/// The call content type inside a sealed message (client messaging plan, Design 16).
/// Copied from the client-calling plan, "Call payload sealing".
public struct CallSignalPayload: Codable, Equatable, Sendable {
    public enum Kind: String, Codable, Sendable { case ring, offer, answer, candidate, renegotiate, renegotiateAnswer, modeChange }
    public var kind: Kind
    public var callID: String
    public var video: Bool?             // ring
    public var sdp: String?             // offer, answer, renegotiate, renegotiateAnswer
    public var dtlsFingerprint: String? // offer, answer: "sha-256 AB:CD:…", copied from the SDP's a=fingerprint
    public var candidate: IceCandidate? // candidate
    public var ceiling: DataMode?       // ring, offer, answer, modeChange: the most this device wants to receive
    public var videoOn: Bool?           // modeChange

    public init(kind: Kind, callID: String, video: Bool? = nil, sdp: String? = nil, dtlsFingerprint: String? = nil,
                candidate: IceCandidate? = nil, ceiling: DataMode? = nil, videoOn: Bool? = nil) {
        self.kind = kind
        self.callID = callID
        self.video = video
        self.sdp = sdp
        self.dtlsFingerprint = dtlsFingerprint
        self.candidate = candidate
        self.ceiling = ceiling
        self.videoOn = videoOn
    }
}

public struct IceCandidate: Codable, Equatable, Sendable {
    public var sdp: String
    public var sdpMid: String?
    public var sdpMLineIndex: Int32

    public init(sdp: String, sdpMid: String?, sdpMLineIndex: Int32) {
        self.sdp = sdp
        self.sdpMid = sdpMid
        self.sdpMLineIndex = sdpMLineIndex
    }
}

public enum CallSealError: Error, Equatable { case wrongSender, undecryptable }
