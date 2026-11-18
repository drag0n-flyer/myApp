import AppCore
import Foundation
import LocalStore
import SecureMessagingCore
import Testing
@testable import Messaging
import MessagingTestKit

private struct NoInbox: InboxSyncing {
    func syncOnce(maxItems: Int) async throws -> Int { 0 }
}

private struct NoConversations: ConversationManaging {
    func preferredConversation(with peer: String) async throws -> ConversationRecord { throw MLSError.unknownGroup }
    func handleStaleEpoch(_ conversation: ConversationRecord) async throws {}
}

private func padded(_ payload: Data) -> Data {
    var out = Data([UInt8(payload.count >> 8), UInt8(payload.count & 0xff)]) + payload
    if out.count < 256 { out += Data(repeating: 0, count: 256 - out.count) }
    return out
}

/// The notification extension runs the same `IncomingHandler` as the app: it stores `expires_at` and skips a message
/// whose time has already come.
@Suite("TestNotificationExpiry") struct TestNotificationExpiry {
    private struct Pair {
        let ana: TestDevice
        let bob: TestDevice
        let group: GroupID

        init() async throws {
            let server = FakeServer()
            ana = await makeDevice(server, account: "acc-ana", username: "ana")
            bob = await makeDevice(server, account: "acc-bob", username: "bob")
            let packages = try await bob.deps.mls.makeKeyPackages(count: 1)
            group = try await ana.deps.mls.createGroup()
            let bundle = try await ana.deps.mls.add(keyPackages: packages, to: group)
            try await ana.deps.mls.commitAccepted(group)
            _ = try await bob.deps.mls.process(try #require(bundle.welcome))
            try bob.deps.repo.upsertChat(ChatRecord(id: "acc-ana", isRequest: false, unread: 0, muted: false, lastActivity: testEpochDate))
            try bob.deps.repo.upsertConversation(ConversationRecord(
                serverID: "conv-1", chatID: "acc-ana", groupID: group.raw, mode: "e2ee", innerMode: "e2ee",
                modeMismatch: false, epoch: 1, active: true, createdAt: testEpochDate))
            try bob.deps.repo.upsertContact(ContactRecord(
                accountID: "acc-ana", username: "ana", displayName: "Ana", isContact: true, addedAt: testEpochDate))
        }

        func push(_ envelope: ContentEnvelope) async throws -> Data {
            padded(try await ana.deps.mls.encrypt(envelope.encoded(), in: group).ciphertext)
        }

        func process(_ data: Data) async -> NotificationOutcome {
            let handler = IncomingHandler(me: bob.deps.me, now: bob.deps.now, log: { _ in })
            let processor = NotificationProcessor(deps: bob.deps, handler: handler, inbox: NoInbox(), conversations: NoConversations())
            return await processor.process(pushData: data, deadline: testEpochDate.addingTimeInterval(20))
        }
    }

    private let nowMs: Int64 = 1_700_000_000_000

    @Test func theExtensionStoresTheExpiry() async throws {
        let pair = try await Pair()
        let envelope = ContentEnvelope(id: "m1", at: nowMs, body: .text("Lunch?", replyTo: nil), ttl: 3_600)
        let outcome = await pair.process(try await pair.push(envelope))
        #expect(outcome == .show(title: "Ana", body: "Lunch?", threadID: "acc-ana", messageID: "m1"))
        #expect(try pair.bob.deps.repo.message(id: "m1")?.expiresAt == testEpochDate.addingTimeInterval(3_600))
    }

    @Test func anAlreadyExpiredMessageIsNotStoredNotShownAndGetsNoDownload() async throws {
        let pair = try await Pair()
        let att = Att(id: "att1", key: Data(count: 32), dg: Data(count: 32), sz: 1, csz: 17)
        let photo = ContentEnvelope(id: "p1", at: nowMs - 3_600_000, body: .photo(att, w: 1, h: 1, thumb: nil, replyTo: nil), ttl: 60)
        #expect(await pair.process(try await pair.push(photo)) == .drop)
        let text = ContentEnvelope(id: "m2", at: nowMs - 3_600_000, body: .text("old", replyTo: nil), ttl: 3_600)
        #expect(await pair.process(try await pair.push(text)) == .drop)   // expires exactly now
        #expect(try pair.bob.deps.repo.message(id: "p1") == nil && pair.bob.deps.repo.message(id: "m2") == nil)
        #expect(try pair.bob.deps.repo.attachment(id: "att1") == nil)
        #expect(try pair.bob.deps.repo.chat(id: "acc-ana")?.unread == 0)
    }

    @Test func typingAndProfileArriveAsStoredMessagesWithoutANotice() async throws {
        let pair = try await Pair()
        #expect(await pair.process(try await pair.push(ContentEnvelope(id: "t1", at: nowMs, body: .typing(on: true)))) == .drop)
        #expect(await pair.process(try await pair.push(ContentEnvelope(id: "t2", at: nowMs, body: .unsupported(kind: "presence")))) == .drop)
        #expect(await pair.process(try await pair.push(ContentEnvelope(id: "t3", at: nowMs, body: .profile(name: "Ana B", photo: nil, ver: 1)))) == .drop)
        #expect(try pair.bob.deps.repo.messages(chatID: "acc-ana", before: nil, limit: 10).isEmpty)
        #expect(try pair.bob.deps.repo.profile(for: "acc-ana")?.name == "Ana B")
    }
}
