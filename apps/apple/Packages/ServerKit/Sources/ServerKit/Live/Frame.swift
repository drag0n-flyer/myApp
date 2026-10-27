import Foundation

public enum IncomingFrame: Sendable, Equatable {
    case event(ServerEvent)
    case ack(id: String, data: Data?)
    case error(id: String, code: String)
    /// A known frame type whose fields did not decode. Log the type only, never the payload.
    case malformed(type: String)
}

private struct RawFrame: Decodable {
    var type: String
    var id: String?
    var data: RawJSON?
}

/// Keeps the raw bytes of the `data` object so each frame type can decode it on its own.
private struct RawJSON: Decodable {
    let bytes: Data
    init(from decoder: Decoder) throws {
        let v = try AnyJSON(from: decoder)
        bytes = (try? JSONSerialization.data(withJSONObject: v.object, options: [.fragmentsAllowed])) ?? Data()
    }
}

private struct AnyJSON: Decodable {
    let object: Any
    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { object = NSNull() }
        else if let b = try? c.decode(Bool.self) { object = b }
        else if let i = try? c.decode(Int64.self) { object = i }
        else if let d = try? c.decode(Double.self) { object = d }
        else if let s = try? c.decode(String.self) { object = s }
        else if let a = try? c.decode([AnyJSON].self) { object = a.map(\.object) }
        else { object = try c.decode([String: AnyJSON].self).mapValues(\.object) }
    }
}

private struct DeviceRevoked: Decodable { var deviceID: String }
private struct CallRing: Decodable {
    var callID, fromAccountID, fromDeviceID: String
    var video: Bool
    var payload: Base64URLData
}
private struct CallAnswered: Decodable { var callID, deviceID: String; var payload: Base64URLData }
private struct CallSignal: Decodable { var callID, fromDeviceID: String; var payload: Base64URLData }
private struct CallEnded: Decodable { var callID, reason: String }
private struct ErrorData: Decodable { var code: String }

/// Returns nil for unknown frame types and non-JSON text, `.malformed` for known types with bad fields.
public func decodeFrame(_ text: String) -> IncomingFrame? {
    let decoder = ServerJSON.decoder()
    guard let raw = try? decoder.decode(RawFrame.self, from: Data(text.utf8)) else { return nil }
    let bytes = raw.data?.bytes ?? Data()
    let bad = IncomingFrame.malformed(type: raw.type)
    func body<T: Decodable>(_ t: T.Type) -> T? { try? decoder.decode(t, from: bytes) }
    switch raw.type {
    case "inbox":
        return body(InboxItem.self).map { .event(.inbox($0)) } ?? bad
    case "device.revoked":
        return body(DeviceRevoked.self).map { .event(.deviceRevoked($0.deviceID)) } ?? bad
    case "call.ring":
        return body(CallRing.self).map {
            .event(.callRing(callID: $0.callID, fromAccountID: $0.fromAccountID,
                             fromDeviceID: $0.fromDeviceID, video: $0.video, payload: $0.payload.data))
        } ?? bad
    case "call.answered":
        return body(CallAnswered.self).map {
            .event(.callAnswered(callID: $0.callID, deviceID: $0.deviceID, payload: $0.payload.data))
        } ?? bad
    case "call.signal":
        return body(CallSignal.self).map {
            .event(.callSignal(callID: $0.callID, fromDeviceID: $0.fromDeviceID, payload: $0.payload.data))
        } ?? bad
    case "call.ended":
        return body(CallEnded.self).map { .event(.callEnded(callID: $0.callID, reason: $0.reason)) } ?? bad
    case "ack":
        guard let id = raw.id else { return bad }
        return .ack(id: id, data: raw.data?.bytes)
    case "error":
        guard let id = raw.id, let e = body(ErrorData.self) else { return bad }
        return .error(id: id, code: e.code)
    default:
        return nil
    }
}

private struct OutgoingFrame<D: Encodable>: Encodable {
    var type: String
    var id: String
    var data: D
}

/// Sorted-keys JSON text of one client frame.
public func encodeFrame(type: String, id: String, data: some Encodable) throws -> String {
    let e = ServerJSON.encoder()
    e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    let bytes = try e.encode(OutgoingFrame(type: type, id: id, data: data))
    return String(decoding: bytes, as: UTF8.self)
}
