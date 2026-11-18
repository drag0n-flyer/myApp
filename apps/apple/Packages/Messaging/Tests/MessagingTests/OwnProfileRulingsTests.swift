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
    let conversations: ConversationManager
    let store: LiveChatStore
    var repo: Repository { device.deps.repo }

    init(_ device: TestDevice) {
        self.device = device
        let deps = device.deps
        conversations = ConversationManager(deps: deps, escrowKey: { Data(repeating: 7, count: 32) })
        outbox = Outbox(deps: deps, conversations: conversations, readReceiptsEnabled: { true })
        let inbox = InboxProcessor(
            deps: deps, conversations: conversations, handler: IncomingHandler(me: deps.me, now: deps.now, log: { _ in }),
            controls: outbox)
        store = LiveChatStore(deps: deps, outbox: outbox, inbox: inbox, conversations: conversations, attachments: nil)
    }

    func addLocalContact(_ id: String, isContact: Bool = true) throws {
        try repo.upsertContact(ContactRecord(accountID: id, username: id, displayName: id, isContact: isContact, addedAt: testEpochDate))
        try repo.upsertChat(ChatRecord(id: id, isRequest: !isContact, unread: 0, muted: false, lastActivity: testEpochDate))
    }

    func sentVersion(_ chat: String) throws -> Int? {
        try repo.get(LiveChatStore.profileSentKey(chat)).flatMap { Int(String(decoding: $0, as: UTF8.self)) }
    }

    func sendRaw(_ envelope: ContentEnvelope, to chatID: String) async throws {
        let conversation = try await conversations.preferredConversation(with: chatID)
        try await outbox.send(envelope, in: conversation)
    }
}

private let header = Data([0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x0B, 0x08, 0x01, 0x00, 0x01, 0x00, 0x01, 0x01, 0x11, 0x00])
private let jpeg = header + Data(count: 100)
private let hostile = Data([0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x0B, 0x08, 0xFD, 0xE8, 0xFD, 0xE8, 0x01, 0x01, 0x11, 0x00]) + Data(count: 100)
private let t0 = Int(testEpochDate.timeIntervalSince1970)

@Suite("TestOwnProfileRulings") @MainActor struct TestOwnProfileRulings {
    private func make() async throws -> (ana: Side, bob: Side, server: FakeServer) {
        let server = FakeServer()
        let anaDevice = await makeDevice(server, account: "acc-ana", username: "ana")
        let bobDevice = await makeDevice(server, account: "acc-bob", username: "bob")
        for device in [anaDevice, bobDevice] {
            _ = try await device.deps.server.uploadKeyPackages(try await device.deps.mls.makeKeyPackages(count: 3))
        }
        let ana = Side(anaDevice), bob = Side(bobDevice)
        _ = await ana.store.addContact(username: "bob")
        _ = ana.store.ensureChat(with: "acc-bob")
        return (ana, bob, server)
    }

    // A: a hostile photo

    @Test func aPhotoThatDeclaresAHugePictureIsNeverDecodedOrStored() async throws {
        let json = ContentEnvelope(id: "p", at: 1, body: .profile(name: "Ann", photo: jpeg, ver: 5)).encoded()
        guard case .profile = ContentEnvelope.decode(json).body else { Issue.record("a normal photo must decode"); return }
        let bad = String(decoding: json, as: UTF8.self).replacingOccurrences(
            of: jpeg.base64EncodedString(), with: hostile.base64EncodedString())
        #expect(ContentEnvelope.decode(Data(bad.utf8)).body == .unsupported(kind: "profile"))
    }

    @Test func theOwnPhotoIsCheckedToo() async throws {
        let (ana, _, _) = try await make()
        ana.store.setMyDisplayName("Ana")
        #expect(!ana.store.setMyPhoto(hostile))
        #expect(!ana.store.setMyPhoto(Data([0xFF, 0xD8, 0xFF, 0xD9])))
        #expect(ana.store.myPhoto == nil)
        #expect(ana.store.setMyPhoto(jpeg))
    }

    @Test func aStoredHostilePhotoFromAnEarlierBuildIsNotShown() async throws {
        let (ana, _, _) = try await make()
        try ana.addLocalContact("acc-cy")
        try ana.repo.saveProfile(accountID: "acc-cy", name: "Cy", photo: hostile, ver: 1)
        ana.store.refresh()
        #expect(ana.store.profilePhoto(for: "acc-cy") == nil)
        // The own photo too.
        try ana.repo.set(OwnProfile.photoKey, hostile)
        #expect(ana.store.myPhoto == nil)
    }

    // B: blocked and requests

    @Test func aProfileFromABlockedAccountIsIgnored() async throws {
        let (ana, bob, _) = try await make()
        try bob.addLocalContact("acc-ana")
        try bob.repo.block("acc-ana")
        try await ana.sendRaw(ContentEnvelope(id: "p1", at: 1, body: .profile(name: "Ana", photo: nil, ver: 5)), to: "acc-bob")
        await bob.store.syncInbox()
        await bob.store.settle()
        #expect(try bob.repo.profile(for: "acc-ana") == nil)
    }

    @Test func aProfileFromARequestIsKeptButNotShownUntilAccepted() async throws {
        let (ana, bob, _) = try await make()
        try bob.addLocalContact("acc-ana", isContact: false)
        try await ana.sendRaw(ContentEnvelope(id: "p1", at: 1, body: .profile(name: "Ana Lopez", photo: jpeg, ver: 5)), to: "acc-bob")
        await bob.store.syncInbox()
        await bob.store.settle()
        #expect(try bob.repo.profile(for: "acc-ana")?.name == "Ana Lopez")
        #expect(bob.store.contact(id: "acc-ana")?.displayName == "acc-ana")
        #expect(bob.store.profilePhoto(for: "acc-ana") == nil)
        bob.store.accept(requestID: "acc-ana")
        #expect(bob.store.contact(id: "acc-ana")?.displayName == "Ana Lopez")
        #expect(bob.store.profilePhoto(for: "acc-ana") == jpeg)
    }

    // C: one name everywhere

    @Test func theNameIsTheSameInSystemLinesAndNotificationsAsInTheChatList() async throws {
        let (ana, bob, _) = try await make()
        try bob.addLocalContact("acc-ana")
        try bob.repo.saveProfile(accountID: "acc-ana", name: "Ana Lopez", photo: nil, ver: 1)
        ana.store.setTimer("acc-bob", seconds: 86_400)
        await ana.store.settle()
        await bob.store.syncInbox()
        await bob.store.settle()
        guard case .system(.timer(_, let name?))? = bob.store.chat(id: "acc-ana")?.messages.last?.content else {
            Issue.record("no timer line"); return
        }
        #expect(name == "Ana Lopez")
        let row = try #require(try bob.repo.contact(id: "acc-ana"))
        #expect(ContactNames.display(row, repo: bob.repo) == "Ana Lopez")
        // A request is shown by its lookup name.
        var stranger = row
        stranger.isContact = false
        #expect(ContactNames.display(stranger, repo: bob.repo) == "acc-ana")
    }

    // D: the sent marker follows a successful queue

    @Test func aFailingQueueLeavesTheChatPendingAndTheNextPassSendsIt() async throws {
        let (ana, _, _) = try await make()
        ana.store.profileEnqueueOverride = { _, _ in throw CocoaError(.fileWriteUnknown) }
        ana.store.setMyDisplayName("Ana")
        #expect(try ana.sentVersion("acc-bob") == nil)
        ana.store.profileEnqueueOverride = nil
        ana.store.sendOwnProfileIfNeeded()
        #expect(try ana.sentVersion("acc-bob") == t0)
        let queued = try ana.repo.dueOutbox(at: .distantFuture)
        #expect(queued.filter { if case .profile = ContentEnvelope.decode($0.envelope).body { true } else { false } }.count <= 1)
    }

    // E: versions and the own other device

    @Test func aSecondDevicesFirstChangeBeatsAnOldCounter() async throws {
        let (ana, bob, _) = try await make()
        // Peers hold version 3 from the phone's old counter.
        try bob.repo.saveProfile(accountID: "acc-ana", name: "Old", photo: nil, ver: 3)
        ana.store.setMyDisplayName("Ana")
        await ana.store.settle()
        await bob.store.syncInbox()
        await bob.store.settle()
        #expect(try bob.repo.profile(for: "acc-ana")?.name == "Ana")
        #expect(try bob.repo.profile(for: "acc-ana")?.ver == t0)
    }

    @Test func anotherDeviceOfMineWithAHigherVersionIsAdoptedAndSentOnToMyPendingChats() async throws {
        let (ana, _, _) = try await make()
        try ana.addLocalContact("acc-cy")
        ana.store.setMyDisplayName("Ana")
        #expect(try OwnProfile.adopt(name: "Ana Two", photo: jpeg, ver: t0 + 50, repo: ana.repo, me: "acc-ana"))
        #expect(ana.store.myPhoto == jpeg)
        ana.store.refresh()
        #expect(ana.store.myStoredName == "Ana Two")
        // The adopted version goes to this device's pending chats, as adopted (no new version is made).
        #expect(try ana.sentVersion("acc-bob") == t0 + 50 && ana.sentVersion("acc-cy") == t0 + 50)
        let queued = try ana.repo.dueOutbox(at: .distantFuture).filter { $0.chatID == "acc-cy" }.map { ContentEnvelope.decode($0.envelope).body }
        #expect(queued.contains(.profile(name: "Ana Two", photo: jpeg, ver: t0 + 50)))
        // Equal or lower: ignored.
        #expect(try !OwnProfile.adopt(name: "Low", photo: nil, ver: t0 + 50, repo: ana.repo, me: "acc-ana"))
        #expect(try !OwnProfile.adopt(name: "Low", photo: nil, ver: t0, repo: ana.repo, me: "acc-ana"))
        #expect(ana.store.myPhoto == jpeg)
        // A photo that fails the rule is never adopted.
        #expect(try !OwnProfile.adopt(name: "Bad", photo: hostile, ver: t0 + 60, repo: ana.repo, me: "acc-ana"))
        // The next own change goes above the adopted version.
        ana.store.setMyDisplayName("Ana Three")
        #expect(try ana.sentVersion("acc-bob") == t0 + 51)
    }
}
