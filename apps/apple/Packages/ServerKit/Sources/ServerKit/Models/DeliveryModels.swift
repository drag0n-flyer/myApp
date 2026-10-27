import Foundation

/// One row of the device's inbox (server spec §7.2). `kind` is `message`, `commit` or `welcome`;
/// `seq` is present for messages only. Same shape as lane L2's temporary placeholder (epoch is Int64).
public struct InboxItem: Codable, Sendable, Equatable {
    public var cursor: Int64
    public var kind: String
    public var conversationID: String
    public var seq: Int64?
    public var epoch: Int64
    public var senderAccountID: String
    public var senderDeviceID: String
    public var payload: Data
    public var sentAt: Date

    enum CodingKeys: String, CodingKey {
        case cursor, kind, conversationID, seq, epoch, senderAccountID, senderDeviceID, payload, sentAt
    }

    public init(cursor: Int64, kind: String, conversationID: String, seq: Int64?, epoch: Int64,
                senderAccountID: String, senderDeviceID: String, payload: Data, sentAt: Date) {
        self.cursor = cursor; self.kind = kind; self.conversationID = conversationID; self.seq = seq
        self.epoch = epoch; self.senderAccountID = senderAccountID; self.senderDeviceID = senderDeviceID
        self.payload = payload; self.sentAt = sentAt
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        cursor = try c.decode(Int64.self, forKey: .cursor)
        kind = try c.decode(String.self, forKey: .kind)
        conversationID = try c.decode(String.self, forKey: .conversationID)
        seq = try c.decodeIfPresent(Int64.self, forKey: .seq)
        epoch = try c.decode(Int64.self, forKey: .epoch)
        senderAccountID = try c.decode(String.self, forKey: .senderAccountID)
        senderDeviceID = try c.decode(String.self, forKey: .senderDeviceID)
        payload = try c.decode(Base64URLData.self, forKey: .payload).data
        sentAt = try c.decode(Date.self, forKey: .sentAt)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(cursor, forKey: .cursor)
        try c.encode(kind, forKey: .kind)
        try c.encode(conversationID, forKey: .conversationID)
        try c.encodeIfPresent(seq, forKey: .seq)
        try c.encode(epoch, forKey: .epoch)
        try c.encode(senderAccountID, forKey: .senderAccountID)
        try c.encode(senderDeviceID, forKey: .senderDeviceID)
        try c.encode(Base64URLData(payload), forKey: .payload)
        try c.encode(sentAt, forKey: .sentAt)
    }
}

public struct ClaimedKeyPackage: Equatable, Sendable {
    public let deviceID: String
    public let keyPackage: Data

    public init(deviceID: String, keyPackage: Data) {
        self.deviceID = deviceID
        self.keyPackage = keyPackage
    }
}

/// `POST /v1/conversations` and `GET /v1/conversations/{id}`.
public struct NewConversation: Decodable, Equatable, Sendable {
    public let conversationID: String
    public let mode: String
    public let epoch: UInt64
}
