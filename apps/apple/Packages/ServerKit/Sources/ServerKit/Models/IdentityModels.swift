import Foundation

public struct NewSession: Codable, Equatable, Sendable {
    public let accountID: String
    public let deviceID: String
    public let sessionToken: String

    public init(accountID: String, deviceID: String, sessionToken: String) {
        self.accountID = accountID
        self.deviceID = deviceID
        self.sessionToken = sessionToken
    }
}

public struct Me: Codable, Equatable, Sendable {
    public let accountID: String
    public let username: String
    public let email: String
    public let displayName: String
    public let findByEmail: Bool
}

public struct Device: Codable, Equatable, Sendable, Identifiable {
    public let id: String
    public let name: String
    public let platform: String
    public let createdAt: Date
    public let lastSeenAt: Date
    public let current: Bool
}

public struct LinkTicket: Codable, Equatable, Sendable {
    public let linkID: String
    public let secret: String
    public let claimToken: String
    public let expiresAt: Date
}

/// What the approving device shows before it approves (`POST /v1/link/{id}/info`).
public struct LinkInfo: Codable, Equatable, Sendable {
    public let deviceName: String
    public let platform: String
    public let createdAt: Date
}

public enum LinkClaim: Equatable, Sendable {
    case pending
    case approved(NewSession, sealed: Data?)
}

// MARK: - WebAuthn

/// The Go server sends the WebAuthn options bare (`{"challenge":…}`), as its spec says; a browser-style
/// `{"publicKey":{…}}` wrapper is accepted too.
private struct PublicKeyEnvelope<T: Decodable>: Decodable {
    let publicKey: T

    private enum Keys: String, CodingKey { case publicKey }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: Keys.self)
        if c.contains(.publicKey) {
            publicKey = try c.decode(T.self, forKey: .publicKey)
        } else {
            publicKey = try T(from: decoder)
        }
    }
}

/// Registration options, parsed from the server's bare options (or a `{"publicKey":{…}}` wrapper).
public struct CreationOptions: Decodable, Equatable, Sendable {
    public let challenge: Data
    public let rpID: String
    public let rpName: String
    public let userID: Data
    public let userName: String
    public let userDisplayName: String

    public init(challenge: Data, rpID: String, rpName: String, userID: Data, userName: String, userDisplayName: String) {
        self.challenge = challenge
        self.rpID = rpID
        self.rpName = rpName
        self.userID = userID
        self.userName = userName
        self.userDisplayName = userDisplayName
    }

    private struct Inner: Decodable {
        struct RP: Decodable { let id: String; let name: String }
        struct User: Decodable { let id: Base64URLData; let name: String; let displayName: String }
        let challenge: Base64URLData
        let rp: RP
        let user: User
    }

    public init(from decoder: Decoder) throws {
        let i = try PublicKeyEnvelope<Inner>(from: decoder).publicKey
        self.init(challenge: i.challenge.data, rpID: i.rp.id, rpName: i.rp.name, userID: i.user.id.data,
                  userName: i.user.name, userDisplayName: i.user.displayName)
    }
}

/// Sign-in options, from `{"challenge","rpId"}` (or a `{"publicKey":{…}}` wrapper).
public struct RequestOptions: Decodable, Equatable, Sendable {
    public let challenge: Data
    public let rpID: String

    public init(challenge: Data, rpID: String) {
        self.challenge = challenge
        self.rpID = rpID
    }

    private struct Inner: Decodable {
        let challenge: Base64URLData
        let rpId: String
    }

    public init(from decoder: Decoder) throws {
        let i = try PublicKeyEnvelope<Inner>(from: decoder).publicKey
        self.init(challenge: i.challenge.data, rpID: i.rpId)
    }
}

private struct CredentialKeys: CodingKey {
    var stringValue: String
    var intValue: Int? { nil }
    init(_ s: String) { stringValue = s }
    init?(stringValue: String) { self.stringValue = stringValue }
    init?(intValue: Int) { nil }
}

public struct RegistrationCredential: Encodable, Equatable, Sendable {
    public let id: Data
    public let clientDataJSON: Data
    public let attestationObject: Data

    public init(id: Data, clientDataJSON: Data, attestationObject: Data) {
        self.id = id
        self.clientDataJSON = clientDataJSON
        self.attestationObject = attestationObject
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CredentialKeys.self)
        try c.encode(Base64URLData(id), forKey: .init("id"))
        try c.encode(Base64URLData(id), forKey: .init("rawId"))
        try c.encode("public-key", forKey: .init("type"))
        var r = c.nestedContainer(keyedBy: CredentialKeys.self, forKey: .init("response"))
        try r.encode(Base64URLData(clientDataJSON), forKey: .init("clientDataJSON"))
        try r.encode(Base64URLData(attestationObject), forKey: .init("attestationObject"))
    }
}

public struct AssertionCredential: Encodable, Equatable, Sendable {
    public let id: Data
    public let clientDataJSON: Data
    public let authenticatorData: Data
    public let signature: Data
    public let userHandle: Data

    public init(id: Data, clientDataJSON: Data, authenticatorData: Data, signature: Data, userHandle: Data) {
        self.id = id
        self.clientDataJSON = clientDataJSON
        self.authenticatorData = authenticatorData
        self.signature = signature
        self.userHandle = userHandle
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CredentialKeys.self)
        try c.encode(Base64URLData(id), forKey: .init("id"))
        try c.encode(Base64URLData(id), forKey: .init("rawId"))
        try c.encode("public-key", forKey: .init("type"))
        var r = c.nestedContainer(keyedBy: CredentialKeys.self, forKey: .init("response"))
        try r.encode(Base64URLData(clientDataJSON), forKey: .init("clientDataJSON"))
        try r.encode(Base64URLData(authenticatorData), forKey: .init("authenticatorData"))
        try r.encode(Base64URLData(signature), forKey: .init("signature"))
        try r.encode(Base64URLData(userHandle), forKey: .init("userHandle"))
    }
}
