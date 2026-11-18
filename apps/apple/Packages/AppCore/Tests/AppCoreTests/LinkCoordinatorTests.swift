import Foundation
import Testing
@testable import AppCore

@MainActor private final class FakeServices: LinkServices {
    var ownUsername = "myself"
    var contacts: [String: String] = [:]          // username -> contact id
    var redeemResult: Result<RedeemedPerson, LinkRedeemFailure> = .success(
        RedeemedPerson(accountID: "a-bob", username: "bob", displayName: "Bob"))
    private(set) var redeemed: [String] = []
    private(set) var redeemCalls = 0

    func contactID(username: String) -> String? { contacts[username] }
    func redeem(token: String) async throws(LinkRedeemFailure) -> RedeemedPerson {
        redeemCalls += 1
        redeemed.append(token)
        return try redeemResult.get()
    }
}

@MainActor @Suite struct LinkCoordinatorTests {
    private let host = "chat.example.org"
    private let token = "AbCdEfGhIjKlMnOpQrStUv"

    private func url(_ path: String, host: String = "chat.example.org") -> URL { URL(string: "https://\(host)\(path)")! }

    @Test func userLinkAsksToAdd() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/u/alice"), serverHost: host)
        await c.process(ready: true, services: s)
        #expect(c.outcome == .confirmAdd(username: "alice", displayName: nil))
        #expect(s.redeemCalls == 0)
    }

    @Test func inviteRedeemsThenAsksToAdd() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/c/\(token)"), serverHost: host)
        await c.process(ready: true, services: s)
        #expect(s.redeemed == [token])
        #expect(c.outcome == .confirmAdd(username: "bob", displayName: "Bob"))
    }

    @Test func ownUserLink() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/u/myself"), serverHost: host)
        await c.process(ready: true, services: s)
        #expect(c.outcome == .message(.ownLink))
    }

    @Test func ownInviteLink() async {
        let s = FakeServices(), c = LinkCoordinator()
        s.redeemResult = .failure(.ownLink)
        c.receive(url("/c/\(token)"), serverHost: host)
        await c.process(ready: true, services: s)
        #expect(c.outcome == .message(.ownLink))
    }

    @Test func expiredAndBusy() async {
        let s = FakeServices(), c = LinkCoordinator()
        s.redeemResult = .failure(.expired)
        c.receive(url("/c/\(token)"), serverHost: host)
        await c.process(ready: true, services: s)
        #expect(c.outcome == .message(.expired))
        s.redeemResult = .failure(.tryAgain)
        c.receive(url("/c/\(token)"), serverHost: host)
        await c.process(ready: true, services: s)
        #expect(c.outcome == .message(.tryAgain))
    }

    @Test func otherServerSendsNothing() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/c/\(token)", host: "evil.example.com"), serverHost: host)
        await c.process(ready: true, services: s)
        #expect(c.outcome == .message(.otherServer))
        #expect(s.redeemCalls == 0)
    }

    @Test func alreadyAContactOpensTheChat() async {
        let s = FakeServices(), c = LinkCoordinator()
        s.contacts["alice"] = "id-alice"
        c.receive(url("/u/alice"), serverHost: host)
        await c.process(ready: true, services: s)
        #expect(c.outcome == .openChat(contactID: "id-alice"))
        // After a redeem too.
        s.contacts["bob"] = "id-bob"
        c.receive(url("/c/\(token)"), serverHost: host)
        await c.process(ready: true, services: s)
        #expect(c.outcome == .openChat(contactID: "id-bob"))
    }

    @Test func notALinkIsIgnored() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(URL(string: "http://chat.example.org/u/alice")!, serverHost: host)
        c.receive(url("/nothing"), serverHost: host)
        #expect(!c.hasPending)
        await c.process(ready: true, services: s)
        #expect(c.outcome == nil)
    }

    @Test func keptWhileNotReadyThenHandledOnce() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/c/\(token)"), serverHost: host)
        await c.process(ready: false, services: s)       // locked or signed out
        #expect(s.redeemCalls == 0)
        #expect(c.outcome == nil)
        #expect(c.hasPending)
        await c.process(ready: true, services: s)
        #expect(s.redeemCalls == 1)
        #expect(!c.hasPending)
        await c.process(ready: true, services: s)       // nothing left: not handled twice
        #expect(s.redeemCalls == 1)
    }

    @Test func latestWins() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/u/alice"), serverHost: host)
        c.receive(url("/c/\(token)"), serverHost: host)
        c.receive(url("/u/carol"), serverHost: host)
        await c.process(ready: true, services: s)
        #expect(c.outcome == .confirmAdd(username: "carol", displayName: nil))
        #expect(s.redeemCalls == 0)
    }

    @Test func aNotALinkDoesNotReplaceAPendingOne() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/u/alice"), serverHost: host)
        c.receive(url("/junk"), serverHost: host)
        await c.process(ready: true, services: s)
        #expect(c.outcome == .confirmAdd(username: "alice", displayName: nil))
    }

    @Test func tokenIsDroppedAfterRedeem() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/c/\(token)"), serverHost: host)
        await c.process(ready: true, services: s)
        #expect(!c.hasPending)
        #expect(!"\(c)".contains(token))
        #expect(!String(describing: c.outcome as Any).contains(token))
    }

    @Test func dismissClearsTheOutcome() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/u/alice"), serverHost: host)
        await c.process(ready: true, services: s)
        c.dismiss()
        #expect(c.outcome == nil)
    }

    @Test func clearDropsAPendingLink() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/c/\(token)"), serverHost: host)
        c.clear()      // signed out
        await c.process(ready: true, services: s)
        #expect(s.redeemCalls == 0)
    }

    @Test func messagesUseTheSpecWords() {
        #expect(LinkMessage.ownLink.text == "This is your own link.")
        #expect(LinkMessage.expired.text == "This link has expired. Ask for a new one.")
        #expect(LinkMessage.otherServer.text == "This link is for a different server.")
    }
}
