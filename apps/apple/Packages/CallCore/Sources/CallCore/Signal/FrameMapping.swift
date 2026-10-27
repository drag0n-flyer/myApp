import Foundation

/// CallCore-owned mirror of the call cases of ServerKit's `ServerEvent`.
/// CallCore does not depend on ServerKit; the app adapter converts `ServerEvent` to this type.
public enum CallWireEvent: Sendable, Equatable {
    case ring(callID: String, fromAccountID: String, fromDeviceID: String, video: Bool, payload: Data)
    case answered(callID: String, deviceID: String, payload: Data)
    case signal(callID: String, fromDeviceID: String, payload: Data)
    case ended(callID: String, reason: String)
    case other   // inbox, device.revoked: not a call event
}

/// A JSON value in a client frame's `data` object.
public enum FrameValue: Sendable, Equatable, Encodable {
    case string(String)
    case bool(Bool)

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let s): try c.encode(s)
        case .bool(let b): try c.encode(b)
        }
    }
}

public enum FrameMappingError: Error, Equatable { case malformedTurn }

/// Pure translation between server frames (server spec §7.3) and CallCore types.
public enum FrameMapping {
    public static func event(_ e: CallWireEvent) -> CallServerEvent? {
        switch e {
        case let .ring(callID, from, device, video, payload):
            return .ring(callID: callID, fromAccountID: from, fromDeviceID: device, video: video, payload: payload)
        case let .answered(callID, device, payload): return .answered(callID: callID, deviceID: device, payload: payload)
        case let .signal(callID, device, payload): return .signal(callID: callID, fromDeviceID: device, payload: payload)
        case let .ended(callID, reason): return .ended(callID: callID, reason: reason)
        case .other: return nil
        }
    }

    public static func frame(invite callID: String, to accountID: String, video: Bool, payload: Data)
        -> (type: String, data: [String: FrameValue]) {
        ("call.invite", ["callID": .string(callID), "to": .string(accountID),
                         "video": .bool(video), "payload": .string(base64URL(payload))])
    }

    public static func frame(answer callID: String, payload: Data) -> (type: String, data: [String: FrameValue]) {
        ("call.answer", ["callID": .string(callID), "payload": .string(base64URL(payload))])
    }

    public static func frame(signal callID: String, payload: Data) -> (type: String, data: [String: FrameValue]) {
        ("call.signal", ["callID": .string(callID), "payload": .string(base64URL(payload))])
    }

    /// `type` is `call.decline`, `call.hangup` or `call.status`.
    public static func frame(_ type: String, callID: String) -> (type: String, data: [String: FrameValue]) {
        (type, ["callID": .string(callID)])
    }

    private struct Turn: Decodable {
        struct Entry: Decodable { var urls: [String]; var username: String?; var credential: String? }
        var iceServers: [Entry]
        var ttl: Int?
    }

    public static func iceServers(fromTurnJSON json: Data) throws -> [IceServer] {
        guard let t = try? JSONDecoder().decode(Turn.self, from: json) else { throw FrameMappingError.malformedTurn }
        return t.iceServers.map { IceServer(urls: $0.urls, username: $0.username, credential: $0.credential) }
    }

    public static func turnTTL(fromTurnJSON json: Data) -> Int? {
        (try? JSONDecoder().decode(Turn.self, from: json))?.ttl
    }

    public static func status(fromAck data: Data) -> CallStatus {
        struct Ack: Decodable { var state: String; var reason: String? }
        guard let a = try? JSONDecoder().decode(Ack.self, from: data) else { return .unknown }
        switch a.state {
        case "ringing": return .ringing
        case "answered": return .answered
        case "ended": return .ended(reason: a.reason ?? "")
        default: return .unknown
        }
    }

    /// A failed `call.status`: `not_found` (and anything else) means the call is unknown.
    public static func status(fromErrorCode code: String) -> CallStatus { .unknown }

    public static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
