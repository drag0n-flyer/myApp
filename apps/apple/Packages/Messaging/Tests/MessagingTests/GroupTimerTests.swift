import AppCore
import Foundation
import LocalStore
import Testing
@testable import Messaging
import MessagingTestKit

@Suite("GroupTimer") @MainActor struct GroupTimerTests {
    @Test func aPlainMemberCannotSetTheTimerButTheOwnerCanAndEveryoneFollows() async throws {
        let g = try await GroupOfThree.make()
        // A plain member: refused here, nothing queued, no line.
        let queuedBefore = try g.bob.repo.dueOutbox(at: testEpochDate.addingTimeInterval(3600)).count
        g.bob.store.setTimer(g.chatID, seconds: 86_400)
        #expect(g.bob.store.timerSeconds(g.chatID) == 0)
        #expect(try g.bob.repo.dueOutbox(at: testEpochDate.addingTimeInterval(3600)).count == queuedBefore)
        // The owner sets it; the members take it from the envelope.
        g.ana.store.setTimer(g.chatID, seconds: 604_800)
        await g.ana.store.flushOutbox()
        await g.bob.store.syncInbox()
        await g.cy.store.syncInbox()
        #expect(g.ana.store.timerSeconds(g.chatID) == 604_800)
        #expect(g.bob.store.timerSeconds(g.chatID) == 604_800)
        #expect(g.cy.store.timerSeconds(g.chatID) == 604_800)
        // The next message carries the life.
        g.bob.store.send(.text("expires"), in: g.chatID, replyTo: nil)
        let sent = g.bob.store.messages(in: g.chatID, before: nil, limit: 5).last { $0.senderID == "acc-bob" }
        #expect(sent?.expiresAt != nil)
    }
}
