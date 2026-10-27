import Foundation

public struct UserSummary: Codable, Equatable, Sendable {
    public let accountID: String
    public let username: String
    public let displayName: String
}

public struct ReportedMessage: Codable, Equatable, Sendable {
    public let sentAt: Date
    public let text: String

    public init(sentAt: Date, text: String) {
        self.sentAt = sentAt
        self.text = text
    }
}

public struct UploadedAttachment: Codable, Equatable, Sendable {
    public let id: String
    public let expiresAt: Date
}

public enum PushTokenKind: String, Sendable { case alert, voip }
