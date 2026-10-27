import AppCore
import Foundation

/// `GET /v1/server` (server spec §7.5).
public struct ServerInfo: Decodable, Equatable, Sendable {
    public struct Policy: Decodable, Equatable, Sendable {
        public let defaultMode: String
        public let maxMode: String
        public let defaultIPHiding: String
    }
    public struct Encryption: Decodable, Equatable, Sendable {
        public let newConversations: String
        public let escrowKey: String?
    }
    public struct Push: Decodable, Equatable, Sendable {
        public let gateway: String?
    }
    public struct Limits: Decodable, Equatable, Sendable {
        public let attachmentBytes: Int
    }

    public let `protocol`: Int
    public let host: String
    public let requiresInvite: Bool
    /// Raw wire values; use `policy()` for the typed `ServerPolicy`.
    public let policyFields: Policy
    public let encryption: Encryption
    public let push: Push?
    public let features: [String]
    public let federation: Bool
    public let limits: Limits

    private enum Mode: String, Decodable {
        case ultraLow, dataSaver, balanced, high
        var dataMode: DataMode {
            switch self {
            case .ultraLow: .ultraLow
            case .dataSaver: .dataSaver
            case .balanced: .balanced
            case .high: .high
            }
        }
    }

    private enum Hiding: String, Decodable {
        case never, nonContacts, always
        var value: IPHiding {
            switch self {
            case .never: .never
            case .nonContacts: .nonContacts
            case .always: .always
            }
        }
    }

    // Unknown policy values fail the decode instead of being guessed.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        `protocol` = try c.decode(Int.self, forKey: .protocol)
        host = try c.decode(String.self, forKey: .host)
        requiresInvite = try c.decode(Bool.self, forKey: .requiresInvite)
        policyFields = try c.decode(Policy.self, forKey: .policy)
        encryption = try c.decode(Encryption.self, forKey: .encryption)
        push = try c.decodeIfPresent(Push.self, forKey: .push)
        features = try c.decode([String].self, forKey: .features)
        federation = try c.decode(Bool.self, forKey: .federation)
        limits = try c.decode(Limits.self, forKey: .limits)
        guard (try? Self.preference(policyFields.defaultMode)) != nil,
              Mode(rawValue: policyFields.maxMode) != nil,
              Hiding(rawValue: policyFields.defaultIPHiding) != nil else {
            throw DecodingError.dataCorruptedError(forKey: .policy, in: c, debugDescription: "unknown policy value")
        }
    }

    private enum CodingKeys: String, CodingKey {
        case `protocol`, host, requiresInvite, policy, encryption, push, features, federation, limits
    }

    private static func preference(_ s: String) throws -> ModePreference {
        if s == "auto" { return .auto }
        guard let m = Mode(rawValue: s) else { throw ServerError.decoding() }
        return .fixed(m.dataMode)
    }

    public func policy() -> ServerPolicy {
        ServerPolicy(defaultPreference: (try? Self.preference(policyFields.defaultMode)) ?? .auto,
                     maximum: Mode(rawValue: policyFields.maxMode)?.dataMode ?? .high,
                     defaultIPHiding: Hiding(rawValue: policyFields.defaultIPHiding)?.value ?? .nonContacts,
                     requiresInvite: requiresInvite,
                     host: host)
    }
}
