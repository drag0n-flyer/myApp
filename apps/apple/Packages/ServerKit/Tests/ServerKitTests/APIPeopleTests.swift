import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@_spi(Testing) @testable import ServerKit
import ServerKitTesting

@Suite struct APIPeopleTests {
    @Test func postMessageSendsExpiresAtAsRFC3339AndOmitsItWhenNil() async throws {
        let t = StubTransport()
        await t.respond("POST", "/v1/conversations/c1/messages", status: 200, json: #"{"seq":5}"#)
        let c = makeAuthedClient(t)
        _ = try await c.postMessage(conversationID: "c1", epoch: 2, ciphertext: Data([1]), escrow: nil, clientMsgID: "m1",
                                    expiresAt: Date(timeIntervalSince1970: 1_800_000_000))
        _ = try await c.postMessage(conversationID: "c1", epoch: 2, ciphertext: Data([1]), escrow: nil, clientMsgID: "m2")
        let reqs = await t.requests
        #expect(try jsonBody(reqs[0]).keys.sorted() == ["ciphertext", "clientMsgID", "epoch", "expiresAt"])
        #expect(try jsonBody(reqs[0])["expiresAt"] as? String == "2027-01-15T08:00:00.000Z")
        #expect(try jsonBody(reqs[1])["expiresAt"] == nil)
    }

    @Test func createContactLink() async throws {
        let t = StubTransport()
        await t.respond("POST", "/v1/contact-links", status: 200, json: #"{"token":"AAAAAAAAAAAAAAAAAAAAAA","expiresAt":"2026-11-25T12:00:00Z"}"#)
        let link = try await makeAuthedClient(t).createContactLink()
        #expect(link.token == "AAAAAAAAAAAAAAAAAAAAAA")
        #expect(link.expiresAt == Date(timeIntervalSince1970: 1_795_608_000))
        let r = await t.requests[0]
        #expect(r.httpMethod == "POST")
        #expect(r.url?.path == "/v1/contact-links")
    }

    @Test func createContactLinkTooMany() async {
        let t = StubTransport()
        await t.respond("POST", "/v1/contact-links", status: 429, json: #"{"error":{"code":"too_many","message":"x"}}"#)
        do { _ = try await makeAuthedClient(t).createContactLink(); Issue.record("expected error") } catch {
            #expect(error.code == "too_many" && error.status == 429)
        }
    }

    @Test func redeemContactLink() async throws {
        let t = StubTransport()
        await t.respond("POST", "/v1/contact-links/redeem", status: 200, json: #"{"accountID":"a1","username":"ana","displayName":"Ana"}"#)
        let who = try await makeAuthedClient(t).redeemContactLink(token: "AAAAAAAAAAAAAAAAAAAAAA")
        #expect(who == UserSummary(accountID: "a1", username: "ana", displayName: "Ana"))
        let r = await t.requests[0]
        #expect(try jsonBody(r).keys.sorted() == ["token"])
        #expect(try jsonBody(r)["token"] as? String == "AAAAAAAAAAAAAAAAAAAAAA")
    }

    @Test(arguments: [("own_link", 409), ("expired", 410), ("rate_limited", 429)])
    func redeemContactLinkErrors(code: String, status: Int) async {
        let t = StubTransport()
        await t.respond("POST", "/v1/contact-links/redeem", status: status, json: #"{"error":{"code":"\#(code)","message":"x"}}"#)
        do { _ = try await makeAuthedClient(t).redeemContactLink(token: "x"); Issue.record("expected error") } catch {
            #expect(error.code == code && error.status == status)
        }
    }

    @Test func ephemeralFrameDecodes() {
        let cipher = Base64URLData(Data([251, 255, 1])).encoded
        let frame = #"{"type":"conv.ephemeral","data":{"conversationID":"cv","epoch":7,"from":{"account":"a","device":"d"},"ciphertext":"\#(cipher)"}}"#
        #expect(decodeFrame(frame) == .event(.conversationEphemeral(
            conversationID: "cv", epoch: 7, fromAccountID: "a", fromDeviceID: "d", ciphertext: Data([251, 255, 1]))))
        #expect(decodeFrame(#"{"type":"conv.ephemeral","data":{"conversationID":"cv","epoch":7}}"#) == .malformed(type: "conv.ephemeral"))
    }
}
