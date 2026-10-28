import Foundation

/// Client -> server call frames (server design 7.3). `payload` is base64 of a `LabPayload`.
public enum LabClientFrame: Equatable, Sendable {
    case invite(callID: String, to: String, video: Bool, payload: String)
    case answer(callID: String, payload: String)
    case signal(callID: String, payload: String)
    case decline(callID: String)
    case hangup(callID: String)

    public func json(id: String?) throws -> Data {
        var type: String
        var data: [String: Any]
        switch self {
        case let .invite(c, to, v, p): type = "call.invite"; data = ["callID": c, "to": to, "video": v, "payload": p]
        case let .answer(c, p): type = "call.answer"; data = ["callID": c, "payload": p]
        case let .signal(c, p): type = "call.signal"; data = ["callID": c, "payload": p]
        case let .decline(c): type = "call.decline"; data = ["callID": c]
        case let .hangup(c): type = "call.hangup"; data = ["callID": c]
        }
        var frame: [String: Any] = ["type": type, "data": data]
        if let id { frame["id"] = id }
        return try JSONSerialization.data(withJSONObject: frame, options: [.sortedKeys])
    }
}

public enum LabServerEvent: Equatable, Sendable {
    case ring(callID: String, fromAccountID: String, fromDeviceID: String, video: Bool, payload: String)
    case answered(callID: String, deviceID: String, payload: String)
    case signal(callID: String, fromDeviceID: String, payload: String)
    case ended(callID: String, reason: String)
    case ack(id: String)
    case error(id: String?, code: String)
    case other(type: String)

    public static func parse(_ raw: Data) throws -> LabServerEvent {
        guard let o = try JSONSerialization.jsonObject(with: raw) as? [String: Any], let type = o["type"] as? String else {
            throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "not a frame"))
        }
        let d = o["data"] as? [String: Any] ?? [:]
        func s(_ k: String) -> String { d[k] as? String ?? "" }
        switch type {
        case "call.ring":
            return .ring(callID: s("callID"), fromAccountID: s("fromAccountID"), fromDeviceID: s("fromDeviceID"),
                         video: d["video"] as? Bool ?? false, payload: s("payload"))
        case "call.answered": return .answered(callID: s("callID"), deviceID: s("deviceID"), payload: s("payload"))
        case "call.signal": return .signal(callID: s("callID"), fromDeviceID: s("fromDeviceID"), payload: s("payload"))
        case "call.ended": return .ended(callID: s("callID"), reason: s("reason"))
        case "ack": return .ack(id: o["id"] as? String ?? "")
        case "error": return .error(id: o["id"] as? String, code: s("code"))
        default: return .other(type: type)
        }
    }
}

/// `GET /v1/turn` response.
public struct TurnConfig: Codable, Equatable, Sendable {
    public struct Server: Codable, Equatable, Sendable {
        public var urls: [String]
        public var username: String
        public var credential: String
    }
    public var iceServers: [Server]
    public var ttl: Int
}
