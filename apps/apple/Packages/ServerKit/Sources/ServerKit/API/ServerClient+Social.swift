import Foundation

private func seg(_ s: String) -> String {
    s.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? s
}

extension ServerClient {
    public func lookUpUser(username: String) async throws(ServerError) -> UserSummary {
        try await request("GET", "/v1/users/\(seg(username))", auth: .session)
    }

    public func discover(email: String) async throws(ServerError) -> UserSummary {
        struct Body: Encodable { let email: String }
        return try await request("POST", "/v1/discover/email", body: Body(email: email), auth: .session)
    }

    public func updateSettings(displayName: String?, findByEmail: Bool?) async throws(ServerError) {
        struct Body: Encodable { let displayName: String?; let findByEmail: Bool? }
        try await requestNoContent("PATCH", "/v1/me/settings", body: Body(displayName: displayName, findByEmail: findByEmail), auth: .session)
    }

    public func block(accountID: String) async throws(ServerError) {
        try await requestNoContent("PUT", "/v1/blocks/\(seg(accountID))", auth: .session)
    }

    public func unblock(accountID: String) async throws(ServerError) {
        try await requestNoContent("DELETE", "/v1/blocks/\(seg(accountID))", auth: .session)
    }

    public func blocks() async throws(ServerError) -> [String] {
        struct Reply: Decodable { let accountIDs: [String] }
        let r: Reply = try await request("GET", "/v1/blocks", auth: .session)
        return r.accountIDs
    }

    /// `reason` is `spam`, `abuse` or `other`. At most 50 messages, shared with the person's consent.
    public func report(accountID: String, conversationID: String?, reason: String,
                       messages: [ReportedMessage], note: String?) async throws(ServerError) {
        struct Body: Encodable {
            let accountID: String; let conversationID: String?; let reason: String
            let messages: [ReportedMessage]; let note: String?
        }
        try await requestNoContent("POST", "/v1/reports",
                                   body: Body(accountID: accountID, conversationID: conversationID, reason: reason,
                                              messages: messages, note: note), auth: .session)
    }
}
