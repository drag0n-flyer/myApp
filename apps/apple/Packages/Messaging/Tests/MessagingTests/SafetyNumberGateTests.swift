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
