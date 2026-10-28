import Foundation

private func seg(_ s: String) -> String {
    s.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? s
}
private struct Count: Decodable { let count: Int }
private struct Seq: Decodable { let seq: Int64 }
private struct EpochReply: Decodable { let epoch: UInt64 }
private struct InboxPage: Decodable { let items: [InboxItem]; let more: Bool }

extension ServerClient {
    public func uploadKeyPackages(_ packages: [Data]) async throws(ServerError) -> Int {
        struct Body: Encodable { let keyPackages: [Base64URLData] }
        let r: Count = try await request("POST", "/v1/keypackages",
                                         body: Body(keyPackages: packages.map { Base64URLData($0) }), auth: .session)
        return r.count
    }

    public func keyPackageCount() async throws(ServerError) -> Int {
        let r: Count = try await request("GET", "/v1/keypackages/count", auth: .session)
        return r.count
    }

    /// `deviceIDs` limits the claim to those devices of the account (omitted: every device).
    public func claimKeyPackages(accountID: String, deviceIDs: [String]? = nil) async throws(ServerError) -> [ClaimedKeyPackage] {
        struct Body: Encodable { let accountID: String; let deviceIDs: [String]? }
        struct Entry: Decodable { let deviceID: String; let keyPackage: Base64URLData }
        struct Reply: Decodable { let keyPackages: [Entry] }
        let r: Reply = try await request("POST", "/v1/keypackages/claim", body: Body(accountID: accountID, deviceIDs: deviceIDs), auth: .session)
        return r.keyPackages.map { ClaimedKeyPackage(deviceID: $0.deviceID, keyPackage: $0.keyPackage.data) }
    }

    public func createConversation(with accountID: String) async throws(ServerError) -> NewConversation {
        struct Body: Encodable { let members: [String] }
        return try await request("POST", "/v1/conversations", body: Body(members: [accountID]), auth: .session)
    }

    /// A device that joined by Welcome learns the mode here.
    public func conversation(id: String) async throws(ServerError) -> NewConversation {
        try await request("GET", "/v1/conversations/\(seg(id))", auth: .session)
    }

    /// Returns the new epoch. On `stale_epoch` the thrown error's `details["currentEpoch"]` is set.
    public func postCommit(conversationID: String, epoch: UInt64, commit: Data, welcome: Data?,
                           addDevices: [String], removeDevices: [String]) async throws(ServerError) -> UInt64 {
        struct Body: Encodable {
            let epoch: UInt64
            let commit: Base64URLData
            let welcome: Base64URLData?
            let addDevices: [String]?
            let removeDevices: [String]?
        }
        let body = Body(epoch: epoch, commit: Base64URLData(commit), welcome: welcome.map { Base64URLData($0) },
                        addDevices: addDevices.isEmpty ? nil : addDevices,
                        removeDevices: removeDevices.isEmpty ? nil : removeDevices)
        let r: EpochReply = try await request("POST", "/v1/conversations/\(seg(conversationID))/commits", body: body, auth: .session)
        return r.epoch
    }

    /// `clientMsgID` must be unique per conversation; a retry with the same value is idempotent.
    public func postMessage(conversationID: String, epoch: UInt64, ciphertext: Data, escrow: Data?,
                            clientMsgID: String) async throws(ServerError) -> Int64 {
        struct Body: Encodable {
            let epoch: UInt64
            let ciphertext: Base64URLData
            let escrow: Base64URLData?
            let clientMsgID: String
        }
        let body = Body(epoch: epoch, ciphertext: Base64URLData(ciphertext), escrow: escrow.map { Base64URLData($0) },
                        clientMsgID: clientMsgID)
        let r: Seq = try await request("POST", "/v1/conversations/\(seg(conversationID))/messages", body: body, auth: .session)
        return r.seq
    }

    public func inbox(after: Int64?, limit: Int = 100) async throws(ServerError) -> (items: [InboxItem], more: Bool) {
        var path = "/v1/inbox?"
        if let after { path += "after=\(after)&" }
        path += "limit=\(limit)"
        let r: InboxPage = try await request("GET", path, auth: .session)
        return (r.items, r.more)
    }

    public func ackInbox(upTo: Int64) async throws(ServerError) {
        struct Body: Encodable { let upTo: Int64 }
        try await requestNoContent("POST", "/v1/inbox/ack", body: Body(upTo: upTo), auth: .session)
    }
}
