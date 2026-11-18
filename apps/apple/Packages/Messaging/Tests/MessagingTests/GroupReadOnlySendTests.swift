import AppCore
import Foundation
import LocalStore
import Testing
@testable import Messaging
import MessagingTestKit

@Suite("GroupReadOnlySend") @MainActor struct GroupReadOnlySendTests {
    @Test func aRemovedGroupRefusesMessagesAndControlEnvelopes() async throws {
        let g = try await GroupOfThree.make()
        try g.bob.repo.setMyState(chatID: g.chatID, "removed")
        let before = try g.bob.repo.messages(chatID: g.chatID, before: nil, limit: 50).count
        g.bob.store.send(.text("too late"), in: g.chatID, replyTo: nil)
        #expect(try g.bob.repo.messages(chatID: g.chatID, before: nil, limit: 50).count == before)
        #expect(throws: OutboxError.readOnlyGroup) {
            try g.bob.outbox.enqueueControl(.typing(on: true), chatID: g.chatID)
        }
        await #expect(throws: OutboxError.readOnlyGroup) {
            try await g.bob.outbox.sendControl(.typing(on: true), chatID: g.chatID)
        }
        // A message queued before the removal fails instead of being sent.
        #expect(try g.bob.repo.dueOutbox(at: testEpochDate.addingTimeInterval(3600)).isEmpty)
    }
}
