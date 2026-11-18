import AppCore
import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

@MainActor
private struct Side {
    let device: TestDevice
    let outbox: Outbox
    let store: LiveChatStore
    var repo: Repository { device.deps.repo }

    init(_ device: TestDevice) {
        self.device = device
        let deps = device.deps
        let conversations = ConversationManager(deps: deps, escrowKey: { Data(repeating: 7, count: 32) })
        outbox = Outbox(deps: deps, conversations: conversations, readReceiptsEnabled: { true })
        let inbox = InboxProcessor(
            deps: deps, conversations: conversations, handler: IncomingHandler(me: deps.me, now: deps.now, log: { _ in }),
            controls: outbox)
        store = LiveChatStore(deps: deps, outbox: outbox, inbox: inbox, conversations: conversations, attachments: nil)
    }

    /// A contact with a chat that exists only on this device (no conversation behind it).
    func addLocalContact(_ id: String, isContact: Bool = true) throws {
        try repo.upsertContact(ContactRecord(accountID: id, username: id, displayName: id, isContact: isContact, addedAt: testEpochDate))
        try repo.upsertChat(ChatRecord(id: id, isRequest: !isContact, unread: 0, muted: false, lastActivity: testEpochDate))
    }

    func sentVersion(_ chat: String) throws -> Int? {
        try repo.get(LiveChatStore.profileSentKey(chat)).flatMap { Int(String(decoding: $0, as: UTF8.self)) }
    }
}

/// A JPEG that passes `ProfilePhotoRule`: a 256 x 256 frame header and padding.
private let jpeg = Data([0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x0B, 0x08, 0x01, 0x00, 0x01, 0x00, 0x01, 0x01, 0x11, 0x00]) + Data(count: 100)
/// The first version is the clock in seconds; the next ones are one above the one before.
private let t0 = Int(testEpochDate.timeIntervalSince1970)

@Suite("TestOwnProfile") @MainActor struct TestOwnProfile {
    private func make() async throws -> (ana: Side, bob: Side) {
        let server = FakeServer()
        let anaDevice = await makeDevice(server, account: "acc-ana", username: "ana")
        let bobDevice = await makeDevice(server, account: "acc-bob", username: "bob")
        for device in [anaDevice, bobDevice] {
            _ = try await device.deps.server.uploadKeyPackages(try await device.deps.mls.makeKeyPackages(count: 3))
        }
        let ana = Side(anaDevice), bob = Side(bobDevice)
        _ = await ana.store.addContact(username: "bob")
        _ = ana.store.ensureChat(with: "acc-bob")
        return (ana, bob)
    }

    @Test func aNameChangeSendsOnceToEveryOneToOneChat() async throws {
        let (ana, _) = try await make()
        try ana.addLocalContact("acc-cy")
        try ana.addLocalContact("acc-dee")
        ana.store.setMyDisplayName("Ana")
        for chat in ["acc-bob", "acc-cy", "acc-dee"] { #expect(try ana.sentVersion(chat) == t0) }
        // Nothing changed: nothing is sent again.
        ana.store.setMyDisplayName("Ana")
        ana.store.sendOwnProfileIfNeeded()
        for chat in ["acc-bob", "acc-cy", "acc-dee"] { #expect(try ana.sentVersion(chat) == t0) }
        // A queued profile for a chat that cannot send yet is still there once, not twice.
        let queued = try ana.repo.dueOutbox(at: .distantFuture).filter { $0.chatID == "acc-cy" }
        #expect(queued.count == 1)
        guard case .profile(let name, let photo, let ver) = ContentEnvelope.decode(try #require(queued.first).envelope).body else {
            Issue.record("expected a profile"); return
        }
        #expect(name == "Ana" && photo == nil && ver == t0)
    }

    @Test func aPhotoChangeBumpsTheVersionAndReachesThePeer() async throws {
        let (ana, bob) = try await make()
        ana.store.setMyDisplayName("Ana")
        #expect(ana.store.setMyPhoto(jpeg))
        #expect(ana.store.myPhoto == jpeg)
        #expect(try ana.sentVersion("acc-bob") == t0 + 1)
        await ana.store.settle()
        await bob.store.syncInbox()
        await bob.store.settle()
        #expect(bob.store.profile(for: "acc-ana") == StoredProfile(accountID: "acc-ana", name: "Ana", photo: jpeg, ver: t0 + 1))
        #expect(try bob.repo.messages(chatID: "acc-ana", before: nil, limit: 10).isEmpty)
    }

    @Test func removingThePhotoSendsAProfileWithoutOne() async throws {
        let (ana, bob) = try await make()
        ana.store.setMyDisplayName("Ana")
        #expect(ana.store.setMyPhoto(jpeg))
        #expect(ana.store.setMyPhoto(nil))
        #expect(ana.store.myPhoto == nil)
        #expect(try ana.sentVersion("acc-bob") == t0 + 2)
        await ana.store.settle()
        await bob.store.syncInbox()
        await bob.store.settle()
        #expect(bob.store.profile(for: "acc-ana") == StoredProfile(accountID: "acc-ana", name: "Ana", photo: nil, ver: t0 + 2))
    }

    @Test func removingWhenThereIsNoPhotoChangesNothing() async throws {
        let (ana, _) = try await make()
        ana.store.setMyDisplayName("Ana")
        #expect(ana.store.setMyPhoto(nil))
        #expect(try ana.sentVersion("acc-bob") == t0)
    }

    @Test func aBlockedContactAndAMessageRequestGetNothing() async throws {
        let (ana, _) = try await make()
        try ana.addLocalContact("acc-bad")
        try ana.addLocalContact("acc-stranger", isContact: false)
        try ana.repo.block("acc-bad")
        ana.store.setMyDisplayName("Ana")
        #expect(try ana.sentVersion("acc-bad") == nil)
        #expect(try ana.sentVersion("acc-stranger") == nil)
        #expect(try ana.sentVersion("acc-bob") == t0)
        // Accepting the request sends the profile then, once.
        ana.store.accept(requestID: "acc-stranger")
        #expect(try ana.sentVersion("acc-stranger") == t0)
    }

    @Test func aPhotoThatIsNotAJPEGOrIsTooBigIsRefused() async throws {
        let (ana, _) = try await make()
        ana.store.setMyDisplayName("Ana")
        #expect(!ana.store.setMyPhoto(Data([1, 2, 3, 4])))
        #expect(!ana.store.setMyPhoto(Data([0xFF, 0xD8]) + Data(count: 24_576)))
        #expect(ana.store.myPhoto == nil)
        #expect(try ana.sentVersion("acc-bob") == t0)
    }

    @Test func noNameNoProfile() async throws {
        let (ana, _) = try await make()
        #expect(ana.store.setMyPhoto(jpeg))
        #expect(try ana.sentVersion("acc-bob") == nil)
        // The name arrives later: the first profile carries both.
        ana.store.setMyDisplayName("Ana")
        #expect(try ana.sentVersion("acc-bob") == t0)
        let queued = try ana.repo.dueOutbox(at: .distantFuture).filter { $0.chatID == "acc-bob" }
        let bodies = queued.map { ContentEnvelope.decode($0.envelope).body }
        if let first = bodies.first, case .profile(_, let photo, let ver) = first { #expect(photo == jpeg && ver == t0) }
    }

    @Test func theProfileNameAndPhotoShowForTheContact() async throws {
        let (ana, bob) = try await make()
        try bob.repo.upsertContact(ContactRecord(accountID: "acc-ana", username: "ana", displayName: "ana", isContact: true, addedAt: testEpochDate))
        ana.store.setMyDisplayName("Ana Lopez")
        #expect(ana.store.setMyPhoto(jpeg))
        await ana.store.settle()
        await bob.store.syncInbox()
        await bob.store.settle()
        #expect(bob.store.contact(id: "acc-ana")?.displayName == "Ana Lopez")
        #expect(bob.store.profilePhoto(for: "acc-ana") == jpeg)
        #expect(bob.store.profilePhoto(for: "acc-nobody") == nil)
    }
}
