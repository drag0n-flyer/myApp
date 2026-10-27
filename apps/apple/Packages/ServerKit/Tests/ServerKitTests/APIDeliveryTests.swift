import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@_spi(Testing) @testable import ServerKit
import ServerKitTesting

func makeAuthedClient(_ t: StubTransport) -> ServerClient {
    let store = SessionStore(storage: MemorySessionStorage())
    try? store.save(Session(host: "chat.test", accountID: "a", deviceID: "d", token: "tok"))
    return ServerClient(baseURL: URL(string: "https://chat.test")!, session: store, transport: t)
}

func jsonBody(_ r: URLRequest) throws -> [String: Any] {
    try JSONSerialization.jsonObject(with: r.httpBody ?? Data()) as? [String: Any] ?? [:]
}

@Suite struct APIDeliveryTests {
    @Test func inboxItemDecodes() throws {
        let json = #"{"cursor":7,"kind":"message","conversationID":"c1","seq":1,"epoch":2,"senderAccountID":"a2","senderDeviceID":"d2","payload":"AQID","sentAt":"2026-11-01T12:00:00.5Z"}"#
        let item = try ServerJSON.decoder().decode(InboxItem.self, from: Data(json.utf8))
        #expect(item.payload == Data([1, 2, 3]))
        #expect(item.seq == 1)
        #expect(item.kind == "message")
        let commit = json.replacingOccurrences(of: #""seq":1,"#, with: "").replacingOccurrences(of: "message", with: "commit")
        let c = try ServerJSON.decoder().decode(InboxItem.self, from: Data(commit.utf8))
        #expect(c.seq == nil)
        #expect(c.kind == "commit")
    }

    @Test func postCommitStaleEpochDetails() async {
        let t = StubTransport()
        await t.respond("POST", "/v1/conversations/c1/commits", status: 409,
                        json: #"{"error":{"code":"stale_epoch","message":"x","details":{"currentEpoch":4}}}"#)
        do {
            _ = try await makeAuthedClient(t).postCommit(conversationID: "c1", epoch: 2, commit: Data([1]), welcome: nil, addDevices: [], removeDevices: [])
            Issue.record("expected error")
        } catch {
            #expect(error.code == "stale_epoch")
            #expect(error.details?["currentEpoch"] == 4)
        }
    }

    @Test func postCommitBodyAndEpoch() async throws {
        let t = StubTransport()
        await t.respond("POST", "/v1/conversations/c1/commits", status: 200, json: #"{"epoch":3}"#)
        let e = try await makeAuthedClient(t).postCommit(conversationID: "c1", epoch: 2, commit: Data([1]), welcome: Data([2]), addDevices: ["d2"], removeDevices: [])
        #expect(e == 3)
        let b = try jsonBody(await t.requests[0])
        #expect(b["welcome"] as? String == "Ag")
        #expect(b["addDevices"] as? [String] == ["d2"])
        #expect(b["removeDevices"] == nil)
    }

    @Test func postMessageOmitsNilEscrow() async throws {
        let t = StubTransport()
        await t.respond("POST", "/v1/conversations/c1/messages", status: 200, json: #"{"seq":5}"#)
        let c = makeAuthedClient(t)
        #expect(try await c.postMessage(conversationID: "c1", epoch: 2, ciphertext: Data([1]), escrow: nil, clientMsgID: "m1") == 5)
        _ = try await c.postMessage(conversationID: "c1", epoch: 2, ciphertext: Data([1]), escrow: Data([9]), clientMsgID: "m2")
        let reqs = await t.requests
        #expect(try jsonBody(reqs[0]).keys.sorted() == ["ciphertext", "clientMsgID", "epoch"])
        #expect(try jsonBody(reqs[1])["escrow"] as? String == "CQ")
    }

    @Test func inboxQuery() async throws {
        let t = StubTransport()
        await t.respond("GET", "/v1/inbox", status: 200, json: #"{"items":[],"more":true}"#)
        let c = makeAuthedClient(t)
        let r = try await c.inbox(after: 99, limit: 100)
        #expect(r.more == true)
        _ = try await c.inbox(after: nil)
        let urls = await t.requests.map { $0.url!.absoluteString }
        #expect(urls == ["https://chat.test/v1/inbox?after=99&limit=100", "https://chat.test/v1/inbox?limit=100"])
    }

    @Test func conversationsAndKeyPackages() async throws {
        let t = StubTransport()
        await t.respond("POST", "/v1/conversations", status: 200, json: #"{"conversationID":"c1","mode":"e2ee","epoch":0}"#)
        await t.respond("GET", "/v1/conversations/c1", status: 200, json: #"{"conversationID":"c1","mode":"e2ee","epoch":3}"#)
        await t.respond("POST", "/v1/keypackages/claim", status: 200, json: #"{"keyPackages":[{"deviceID":"d2","keyPackage":"AQI"}]}"#)
        await t.respond("POST", "/v1/keypackages", status: 200, json: #"{"count":2}"#)
        await t.respond("POST", "/v1/inbox/ack", status: 204, json: "")
        let c = makeAuthedClient(t)
        let n = try await c.createConversation(with: "a2")
        #expect(n.mode == "e2ee" && n.epoch == 0)
        #expect(try jsonBody(await t.requests[0])["members"] as? [String] == ["a2"])
        #expect(try await c.conversation(id: "c1").epoch == 3)
        #expect(try await c.claimKeyPackages(accountID: "a2") == [ClaimedKeyPackage(deviceID: "d2", keyPackage: Data([1, 2]))])
        #expect(try await c.uploadKeyPackages([Data([1])]) == 2)
        try await c.ackInbox(upTo: 7)
    }
}
