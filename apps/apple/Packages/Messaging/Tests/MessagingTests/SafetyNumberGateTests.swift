import AppCore
import Foundation
import LocalStore
import SecureMessagingCore
import Testing
@testable import Messaging
import MessagingTestKit

/// Stores a conversation with `peer` whose members (one device each side) have real-looking keys, and the number
/// they give. Returns the number.
@discardableResult
func storeNumber(_ repo: Repository, me: String, peer: String, key: UInt8) throws -> String {
    if try repo.preferredConversation(chatID: peer) == nil {
        try repo.upsertConversation(ConversationRecord(
            serverID: "conv-\(peer)", chatID: peer, groupID: Data(repeating: 0xC, count: 16), mode: "e2ee", innerMode: nil,
            modeMismatch: false, epoch: 1, active: true, createdAt: testEpochDate))
    }
    let keys = [MemberKey(identity: "\(me)/d1", signatureKey: Data(repeating: key, count: 32)),
                MemberKey(identity: "\(peer)/d1", signatureKey: Data(repeating: key &+ 1, count: 32))]
    try repo.replaceMembers(conversationID: "conv-\(peer)", keys.map { ($0.identity, $0.signatureKey) })
    let number = try SafetyNumberCalc.compute(accountA: me, accountB: peer, members: keys).joined()
    try repo.setSafetyCurrent(chatID: peer, number)
    return number
}

@Suite("TestSafetyNumberGate") @MainActor struct TestSafetyNumberGate {
    private func store(realKeys: Bool) async throws -> (LiveChatStore, TestDevice) {
        let server = FakeServer()
        let device = await makeDevice(server, account: "acc-ana", username: "ana", realMemberKeys: realKeys)
        let manager = ConversationManager(deps: device.deps)
        let outbox = Outbox(deps: device.deps, conversations: manager, readReceiptsEnabled: { true })
        let inbox = InboxProcessor(
            deps: device.deps, conversations: manager, handler: IncomingHandler(me: device.deps.me, now: device.deps.now, log: { _ in }))
        let store = LiveChatStore(deps: device.deps, outbox: outbox, inbox: inbox, conversations: manager, attachments: nil)
        _ = store.ensureChat(with: "acc-bob")
        try storeNumber(device.deps.repo, me: "acc-ana", peer: "acc-bob", key: 1)
        return (store, device)
    }

    @Test func numberIsHiddenWhileTheEngineHasNoRealMemberKeys() async throws {
        let (store, _) = try await store(realKeys: false)
        #expect(!store.supportsSafetyNumbers)
        #expect(store.safetyNumber(for: "acc-bob") == nil)
    }

    @Test func numberShowsOnceTheEngineProvidesRealMemberKeys() async throws {
        let (store, _) = try await store(realKeys: true)
        #expect(store.supportsSafetyNumbers)
        #expect(store.safetyNumber(for: "acc-bob")?.groups.count == 12)
    }
}

/// An install upgraded from the placeholder keys: the conversation's stored members still have `fake-sig:` keys and
/// the stored number covers no key. It is never shown or verifiable; opening it re-baselines from the real keys.
@Suite("TestSafetyNumberUpgrade") @MainActor struct TestSafetyNumberUpgrade {
    private func upgraded() async throws -> (store: LiveChatStore, spy: SpyMLSEngine, repo: Repository, old: String, conversation: ConversationRecord) {
        let server = FakeServer()
        let ana = await makeDevice(server, account: "acc-ana", username: "ana", realMemberKeys: true)
        let bob = await makeDevice(server, account: "acc-bob", username: "bob", realMemberKeys: true)
        _ = try await bob.deps.server.uploadKeyPackages(try await bob.deps.mls.makeKeyPackages(count: 3))
        var deps = ana.deps
        let spy = SpyMLSEngine(deps.mls)
        deps.mls = spy
        let manager = ConversationManager(deps: deps, escrowKey: { Data(repeating: 7, count: 32) })
        let conversation = try await manager.create(with: "acc-bob")
        // The fake's keys are the old placeholders; from now on the engine reports real ones.
        let rows = try deps.repo.members(conversationID: conversation.serverID)
        #expect(!rows.isEmpty && rows.allSatisfy { $0.signatureKey == Data("fake-sig:\($0.identity)".utf8) })
        for (index, row) in rows.enumerated() { spy.setSignatureKey(Data(repeating: UInt8(index + 1), count: 32), for: row.identity) }
        let old = try #require(try deps.repo.safety(chatID: "acc-bob")?.current)
        let inbox = InboxProcessor(deps: deps, conversations: manager, handler: IncomingHandler(me: deps.me, now: deps.now, log: { _ in }))
        let outbox = Outbox(deps: deps, conversations: manager, readReceiptsEnabled: { true })
        let store = LiveChatStore(deps: deps, outbox: outbox, inbox: inbox, conversations: manager, attachments: nil)
        return (store, spy, deps.repo, old, conversation)
    }

    @Test func placeholderNumberIsNeverShownAndOpeningRebaselines() async throws {
        let (store, _, repo, old, _) = try await upgraded()
        #expect(store.safetyNumber(for: "acc-bob") == nil)
        store.setVerified(true, chatID: "acc-bob")
        #expect(try repo.safety(chatID: "acc-bob")?.verified == nil)
        await store.settle()
        let number = try #require(store.safetyNumber(for: "acc-bob"))
        #expect(number.groups.joined() != old)
        #expect(!number.isVerified)
    }

    @Test func placeholderNumberStaysHiddenWhenKeysCannotBeRead() async throws {
        let (store, spy, repo, _, _) = try await upgraded()
        spy.failMembers(true)
        #expect(store.safetyNumber(for: "acc-bob") == nil)
        await store.settle()
        #expect(store.safetyNumber(for: "acc-bob") == nil)
        store.setVerified(true, chatID: "acc-bob")
        #expect(try repo.safety(chatID: "acc-bob")?.verified == nil)
    }

    /// Route (a): a member whose identity is too long to frame. The rows become real but no number can be computed,
    /// so the stored placeholder number must stay hidden.
    @Test func numberNotFromStoredRowsIsHiddenWhenItCannotBeComputed() async throws {
        let (store, spy, repo, old, conversation) = try await upgraded()
        var rows = try await spy.memberKeys(of: GroupID(raw: conversation.groupID)).map { ($0.identity, $0.signatureKey) }
        rows.append(("acc-bob/" + String(repeating: "x", count: 70_000), Data(repeating: 9, count: 32)))
        try repo.replaceMembers(conversationID: conversation.serverID, rows)
        #expect(store.safetyNumber(for: "acc-bob") == nil)
        store.setVerified(true, chatID: "acc-bob")
        #expect(try repo.safety(chatID: "acc-bob")?.verified == nil)
        await store.settle()
        #expect(store.safetyNumber(for: "acc-bob")?.groups.joined() != old)
    }

    /// Route (b): the preferred conversation switches to one already re-baselined, while the stored number still
    /// comes from the old conversation's placeholder rows.
    @Test func numberFromAnotherConversationIsHidden() async throws {
        let (store, spy, repo, old, a) = try await upgraded()
        let real = try await spy.memberKeys(of: GroupID(raw: a.groupID)).map { ($0.identity, $0.signatureKey) }
        var b = a
        b.serverID = a.serverID + "-b"
        b.groupID = Data(repeating: 0xB, count: 16)
        try repo.upsertConversation(b)
        try repo.replaceMembers(conversationID: b.serverID, real)
        var inactive = a
        inactive.active = false
        try repo.upsertConversation(inactive)
        #expect(try repo.preferredConversation(chatID: "acc-bob")?.serverID == b.serverID)
        #expect(store.safetyNumber(for: "acc-bob") == nil)
        store.setVerified(true, chatID: "acc-bob")
        #expect(try repo.safety(chatID: "acc-bob")?.verified == nil)
        await store.settle()
        #expect(store.safetyNumber(for: "acc-bob")?.groups.joined() != old)
    }
}

/// A chat whose members cannot be read shows no number and no verified state, and says so.
@Suite("TestSafetyNumberMembersUnreadable") @MainActor struct TestSafetyNumberMembersUnreadable {
    @Test func unreadableMembersHideTheNumberAndTheVerifiedState() async throws {
        let server = FakeServer()
        let device = await makeDevice(server, account: "acc-ana", username: "ana", realMemberKeys: true)
        let manager = ConversationManager(deps: device.deps)
        let outbox = Outbox(deps: device.deps, conversations: manager, readReceiptsEnabled: { true })
        let inbox = InboxProcessor(
            deps: device.deps, conversations: manager, handler: IncomingHandler(me: device.deps.me, now: device.deps.now, log: { _ in }))
        let store = LiveChatStore(deps: device.deps, outbox: outbox, inbox: inbox, conversations: manager, attachments: nil)
        _ = store.ensureChat(with: "acc-bob")
        let number = try storeNumber(device.deps.repo, me: "acc-ana", peer: "acc-bob", key: 1)
        try device.deps.repo.setVerified(chatID: "acc-bob", number)
        #expect(store.safetyNumber(for: "acc-bob")?.isVerified == true)
        #expect(!store.membersUnreadable(for: "acc-bob"))

        try Membership.markUnreadable(conversationID: "conv-acc-bob", db: device.deps.repo)
        #expect(store.membersUnreadable(for: "acc-bob"))
        #expect(store.safetyNumber(for: "acc-bob") == nil)
        store.setVerified(false, chatID: "acc-bob")
        #expect(try device.deps.repo.safety(chatID: "acc-bob")?.verified == number)  // unchanged, not re-verifiable
    }
}
