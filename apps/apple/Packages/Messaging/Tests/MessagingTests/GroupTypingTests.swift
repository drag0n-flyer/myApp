import AppCore
import Foundation
import LocalStore
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

@MainActor
private final class FakeSender: SignalSending {
    var sent: [(chatID: String, body: ContentEnvelope.Body)] = []
    func send(chatID: String, body: ContentEnvelope.Body) async { sent.append((chatID, body)) }
}

@MainActor
private final class Clock { var ms: Int64 = 1_000_000 }

@MainActor @Suite("GroupTyping") struct GroupTypingSignalsTests {
    private func make() -> (PeopleSignals, Clock, FakeSender) {
        let clock = Clock(), sender = FakeSender()
        let signals = PeopleSignals(
            sender: sender, now: { clock.ms }, typingEnabled: { true }, presenceEnabled: { true }, isBlocked: { _ in false },
            presenceTargets: { [] }, loadSeen: { _ in nil }, saveSeen: { _, _ in })
        signals.groupMembers = { _ in ["acc-me", "acc-a", "acc-b", "acc-c"] }
        signals.blockedAccounts = { ["acc-c"] }
        signals.myAccount = "acc-me"
        return (signals, clock, sender)
    }

    private func typing(_ account: String, on: Bool = true) -> EphemeralSignal {
        EphemeralSignal(chatID: "g:1", accountID: account, body: .typing(on: on))
    }

    @Test func eachMemberTypesOnTheirOwnAndEndsOnTheirOwn() {
        let (signals, clock, _) = make()
        #expect(!signals.isTyping(chatID: "g:1") && signals.typingAccounts(chatID: "g:1").isEmpty)
        signals.ingest(typing("acc-a"))
        signals.ingest(typing("acc-b"))
        #expect(signals.typingAccounts(chatID: "g:1") == ["acc-a", "acc-b"] && signals.isTyping(chatID: "g:1"))
        signals.ingest(typing("acc-a", on: false))
        #expect(signals.typingAccounts(chatID: "g:1") == ["acc-b"])
        // Ten seconds without a new signal: over.
        clock.ms += 10_000
        #expect(signals.typingAccounts(chatID: "g:1").isEmpty && !signals.isTyping(chatID: "g:1"))
    }

    @Test func aBlockedOrOutsideAccountAndPresenceNeverShow() {
        let (signals, _, _) = make()
        signals.ingest(typing("acc-c"))         // blocked
        signals.ingest(typing("acc-stranger"))  // not a member
        signals.ingest(typing("acc-me"))        // me
        signals.ingest(EphemeralSignal(chatID: "g:1", accountID: "acc-a", body: .presence(.online)))
        #expect(signals.typingAccounts(chatID: "g:1").isEmpty)
        #expect(signals.presence(chatID: "g:1") == .none)
    }

    @Test func aMessageFromAMemberEndsOnlyTheirTyping() {
        let (signals, _, _) = make()
        signals.incomingMessage(chatID: "g:1", id: "m0", from: "acc-a")   // the first look only records
        signals.ingest(typing("acc-a"))
        signals.ingest(typing("acc-b"))
        signals.incomingMessage(chatID: "g:1", id: "m1", from: "acc-a")
        #expect(signals.typingAccounts(chatID: "g:1") == ["acc-b"])
    }

    @Test func theComposerSendsTypingToAGroupAndNeverPresence() async {
        let (signals, clock, sender) = make()
        signals.composerChanged(chatID: "g:1", isEmpty: false)
        clock.ms += 100
        await signals.flush()
        #expect(sender.sent.map(\.chatID) == ["g:1"])
        #expect(sender.sent.first.map { $0.body == .typing(on: true) } == true)
    }
}

private let escrow = Data(repeating: 7, count: 32)

@Suite("GroupEphemeral") struct GroupEphemeralTests {
    private actor Sent: EphemeralSending {
        var conversations: [String] = []
        func sendEphemeral(conversationID: String, epoch: UInt64, ciphertext: Data) async throws { conversations.append(conversationID) }
    }

    private func rig() async throws -> (EphemeralChannel, Repository, Sent) {
        let server = FakeServer()
        let device = await makeDevice(server, account: "acc-ana", username: "ana")
        let repo = device.deps.repo
        try repo.upsertGroup(conversationID: "conv-g", title: "Club", photo: nil, at: testEpochDate)
        try repo.setGroupMembers(chatID: "g:conv-g", [
            GroupMemberRecord(accountID: "acc-ana", role: "owner"), GroupMemberRecord(accountID: "acc-bob", role: "member"),
            GroupMemberRecord(accountID: "acc-cy", role: "member")], version: 0)
        try repo.upsertConversation(ConversationRecord(
            serverID: "conv-g", chatID: "g:conv-g", groupID: Data([9]), mode: "e2ee", innerMode: "e2ee", modeMismatch: false,
            epoch: 3, active: true, createdAt: testEpochDate))
        let sent = Sent()
        return (EphemeralChannel(repo: repo, me: "acc-ana", sender: sent), repo, sent)
    }

    private func frame(_ body: ContentEnvelope.Body, from account: String) -> ServerEvent {
        .conversationEphemeral(
            conversationID: "conv-g", epoch: 3, fromAccountID: account, fromDeviceID: "d",
            ciphertext: ContentEnvelope(id: "e", at: 1, body: body).encoded())
    }

    @Test func typingFromAMemberIsPassedOnAndNothingElseIs() async throws {
        let (channel, repo, _) = try await rig()
        try repo.block("acc-cy")
        channel.ingest(frame(.typing(on: true), from: "acc-bob"))
        channel.ingest(frame(.typing(on: true), from: "acc-cy"))          // blocked
        channel.ingest(frame(.typing(on: true), from: "acc-stranger"))    // not a member
        channel.ingest(frame(.typing(on: true), from: "acc-ana"))         // me
        channel.ingest(frame(.presence(.online), from: "acc-bob"))        // no presence in a group
        channel.finish()
        var got: [EphemeralSignal] = []
        for await signal in channel.events { got.append(signal) }
        #expect(got == [EphemeralSignal(chatID: "g:conv-g", accountID: "acc-bob", body: .typing(on: true))])
    }

    @Test func typingGoesToTheGroupButPresenceIsRefused() async throws {
        let (channel, _, sent) = try await rig()
        try await channel.send(chatID: "g:conv-g", body: .typing(on: true))
        await #expect(throws: EphemeralError.refused) { try await channel.send(chatID: "g:conv-g", body: .presence(.online)) }
        #expect(await sent.conversations == ["conv-g"])
    }

    @Test func nothingIsPassedOnWhileIAmNotInTheGroup() async throws {
        let (channel, repo, _) = try await rig()
        try repo.setMyState(chatID: "g:conv-g", "removed")
        channel.ingest(frame(.typing(on: true), from: "acc-bob"))
        channel.finish()
        var got: [EphemeralSignal] = []
        for await signal in channel.events { got.append(signal) }
        #expect(got.isEmpty)
    }
}
