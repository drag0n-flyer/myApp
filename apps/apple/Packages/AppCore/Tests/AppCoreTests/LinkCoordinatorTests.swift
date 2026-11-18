import Foundation
import Testing
@testable import AppCore

@MainActor private final class FakeServices: LinkServices {
    var ownUsername = "myself"
    var linkHost = "chat.example.org"
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

@MainActor private final class SlowServices: LinkServices {
    var ownUsername = "myself"
    var linkHost = "chat.example.org"
    private var release: CheckedContinuation<Void, Never>?
    private var begun: CheckedContinuation<Void, Never>?
    private var hasBegun = false
    func contactID(username: String) -> String? { nil }
    func redeem(token: String) async throws(LinkRedeemFailure) -> RedeemedPerson {
        hasBegun = true
        begun?.resume(); begun = nil
        await withCheckedContinuation { release = $0 }
        return RedeemedPerson(accountID: "a", username: "bob", displayName: "Bob")
    }
    func started() async { if !hasBegun { await withCheckedContinuation { begun = $0 } } }
    func finish() { release?.resume(); release = nil }
}

@MainActor @Suite struct LinkCoordinatorTests {
    private let host = "chat.example.org"
    private let token = "AbCdEfGhIjKlMnOpQrStUv"

    private func url(_ path: String, host: String = "chat.example.org") -> URL { URL(string: "https://\(host)\(path)")! }

    @Test func userLinkAsksToAdd() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/u/alice"))
        await c.process(ready: { true }, services: s)
        #expect(c.outcome == .confirmAdd(username: "alice", displayName: nil))
        #expect(s.redeemCalls == 0)
    }

    @Test func inviteRedeemsThenAsksToAdd() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/c/\(token)"))
        await c.process(ready: { true }, services: s)
        #expect(s.redeemed == [token])
        #expect(c.outcome == .confirmAdd(username: "bob", displayName: "Bob"))
    }

    @Test func ownUserLink() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/u/myself"))
        await c.process(ready: { true }, services: s)
        #expect(c.outcome == .message(.ownLink))
    }

    @Test func ownInviteLink() async {
        let s = FakeServices(), c = LinkCoordinator()
        s.redeemResult = .failure(.ownLink)
        c.receive(url("/c/\(token)"))
        await c.process(ready: { true }, services: s)
        #expect(c.outcome == .message(.ownLink))
    }

    @Test func expiredAndBusy() async {
        let s = FakeServices(), c = LinkCoordinator()
        s.redeemResult = .failure(.expired)
        c.receive(url("/c/\(token)"))
        await c.process(ready: { true }, services: s)
        #expect(c.outcome == .message(.expired))
        c.dismiss()
        s.redeemResult = .failure(.tryAgain)
        c.receive(url("/c/\(token)"))
        await c.process(ready: { true }, services: s)
        #expect(c.outcome == .message(.tryAgain))
    }

    @Test func otherServerSendsNothing() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/c/\(token)", host: "evil.example.com"))
        await c.process(ready: { true }, services: s)
        #expect(c.outcome == .message(.otherServer))
        #expect(s.redeemCalls == 0)
    }

    @Test func alreadyAContactOpensTheChat() async {
        let s = FakeServices(), c = LinkCoordinator()
        s.contacts["alice"] = "id-alice"
        c.receive(url("/u/alice"))
        await c.process(ready: { true }, services: s)
        #expect(c.outcome == .openChat(contactID: "id-alice"))
        c.dismiss()
        // After a redeem too.
        s.contacts["bob"] = "id-bob"
        c.receive(url("/c/\(token)"))
        await c.process(ready: { true }, services: s)
        #expect(c.outcome == .openChat(contactID: "id-bob"))
    }

    @Test func notALinkIsIgnored() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(URL(string: "http://chat.example.org/u/alice")!)
        c.receive(url("/nothing"))
        #expect(!c.hasPending)
        await c.process(ready: { true }, services: s)
        #expect(c.outcome == nil)
    }

    @Test func keptWhileNotReadyThenHandledOnce() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/c/\(token)"))
        await c.process(ready: { false }, services: s)       // locked or signed out
        #expect(s.redeemCalls == 0)
        #expect(c.outcome == nil)
        #expect(c.hasPending)
        await c.process(ready: { true }, services: s)
        #expect(s.redeemCalls == 1)
        #expect(!c.hasPending)
        await c.process(ready: { true }, services: s)       // nothing left: not handled twice
        #expect(s.redeemCalls == 1)
    }

    @Test func latestWins() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/u/alice"))
        c.receive(url("/c/\(token)"))
        c.receive(url("/u/carol"))
        await c.process(ready: { true }, services: s)
        #expect(c.outcome == .confirmAdd(username: "carol", displayName: nil))
        #expect(s.redeemCalls == 0)
    }

    @Test func aNotALinkDoesNotReplaceAPendingOne() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/u/alice"))
        c.receive(url("/junk"))
        await c.process(ready: { true }, services: s)
        #expect(c.outcome == .confirmAdd(username: "alice", displayName: nil))
    }

    @Test func tokenIsDroppedAfterRedeem() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/c/\(token)"))
        await c.process(ready: { true }, services: s)
        #expect(!c.hasPending)
        #expect(!"\(c)".contains(token))
        #expect(!String(describing: c.outcome as Any).contains(token))
    }

    @Test func hostIsJudgedWhenProcessedNotWhenReceived() async {
        let s = FakeServices(), c = LinkCoordinator()
        s.linkHost = "build-default.example"
        c.receive(url("/u/alice"))                 // tapped before sign-in
        s.linkHost = "chat.example.org"            // the server the person then joined
        await c.process(ready: { true }, services: s)
        #expect(c.outcome == .confirmAdd(username: "alice", displayName: nil))
    }

    @Test func aLinkForTheOldHostIsOtherServerAfterSignIn() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/c/\(token)", host: "build-default.example"))
        await c.process(ready: { true }, services: s)
        #expect(c.outcome == .message(.otherServer))
        #expect(s.redeemCalls == 0)
    }

    @Test func aRedeemThatReturnsAfterClearIsDiscarded() async {
        let c = LinkCoordinator()
        let s = SlowServices()
        c.receive(url("/c/\(token)"))
        let task = Task { await c.process(ready: { true }, services: s) }
        await s.started()
        c.clear()                                   // sign-out while the redeem is out
        s.finish()
        await task.value
        #expect(c.outcome == nil)
    }

    @Test func aNewLinkWaitsWhileAnOutcomeIsShown() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/u/alice"))
        await c.process(ready: { true }, services: s)
        c.receive(url("/u/carol"))
        await c.process(ready: { true }, services: s)
        #expect(c.outcome == .confirmAdd(username: "alice", displayName: nil))
        #expect(c.hasPending)
        c.dismiss()
        await c.process(ready: { true }, services: s)
        #expect(c.outcome == .confirmAdd(username: "carol", displayName: nil))
    }

    @Test func readinessIsCheckedOnEveryPass() async {
        let c = LinkCoordinator()
        let s = SlowServices()
        var ready = true
        c.receive(url("/c/\(token)"))
        let task = Task { await c.process(ready: { ready }, services: s) }
        await s.started()
        c.receive(url("/u/alice"))     // arrives during the redeem
        ready = false                  // and the app locks
        s.finish()
        await task.value
        #expect(c.hasPending)
        #expect(c.outcome == .confirmAdd(username: "bob", displayName: "Bob"))
    }

    @Test func dismissClearsTheOutcome() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/u/alice"))
        await c.process(ready: { true }, services: s)
        c.dismiss()
        #expect(c.outcome == nil)
    }

    @Test func clearDropsAPendingLink() async {
        let s = FakeServices(), c = LinkCoordinator()
        c.receive(url("/c/\(token)"))
        c.clear()      // signed out
        await c.process(ready: { true }, services: s)
        #expect(s.redeemCalls == 0)
    }

    @Test func messagesUseTheSpecWords() {
        #expect(LinkMessage.ownLink.text == "This is your own link.")
        #expect(LinkMessage.expired.text == "This link has expired. Ask for a new one.")
        #expect(LinkMessage.otherServer.text == "This link is for a different server.")
    }
}
