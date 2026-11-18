import Foundation

/// A one-time contact link: `https://<host>/c/<token>`, good for 7 days, used once.
public struct NewContactLink: Decodable, Equatable, Sendable {
    public let token: String
    public let expiresAt: Date

    public init(token: String, expiresAt: Date) {
        self.token = token
        self.expiresAt = expiresAt
    }
}

extension ServerClient {
    /// Makes a link. Throws `too_many` (429) at 20 unused, unexpired links.
    public func createContactLink() async throws(ServerError) -> NewContactLink {
        try await request("POST", "/v1/contact-links", auth: .session)
    }

    /// Redeems a link, once. Throws `own_link` (409, the link is not used up), `expired` (410: unknown, malformed,
    /// expired, used, owner gone or owner blocked the caller, all alike) or `rate_limited` (429).
    public func redeemContactLink(token: String) async throws(ServerError) -> UserSummary {
        struct Body: Encodable { let token: String }
        return try await request("POST", "/v1/contact-links/redeem", body: Body(token: token), auth: .session)
    }
}
