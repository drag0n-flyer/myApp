import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@_spi(Testing) @testable import ServerKit
import ServerKitTesting

@Suite struct APIOtherTests {
    @Test func uploadAttachmentHeaders() async throws {
        let t = StubTransport()
        await t.respond("POST", "/v1/attachments", status: 200, json: #"{"id":"att1","expiresAt":"2026-11-08T12:00:00Z"}"#)
        let bytes = Data(repeating: 7, count: 1234)
        let up = try await makeAuthedClient(t).uploadAttachment(bytes)
        #expect(up.id == "att1")
        let r = await t.requests[0]
        #expect(r.value(forHTTPHeaderField: "Content-Type") == "application/octet-stream")
        #expect(r.value(forHTTPHeaderField: "Content-Length") == "1234")
        #expect(r.httpBody == bytes)
    }

    @Test func downloadRange() async throws {
        let t = StubTransport()
        await t.respond("GET", "/v1/attachments/att1", status: 206, json: "0123456789")
        let data = try await makeAuthedClient(t).downloadAttachment(id: "att1", range: 10...19)
        #expect(data.count == 10)
        let r = await t.requests[0]
        #expect(r.value(forHTTPHeaderField: "Range") == "bytes=10-19")
        #expect(r.httpBody == nil)
    }

    @Test func turnDecodes() async throws {
        let t = StubTransport()
        await t.respond("GET", "/v1/turn", status: 200, json: #"{"iceServers":[{"urls":["stun:h:3478","turn:h:3478?transport=udp","turn:h:3478?transport=tcp","turns:h:443?transport=tcp"],"username":"1:ab","credential":"c"}],"ttl":43200}"#)
        let r = try await makeAuthedClient(t).turnCredentials()
        #expect(r.iceServers[0].urls.count == 4)
        #expect(r.ttl == 43200)
    }

    @Test func turnDisabled() async {
        let t = StubTransport()
        await t.respond("GET", "/v1/turn", status: 503, json: #"{"error":{"code":"calls_disabled","message":"x"}}"#)
        do { _ = try await makeAuthedClient(t).turnCredentials(); Issue.record("expected error") }
        catch { #expect(error.code == "calls_disabled") }
    }

    @Test func pushTokenPath() async throws {
        let t = StubTransport()
        await t.respond("PUT", "/v1/push/tokens/voip", status: 204, json: "")
        await t.respond("DELETE", "/v1/push/tokens/alert", status: 204, json: "")
        let c = makeAuthedClient(t)
        try await c.putPushToken(kind: .voip, sealed: Data([251, 255]))
        try await c.deletePushToken(kind: .alert)
        let reqs = await t.requests
        #expect(reqs[0].httpMethod == "PUT")
        #expect(String(decoding: reqs[0].httpBody ?? Data(), as: UTF8.self) == #"{"sealed":"-_8"}"#)
        #expect(reqs[1].httpMethod == "DELETE")
    }

    @Test func socialEndpoints() async throws {
        let t = StubTransport()
        await t.respond("GET", "/v1/users/ana", status: 200, json: #"{"accountID":"a1","username":"ana","displayName":"Ana"}"#)
        await t.respond("PATCH", "/v1/me/settings", status: 204, json: "")
        await t.respond("PUT", "/v1/blocks/a2", status: 204, json: "")
        await t.respond("DELETE", "/v1/blocks/a2", status: 204, json: "")
        await t.respond("GET", "/v1/blocks", status: 200, json: #"{"accountIDs":["a2"]}"#)
        await t.respond("POST", "/v1/reports", status: 202, json: "{}")
        let c = makeAuthedClient(t)
        #expect(try await c.lookUpUser(username: "ana").accountID == "a1")
        try await c.updateSettings(displayName: nil, findByEmail: true)
        try await c.block(accountID: "a2")
        try await c.unblock(accountID: "a2")
        #expect(try await c.blocks() == ["a2"])
        try await c.report(accountID: "a2", conversationID: nil, reason: "spam",
                           messages: [ReportedMessage(sentAt: Date(timeIntervalSince1970: 0), text: "hi")], note: nil)
        let reqs = await t.requests
        #expect(try jsonBody(reqs[1]).keys.sorted() == ["findByEmail"])
        let rep = try jsonBody(reqs[5])
        #expect(rep["conversationID"] == nil && rep["note"] == nil)
        #expect(rep["reason"] as? String == "spam")
    }
}
