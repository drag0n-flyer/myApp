import AppCore
import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

private let jpeg4 = Data([0xFF, 0xD8, 0xFF, 0xE0])

/// Send and receive in a group of ana (owner), bob and cy, one device each used.
@Suite("GroupMessagesReceive") struct GroupMessagesReceiveTests {
    private func started() async throws -> (rig: GroupRig, ana: GroupSide, bob: GroupSide, cy: GroupSide, chatID: String, conversationID: String) {
        let rig = try await GroupRig.make()
        let ana = GroupSide(rig.ana)
        let made = try await ana.manager.createGroup(accounts: ["acc-bob", "acc-cy"], name: "Book club", photo: nil)
        try await ana.outbox.flush()
        let bob = GroupSide(rig.bob[0]), cy = GroupSide(rig.cy[0])
        try await bob.sync()
        try await cy.sync()
        return (rig, ana, bob, cy, made.chatID, made.conversationID)
    }

    private func att(_ id: String) -> Att { Att(id: id, key: Data(count: 32), dg: Data(count: 32), sz: 5, csz: 21) }

    private func messages(_ side: GroupSide, _ chatID: String) throws -> [MessageRecord] {
        try side.repo.messages(chatID: chatID, before: nil, limit: 50).filter { $0.senderAccount != "system" }
    }

    @Test func everyContentKindFromAMemberIsStoredInTheGroupChatWithTheMLSSender() async throws {
        let s = try await started()
        try await s.bob.post(.text("hello all", replyTo: nil), in: s.chatID, at: 1_700_000_001_000)
        try await s.bob.post(.photo(att("att-p"), w: 4, h: 4, thumb: nil, replyTo: nil), in: s.chatID, at: 1_700_000_002_000)
        try await s.bob.post(.voice(att("att-v"), duration: 3, replyTo: nil), in: s.chatID, at: 1_700_000_003_000)
        try await s.bob.post(.file(att("att-f"), name: "a.txt", mime: "text/plain", replyTo: nil), in: s.chatID, at: 1_700_000_004_000)
        try await s.ana.sync()
        try await s.cy.sync()
        for side in [s.ana, s.cy] {
            let stored = try messages(side, s.chatID)
            #expect(stored.count == 4)
            #expect(Set(stored.map(\.senderAccount)) == ["acc-bob"])
            #expect(Set(stored.map(\.conversationID)) == [s.conversationID])
            #expect(try side.repo.attachment(id: "att-p") != nil && side.repo.attachment(id: "att-v") != nil && side.repo.attachment(id: "att-f") != nil)
        }
        // Control kinds from another member act on the group's messages: reaction, edit, delete.
        let first = try #require(try messages(s.ana, s.chatID).first { ContentEnvelope.decode($0.envelope).body == .text("hello all", replyTo: nil) })
        try await s.cy.post(.react(target: first.id, emoji: "👍"), in: s.chatID)
        try await s.bob.post(.edit(target: first.id, text: "hello everyone"), in: s.chatID, at: 1_700_000_010_000)
        try await s.ana.sync()
        #expect(try s.ana.repo.reactions(messageID: first.id).map { "\($0.accountID)\($0.emoji)" } == ["acc-cy👍"])
        #expect(ContentEnvelope.decode(try #require(try s.ana.repo.message(id: first.id)).envelope).body == .text("hello everyone", replyTo: nil))
        try await s.bob.post(.delete(target: first.id), in: s.chatID, at: 1_700_000_011_000)
        try await s.ana.sync()
        #expect(try s.ana.repo.message(id: first.id)?.deleted == true)
        // Nothing went to a one-to-one chat.
        #expect(try s.ana.repo.chat(id: "acc-bob") == nil)
    }

    /// Bob's stored list leaves cy out (he joined while the server's answer left cy out); the server's list now has cy.
    private func bobWithoutCyInTheStoredList() async throws -> (rig: GroupRig, ana: GroupSide, bob: GroupSide, cy: GroupSide, chatID: String, conversationID: String) {
        let rig = try await GroupRig.make()
        let ana = GroupSide(rig.ana)
        let made = try await ana.manager.createGroup(accounts: ["acc-bob", "acc-cy"], name: "Book club", photo: nil)
        try await ana.outbox.flush()
        await rig.server.hideAccountInList("acc-cy", of: made.conversationID)
        let bob = GroupSide(rig.bob[0]), cy = GroupSide(rig.cy[0])
        try await bob.sync()
        await rig.server.hideAccountInList(nil, of: made.conversationID)
        try await cy.sync()
        #expect(try bob.repo.groupMembers(chatID: made.chatID).map(\.accountID) == ["acc-ana", "acc-bob"])
        return (rig, ana, bob, cy, made.chatID, made.conversationID)
    }

    private func listFetches(_ s: (rig: GroupRig, ana: GroupSide, bob: GroupSide, cy: GroupSide, chatID: String, conversationID: String)) async -> Int {
        await s.rig.server.requests().filter { $0 == "GET /v1/conversations/\(s.conversationID)" }.count
    }

    @Test func aSenderNotInTheStoredListIsCheckedAgainstTheServersListOnceAndAcceptedWhenListed() async throws {
        let s = try await bobWithoutCyInTheStoredList()
        // The server's list moves on (version 1) and now names cy.
        await s.rig.server.dropGroupAccount("acc-nobody", from: s.conversationID)
        let before = await listFetches(s)
        try await s.cy.post(.text("one", replyTo: nil), in: s.chatID, at: 1_700_000_001_000)
        try await s.cy.post(.text("two", replyTo: nil), in: s.chatID, at: 1_700_000_002_000)
        try await s.bob.sync()
        #expect(try messages(s.bob, s.chatID).map(\.senderAccount) == ["acc-cy", "acc-cy"])
        // One look at the server's list served both messages.
        #expect(await listFetches(s) - before == 1)
    }

    @Test func aSenderTheServersListDoesNotNameEitherIsDroppedAndTheItemIsConsumed() async throws {
        let s = try await bobWithoutCyInTheStoredList()
        await s.rig.server.dropGroupAccount("acc-cy", from: s.conversationID)
        try await s.cy.post(.text("from a removed member", replyTo: nil), in: s.chatID)
        try await s.bob.sync()
        #expect(try messages(s.bob, s.chatID).isEmpty)
        #expect(try s.bob.repo.rawItems(limit: 10).isEmpty)
        // No placeholder either, and nothing counted as unread.
        #expect(try s.bob.repo.chat(id: s.chatID)?.unread == 0)
        // A second sync changes nothing and does not look again for the same item.
        try await s.bob.sync()
        #expect(try messages(s.bob, s.chatID).isEmpty)
    }

    @Test func aMessageInAGroupIAmNoLongerInIsDropped() async throws {
        let s = try await started()
        try s.bob.repo.setMyState(chatID: s.chatID, "removed")
        try await s.ana.post(.text("after removal", replyTo: nil), in: s.chatID)
        try await s.bob.sync()
        #expect(try messages(s.bob, s.chatID).isEmpty)
        #expect(try s.bob.repo.chat(id: s.chatID)?.unread == 0)
        #expect(try s.bob.repo.rawItems(limit: 10).isEmpty)
    }

    @Test func aTimerEnvelopeCountsOnlyFromAnOwnerOrAdmin() async throws {
        let s = try await started()
        // A plain member: refused, no line, no change.
        try await s.cy.post(.timer(seconds: 86_400), in: s.chatID, at: 1_700_000_001_000)
        try await s.bob.sync()
        #expect(try s.bob.repo.timerSeconds(chatID: s.chatID) == 0)
        #expect(try s.bob.repo.messages(chatID: s.chatID, before: nil, limit: 20).count == 1)   // only "added you"
        // The owner: applied with one line.
        try await s.ana.post(.timer(seconds: 604_800), in: s.chatID, at: 1_700_000_002_000)
        try await s.bob.sync()
        #expect(try s.bob.repo.timerSeconds(chatID: s.chatID) == 604_800)
        #expect(try s.bob.repo.messages(chatID: s.chatID, before: nil, limit: 20).count == 2)
        // A one-to-one chat keeps accepting its peer's timer (unchanged rule): covered by the existing people tests.
    }
}
