import AppCore
import Foundation
import LocalStore
import SecureMessagingCore
import Testing
@testable import Messaging
import MessagingTestKit

private struct GroupInbox: InboxSyncing {
    func syncOnce(maxItems: Int) async throws -> Int { 0 }
}

private struct NoConversationManager: ConversationManaging {
    func preferredConversation(with peer: String) async throws -> ConversationRecord { throw MLSError.unknownGroup }
    func handleStaleEpoch(_ conversation: ConversationRecord) async throws {}
}

private func padded(_ payload: Data) -> Data {
    var out = Data([UInt8(payload.count >> 8), UInt8(payload.count & 0xff)]) + payload
    if out.count < 256 { out += Data(repeating: 0, count: 256 - out.count) }
    return out
}

/// Ana (owner) and bob share the MLS group of "Book club"; this is bob's device, with the real incoming handler.
private struct GroupPush {
    let ana: TestDevice
    let bob: TestDevice
    let group: GroupID

    init(anaIsContact: Bool = true) async throws {
        let server = FakeServer()
        ana = await makeDevice(server, account: "acc-ana", username: "ana")
        bob = await makeDevice(server, account: "acc-bob", username: "bob")
        let packages = try await bob.deps.mls.makeKeyPackages(count: 1)
        group = try await ana.deps.mls.createGroup()
        let bundle = try await ana.deps.mls.add(keyPackages: packages, to: group)
        try await ana.deps.mls.commitAccepted(group)
        _ = try await bob.deps.mls.process(try #require(bundle.welcome))
        let repo = bob.deps.repo
        try repo.upsertGroup(conversationID: "conv-g", title: "Book club", photo: nil, at: testEpochDate)
        try repo.setGroupMembers(chatID: "g:conv-g", [
            GroupMemberRecord(accountID: "acc-ana", role: "owner"), GroupMemberRecord(accountID: "acc-bob", role: "member")], version: 0)
        try repo.upsertConversation(ConversationRecord(
            serverID: "conv-g", chatID: "g:conv-g", groupID: group.raw, mode: "e2ee", innerMode: "e2ee", modeMismatch: false,
            epoch: 1, active: true, createdAt: testEpochDate))
        if anaIsContact {
            try repo.upsertContact(ContactRecord(accountID: "acc-ana", username: "ana", displayName: "Ana", isContact: true, addedAt: testEpochDate))
        }
    }

    func push(_ body: ContentEnvelope.Body, id: String = "m1") async throws -> Data {
        let envelope = ContentEnvelope(id: id, at: 1_700_000_000_000, body: body).encoded()
        return padded(try await ana.deps.mls.encrypt(envelope, in: group).ciphertext)
    }

    /// `rawSender`: the sender the server reported for the item already fetched into the raw inbox; `nil` = not fetched yet.
    func process(_ body: ContentEnvelope.Body, id: String = "m1", rawSender: String? = "acc-ana") async throws -> NotificationOutcome {
        let deps = bob.deps
        let processor = NotificationProcessor(
            deps: deps, handler: IncomingHandler(me: deps.me, now: deps.now, log: { _ in }), inbox: GroupInbox(),
            conversations: NoConversationManager())
        let data = try await push(body, id: id)
        if let rawSender {
            try deps.repo.insertRaw([RawInboxRecord(
                cursor: Int64(id.utf8.reduce(1) { ($0 * 31 + Int($1)) % 100_000 }), kind: "message", conversationID: "conv-g", seq: nil, epoch: 1,
                senderAccount: rawSender, senderDevice: "d", payload: try Padding.unpad(data), sentAt: testEpochDate)])
        }
        return await processor.process(pushData: data, deadline: testEpochDate.addingTimeInterval(20))
    }
}

@Suite("GroupNotification") struct GroupNotificationTests {
    @Test func theTitleIsTheGroupNameAndTheBodyNamesTheSender() async throws {
        let g = try await GroupPush()
        let outcome = try await g.process(.text("Lunch?", replyTo: nil))
        #expect(outcome == .show(title: "Book club", body: "Ana: Lunch?", threadID: "g:conv-g", messageID: "m1"))
        #expect(try g.bob.deps.repo.message(id: "m1")?.chatID == "g:conv-g")
        // Media kinds name the sender the same way.
        let photo = try await g.process(.photo(Att(id: "att-1", key: Data(count: 32), dg: Data(count: 32), sz: 5, csz: 21), w: 1, h: 1, thumb: nil, replyTo: nil), id: "m2")
        #expect(photo == .show(title: "Book club", body: "Ana: Photo", threadID: "g:conv-g", messageID: "m2"))
    }

    @Test func aMemberWhoIsNotAContactIsNeverAMessageRequestInAGroup() async throws {
        let g = try await GroupPush(anaIsContact: false)
        let outcome = try await g.process(.text("hi", replyTo: nil))
        #expect(outcome == .show(title: "Book club", body: "Someone: hi", threadID: "g:conv-g", messageID: "m1"))
        // Once their profile is known the name is used.
        try g.bob.deps.repo.saveProfile(accountID: "acc-ana", name: "Ana B", photo: nil, ver: 4)
        let next = try await g.process(.text("again", replyTo: nil), id: "m2")
        #expect(next == .show(title: "Book club", body: "Ana B: again", threadID: "g:conv-g", messageID: "m2"))
    }

    @Test func aMutedGroupStillShowsSoItCanBeSilent() async throws {
        let g = try await GroupPush()
        try g.bob.deps.repo.setMuted(chatID: "g:conv-g", true)
        let outcome = try await g.process(.text("hi", replyTo: nil))
        #expect(outcome == .show(title: "Book club", body: "Ana: hi", threadID: "g:conv-g", messageID: "m1"))
    }

    @Test func aBlockedMembersMessageDropsAndIsNotCountedUnread() async throws {
        let g = try await GroupPush()
        try g.bob.deps.repo.block("acc-ana")
        let outcome = try await g.process(.text("hi", replyTo: nil))
        #expect(outcome == .drop)
        #expect(try g.bob.deps.repo.message(id: "m1") != nil)
        #expect(try g.bob.deps.repo.chat(id: "g:conv-g")?.unread == 0)
    }

    @Test func aReactionNamesTheReactorUnderTheGroupName() async throws {
        let g = try await GroupPush()
        let outcome = try await g.process(.react(target: "x", emoji: "👍"))
        #expect(outcome == .show(title: "Book club", body: "Ana: Reacted 👍", threadID: "g:conv-g"))
    }

    @Test func nothingShowsWhileIAmOutOfTheGroupOrForAStrangerInTheList() async throws {
        let g = try await GroupPush()
        try g.bob.deps.repo.setMyState(chatID: "g:conv-g", "removed")
        #expect(try await g.process(.text("hi", replyTo: nil)) == .drop)
        #expect(try g.bob.deps.repo.message(id: "m1") == nil)
    }
}

@Suite("GroupNotificationLookup") struct GroupNotificationLookupTests {
    @Test func aSenderTheStoredListDoesNotHoldIsLeftForTheAppBeforeAnythingIsDecrypted() async throws {
        let g = try await GroupPush()
        let outcome = try await g.process(.text("hi", replyTo: nil), rawSender: "acc-zed")
        #expect(outcome == .fallback)
        #expect(try g.bob.deps.repo.message(id: "m1") == nil)
        #expect(try g.bob.deps.repo.rawItems(limit: 5).count == 1)   // still there for the app
    }

    @Test func aPushNotFetchedYetIsLeftForTheAppWhenThisDeviceIsInAGroup() async throws {
        let g = try await GroupPush()
        let data = try await g.push(.text("hi", replyTo: nil))
        let deps = g.bob.deps
        let processor = NotificationProcessor(
            deps: deps, handler: IncomingHandler(me: deps.me, now: deps.now, log: { _ in }), inbox: GroupInbox(),
            conversations: NoConversationManager())
        #expect(await processor.process(pushData: data, deadline: testEpochDate.addingTimeInterval(20)) == .fallback)
        #expect(try deps.repo.message(id: "m1") == nil)
        #expect(try !deps.repo.isProcessed(hash: PortableSHA256.hash(try Padding.unpad(data))))
    }
}
