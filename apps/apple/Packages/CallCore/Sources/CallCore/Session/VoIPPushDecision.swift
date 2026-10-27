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

/// What the device learned about a VoIP push after opening it.
public struct VoIPPushResolution: Sendable {
    public var payload: PushPayloadState
    public var isStranger: Bool
    public var status: CallStatus?
    public init(payload: PushPayloadState, isStranger: Bool, status: CallStatus?) {
        self.payload = payload; self.isStranger = isStranger; self.status = status
    }
}

public enum VoIPPushImmediate: Equatable, Sendable {
    /// Report a generic "Incoming call" to CallKit now, synchronously in the push callback.
    case reportGeneric
    /// iOS 26.4+ said no report is required: resolve first, and report only if it would really ring.
    case deferToResolution
}

public enum VoIPPushFollowUp: Equatable, Sendable {
    case updateCaller(name: String, video: Bool)
    case end(reason: EndReason)
    case reportRing(name: String, video: Bool)
    case none
}

extension VoIPPushDecision {
    /// Stage 1, before any async work.
    public static func immediate(mustReport: Bool?) -> VoIPPushImmediate {
        (mustReport ?? true) ? .reportGeneric : .deferToResolution
    }

    /// Stage 2, after the payload is opened. `reportedGeneric` says whether stage 1 already reported a call.
    public static func followUp(reportedGeneric: Bool, action: VoIPPushAction) -> VoIPPushFollowUp {
        switch (reportedGeneric, action) {
        case let (true, .reportRing(name, video)): .updateCaller(name: name, video: video)
        case let (true, .reportThenEnd(_, reason)): .end(reason: reason)
        case (true, .ignore): .end(reason: .failed)   // cannot happen when stage 1 reported; end safely
        case let (false, .reportRing(name, video)): .reportRing(name: name, video: video)
        case (false, _): .none
        }
    }
}

/// Opens a push's sealed ring payload and gathers what the decision needs. Never throws: any failure is `.undecryptable`.
public struct VoIPPushResolver: Sendable {
    private let sealer: any SenderRecoveringSealer
    private let contact: @Sendable (String) async -> Contact?
    private let status: @Sendable (String) async -> CallStatus?

    /// - contact: the saved contact for an account ID, or nil for a stranger.
    /// - status: `call.status` for a call ID; nil when it cannot be fetched in time.
    public init(sealer: any SenderRecoveringSealer,
                contact: @escaping @Sendable (String) async -> Contact?,
                status: @escaping @Sendable (String) async -> CallStatus?) {
        self.sealer = sealer; self.contact = contact; self.status = status
    }

    public func resolve(sealed: Data?) async -> VoIPPushResolution {
        guard let sealed, !sealed.isEmpty else {
            return VoIPPushResolution(payload: .empty, isStranger: false, status: nil)
        }
        guard let opened = try? await sealer.openRecoveringSender(sealed), opened.payload.kind == .ring else {
            return VoIPPushResolution(payload: .undecryptable, isStranger: false, status: nil)
        }
        if let known = await contact(opened.senderAccountID) {
            let st = await status(opened.payload.callID)
            return VoIPPushResolution(payload: .ring(opened.payload, contact: known), isStranger: false, status: st)
        }
        let stranger = Contact(id: opened.senderAccountID, displayName: VoIPPushDecision.unknownCallerName, username: "")
        return VoIPPushResolution(payload: .ring(opened.payload, contact: stranger), isStranger: true, status: nil)
    }
}
