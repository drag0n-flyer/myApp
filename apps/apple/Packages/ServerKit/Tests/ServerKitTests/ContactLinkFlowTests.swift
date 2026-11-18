import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import AppCore
import Testing
@_spi(Testing) @testable import ServerKit
import ServerKitTesting

@Suite struct ContactLinkFlowTests {
    private let token = "AbCdEfGhIjKlMnOpQrStUv"

    private func redeem(status: Int, json: String) async -> Result<RedeemedPerson, LinkRedeemFailure> {
        let t = StubTransport()
        await t.respond("POST", "/v1/contact-links/redeem", status: status, json: json)
        do { return .success(try await makeAuthedClient(t).redeemLink(token: token)) } catch { return .failure(error) }
    }

    @Test func redeemGivesThePerson() async {
        let r = await redeem(status: 200, json: #"{"accountID":"a1","username":"ana","displayName":"Ana"}"#)
        #expect(r == .success(RedeemedPerson(accountID: "a1", username: "ana", displayName: "Ana")))
    }

    @Test func redeemErrorsMap() async {
        #expect(await redeem(status: 409, json: #"{"error":{"code":"own_link","message":"x"}}"#) == .failure(.ownLink))
        #expect(await redeem(status: 410, json: #"{"error":{"code":"expired","message":"x"}}"#) == .failure(.expired))
        #expect(await redeem(status: 429, json: #"{"error":{"code":"rate_limited","message":"x"}}"#) == .failure(.tryAgain))
        #expect(await redeem(status: 500, json: #"{"error":{"code":"internal","message":"x"}}"#) == .failure(.tryAgain))
    }

    @Test func inviteURLFromTheNewLink() async throws {
        let t = StubTransport()
        await t.respond("POST", "/v1/contact-links", status: 200, json: #"{"token":"\#(token)","expiresAt":"2026-11-25T12:00:00Z"}"#)
        let url = try await makeAuthedClient(t).newInviteLinkURL(host: "chat.example.org")
        #expect(url.absoluteString == "https://chat.example.org/c/\(token)")
    }

    @Test func tooManyAndOtherFailures() async {
        let t = StubTransport()
        await t.respond("POST", "/v1/contact-links", status: 429, json: #"{"error":{"code":"too_many","message":"x"}}"#)
        do { _ = try await makeAuthedClient(t).newInviteLinkURL(host: "h.test"); Issue.record("expected error") } catch {
            #expect(error == .tooMany)
        }
        let u = StubTransport()
        await u.respond("POST", "/v1/contact-links", status: 200, json: #"{"token":"short","expiresAt":"2026-11-25T12:00:00Z"}"#)
        do { _ = try await makeAuthedClient(u).newInviteLinkURL(host: "h.test"); Issue.record("expected error") } catch {
            #expect(error == .failed)
        }
    }
}
