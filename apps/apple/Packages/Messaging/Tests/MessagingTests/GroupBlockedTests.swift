import AppCore
import Foundation
import LocalStore
import Testing
@testable import Messaging
import MessagingTestKit

@Suite("GroupBlocked") @MainActor struct GroupBlockedTests {
    @Test func aBlockedMembersMessageIsStoredCollapsedAndNeverCountedUnread() async throws {
        let g = try await GroupOfThree.make()
        try g.bob.repo.block("acc-cy")
        g.cy.store.send(.text("from the blocked one"), in: g.chatID, replyTo: nil)
        await g.cy.store.flushOutbox()
        g.ana.store.send(.text("from ana"), in: g.chatID, replyTo: nil)
        await g.ana.store.flushOutbox()
        await g.bob.store.syncInbox()
        // Both are stored, only ana's counts, and the blocked one is marked for the screen.
        let shown = g.bob.store.messages(in: g.chatID, before: nil, limit: 20).filter { $0.senderID != "system" }
        #expect(Dictionary(uniqueKeysWithValues: shown.map { ($0.senderID, $0.fromBlockedSender) }) == ["acc-cy": true, "acc-ana": false])
        #expect(try g.bob.repo.chat(id: g.chatID)?.unread == 1)
        // Nobody else is affected: ana counts both.
        await g.ana.store.syncInbox()
        #expect(g.ana.store.messages(in: g.chatID, before: nil, limit: 20).filter { $0.senderID == "acc-cy" }.map(\.fromBlockedSender) == [false])
        #expect(try g.ana.repo.chat(id: g.chatID)?.unread == 1)
    }

    @Test func noAutomaticDownloadStartsForABlockedMembersAttachment() async throws {
        let g = try await GroupOfThree.make()
        try g.bob.repo.block("acc-cy")
        let seen = Seen()
        await g.bob.inbox.setOnStored { message in await seen.add(message.senderAccount) }
        g.cy.store.send(.text("x"), in: g.chatID, replyTo: nil)
        await g.cy.store.flushOutbox()
        g.ana.store.send(.text("y"), in: g.chatID, replyTo: nil)
        await g.ana.store.flushOutbox()
        _ = try await g.bob.inbox.syncOnce()
        #expect(await seen.accounts == ["acc-ana"])
    }
}

private actor Seen {
    var accounts: [String] = []
    func add(_ a: String) { accounts.append(a) }
}
