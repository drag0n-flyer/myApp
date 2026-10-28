import AppCore
import Foundation
import LocalStore
import SecureMessagingCore
import Testing
@testable import Messaging
import MessagingTestKit

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
        try device.deps.repo.setSafetyCurrent(chatID: "acc-bob", String(repeating: "12345", count: 12))
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
    private func upgraded() async throws -> (store: LiveChatStore, spy: SpyMLSEngine, repo: Repository, old: String) {
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
        return (store, spy, deps.repo, old)
    }

    @Test func placeholderNumberIsNeverShownAndOpeningRebaselines() async throws {
        let (store, _, repo, old) = try await upgraded()
        #expect(store.safetyNumber(for: "acc-bob") == nil)
        store.setVerified(true, chatID: "acc-bob")
        #expect(try repo.safety(chatID: "acc-bob")?.verified == nil)
        await store.settle()
        let number = try #require(store.safetyNumber(for: "acc-bob"))
        #expect(number.groups.joined() != old)
        #expect(!number.isVerified)
    }

    @Test func placeholderNumberStaysHiddenWhenKeysCannotBeRead() async throws {
        let (store, spy, repo, _) = try await upgraded()
        spy.failMembers(true)
        #expect(store.safetyNumber(for: "acc-bob") == nil)
        await store.settle()
        #expect(store.safetyNumber(for: "acc-bob") == nil)
        store.setVerified(true, chatID: "acc-bob")
        #expect(try repo.safety(chatID: "acc-bob")?.verified == nil)
    }
}
