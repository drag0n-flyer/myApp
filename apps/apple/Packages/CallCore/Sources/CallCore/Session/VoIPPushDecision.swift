import Foundation
import AppCore

/// What the device learned about a VoIP push, before deciding what to tell CallKit.
public enum PushPayloadState: Sendable {
    case ring(CallSignalPayload, contact: Contact)
    case undecryptable
    case empty
}

public enum EndReason: Equatable, Sendable { case failed, remoteEnded, unanswered }

public enum VoIPPushAction: Equatable, Sendable {
    case reportRing(name: String, video: Bool)
    case reportThenEnd(name: String, reason: EndReason)
    case ignore
}

/// Pure "VoIP push to CallKit report" mapper. iOS kills an app that receives a VoIP push and does not
/// report a call, so `.ignore` is only ever returned when iOS 26.4+ said `mustReport == false`.
public enum VoIPPushDecision {
    public static let unknownCallerName = "Unknown"

    /// `mustReport == nil` means the device is below iOS 26.4 (no metadata): treat as must report.
    public static func decide(mustReport: Bool?, payload: PushPayloadState, isStranger: Bool,
                              status: CallStatus?) -> VoIPPushAction {
        let required = mustReport ?? true
        let action: VoIPPushAction
        switch payload {
        case .undecryptable, .empty:
            action = .reportThenEnd(name: unknownCallerName, reason: .failed)
        case let .ring(ring, contact):
            let name = contact.displayName
            if isStranger {
                action = .reportThenEnd(name: name, reason: .unanswered)
            } else if let status, status != .ringing, status != .unknown {
                action = .reportThenEnd(name: name, reason: .remoteEnded)
            } else {
                action = .reportRing(name: name, video: ring.video ?? false)
            }
        }
        if required { return action }
        // Not required to report: ring only if it would really ring; skip everything else.
        if case .reportRing = action { return action }
        return .ignore
    }
}

/// Reads the `d` field of a VoIP push body (standard base64; server spec §7.4 padding).
public enum VoIPPushBody {
    /// Returns the sealed payload, or nil when `d` is missing or not base64, or the length prefix is bad.
    /// An empty result means the server sent an empty push (payload over 3070 bytes).
    public static func payload(d: String?) -> Data? {
        guard let d, let padded = Data(base64Encoded: d) else { return nil }
        return unpad(padded)
    }

    /// 2-byte big-endian length, the payload, then zeros.
    public static func unpad(_ padded: Data) -> Data? {
        let bytes = [UInt8](padded)
        guard bytes.count >= 2 else { return nil }
        let n = Int(bytes[0]) << 8 | Int(bytes[1])
        guard bytes.count >= 2 + n else { return nil }
        return Data(bytes[2..<(2 + n)])
    }
}
