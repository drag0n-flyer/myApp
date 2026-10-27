import Foundation

extension ServerClient {
    /// `sealed` is the push handle sealed to the gateway key; the server cannot read it.
    public func putPushToken(kind: PushTokenKind, sealed: Data) async throws(ServerError) {
        struct Body: Encodable { let sealed: Base64URLData }
        try await requestNoContent("PUT", "/v1/push/tokens/\(kind.rawValue)", body: Body(sealed: Base64URLData(sealed)), auth: .session)
    }

    public func deletePushToken(kind: PushTokenKind) async throws(ServerError) {
        try await requestNoContent("DELETE", "/v1/push/tokens/\(kind.rawValue)", auth: .session)
    }
}
