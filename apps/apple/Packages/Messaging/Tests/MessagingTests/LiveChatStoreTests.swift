import AppCore
import Foundation
import LocalStore
import SecureMessagingCore
import ServerKit
import Testing
@testable import Messaging
import MessagingTestKit

private final class StoreClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current = testEpochDate
    func now() -> Date { lock.withLock { current } }
    func advance(_ seconds: TimeInterval = 1) { lock.withLock { current = current.addingTimeInterval(seconds) } }
}

/// The Read receipts switch, as the app's settings hold it.
private final class ReceiptSwitch: @unchecked Sendable {
    private let lock = NSLock()
    private var value = true
    var on: Bool {
        get { lock.withLock { value } }
        set { lock.withLock { value = newValue } }
    }
}

@MainActor
private struct Side {
    let device: TestDevice
    let readReceipts = ReceiptSwitch()
    let outbox: Outbox
    let inbox: InboxProcessor
    let store: LiveChatStore
    var repo: Repository { device.deps.repo }

    init(_ device: TestDevice) {
        self.device = device
        let deps = device.deps
        let conversations = ConversationManager(deps: deps, escrowKey: { Data(repeating: 7, count: 32) })
        let readReceipts = readReceipts
        outbox = Outbox(deps: deps, conversations: conversations, readReceiptsEnabled: { readReceipts.on })
        inbox = InboxProcessor(
            deps: deps, conversations: conversations, handler: IncomingHandler(me: deps.me, now: deps.now, log: { _ in }),
            controls: outbox)
        store = LiveChatStore(deps: deps, outbox: outbox, inbox: inbox, conversations: conversations, attachments: nil)
    }
}

@MainActor
private struct Rig {
    let server: FakeServer
    let clock: StoreClock
    let ana: Side
    let bob: Side

    static func make() async throws -> Rig {
        let server = FakeServer()
        let clock = StoreClock()
        let anaDevice = await makeDevice(server, account: "acc-ana", username: "ana", now: clock.now)
        let bobDevice = await makeDevice(server, account: "acc-bob", username: "bob", now: clock.now)
        for device in [anaDevice, bobDevice] {
            _ = try await device.deps.server.uploadKeyPackages(try await device.deps.mls.makeKeyPackages(count: 3))
        }
        return Rig(server: server, clock: clock, ana: Side(anaDevice), bob: Side(bobDevice))
    }

    /// Ana adds bob as a contact and opens a chat with him.
    func anaChatsWithBob() async throws {
        _ = await ana.store.addContact(username: "bob")
        _ = ana.store.ensureChat(with: "acc-bob")
    }

    func insertText(_ side: Side, chat: String, id: String, from sender: String, text: String, status: String = "delivered") throws {
        let at = clock.now()
        let envelope = ContentEnvelope(id: id, at: Int64(at.timeIntervalSince1970 * 1000), body: .text(text, replyTo: nil))
        try side.repo.insertMessage(MessageRecord(
            id: id, chatID: chat, conversationID: nil, senderAccount: sender, senderDevice: nil, sentAt: at, receivedAt: at,
            envelope: envelope.encoded(), status: status, replyTo: nil, editedAt: nil, deleted: false, undecryptable: false,
            folded: fold(text)))
    }
}

private func text(_ message: Message?) -> String? {
    if case .text(let t)? = message?.content { return t }
    return nil
}

@Suite("TestLiveChatStore") @MainActor struct TestLiveChatStore {
    @Test func chatsListedByActivity() async throws {
        let rig = try await Rig.make()
        let store = rig.ana.store
        for id in ["acc-x", "acc-y", "acc-z"] {
            rig.clock.advance()
            try rig.ana.repo.upsertContact(ContactRecord(
                accountID: id, username: id, displayName: id, isContact: true, addedAt: rig.clock.now()))
            _ = store.ensureChat(with: id)
        }
        #expect(store.chats(in: .all).map(\.id) == ["acc-z", "acc-y", "acc-x"])
        rig.clock.advance()
        store.send(.text("hello"), in: "acc-x")
        #expect(store.chats(in: .all).map(\.id) == ["acc-x", "acc-z", "acc-y"])
        #expect(store.chats(in: .unread).isEmpty)
        await store.settle()
    }

    @Test func sendShowsSendingThenSent() async throws {
        let rig = try await Rig.make()
        try await rig.anaChatsWithBob()
        rig.clock.advance()
        rig.ana.store.send(.text("hi bob"), in: "acc-bob")
        // Right after `send`, before the flush has run.
        #expect(rig.ana.store.chat(id: "acc-bob")?.messages.last?.status == .sending)
        #expect(text(rig.ana.store.chat(id: "acc-bob")?.messages.last) == "hi bob")
        await rig.ana.store.settle()
        #expect(rig.ana.store.chat(id: "acc-bob")?.messages.last?.status == .sent)

        // Bob syncs: his inbox processor sends the delivered receipt.
        await rig.bob.store.syncInbox()
        await rig.bob.store.settle()
        #expect(text(rig.bob.store.chat(id: "acc-ana")?.messages.last) == "hi bob")
        await rig.ana.store.syncInbox()
        #expect(rig.ana.store.chat(id: "acc-bob")?.messages.last?.status == .delivered)

        rig.bob.store.accept(requestID: "acc-ana")
        rig.bob.store.markRead(chatID: "acc-ana")
        #expect(rig.bob.store.chat(id: "acc-ana")?.unreadCount == 0)
        await rig.bob.store.settle()
        await rig.ana.store.syncInbox()
        #expect(rig.ana.store.chat(id: "acc-bob")?.messages.last?.status == .read)
    }

    @Test func requestAcceptFlow() async throws {
        let rig = try await Rig.make()
        try await rig.anaChatsWithBob()
        rig.ana.store.send(.text("hello"), in: "acc-bob")
        await rig.ana.store.settle()
        await rig.bob.store.syncInbox()
        let bob = rig.bob.store
        #expect(bob.messageRequests.map(\.id) == ["acc-ana"])
        #expect(bob.messageRequestsRowTitle == "Message requests · 1")
        #expect(bob.chats(in: .all).isEmpty)
        #expect(bob.chat(id: "acc-ana")?.unreadCount == 1)

        bob.accept(requestID: "acc-ana")
        #expect(bob.messageRequests.isEmpty)
        #expect(bob.messageRequestsRowTitle == nil)
        #expect(bob.chats(in: .all).map(\.id) == ["acc-ana"])
        #expect(bob.contacts.map(\.id) == ["acc-ana"])
        #expect(bob.chats(in: .unread).map(\.id) == ["acc-ana"])
        #expect(bob.folderChipTitle(.unread) == "Unread 1")
    }

    @Test func blockHidesAndCallsServer() async throws {
        let rig = try await Rig.make()
        try await rig.anaChatsWithBob()
        #expect(rig.ana.store.chats(in: .all).map(\.id) == ["acc-bob"])
        rig.ana.store.block(contactID: "acc-bob")
        #expect(rig.ana.store.chats(in: .all).isEmpty)
        #expect(rig.ana.store.contacts.isEmpty)
        await rig.ana.store.settle()
        #expect(await rig.server.requests().contains("PUT /v1/blocks/acc-bob"))
        #expect(try rig.ana.repo.isBlocked("acc-bob"))
    }

    @Test func reportSendsLastTen() async throws {
        let rig = try await Rig.make()
        try await rig.anaChatsWithBob()
        for i in 1...12 {
            rig.clock.advance()
            try rig.insertText(rig.ana, chat: "acc-bob", id: "m\(i)", from: "acc-bob", text: "line \(i)")
        }
        rig.ana.store.report(chatID: "acc-bob")
        await rig.ana.store.settle()
        let reports = await rig.server.submittedReports()
        #expect(reports.count == 1)
        #expect(reports.first?["messages"] == "10")
        #expect(reports.first?["reason"] == "abuse")
        #expect(reports.first?["accountID"] == "acc-bob")
    }

    @Test func editOnlyOwn() async throws {
        let rig = try await Rig.make()
        try await rig.anaChatsWithBob()
        rig.clock.advance()
        try rig.insertText(rig.ana, chat: "acc-bob", id: "theirs", from: "acc-bob", text: "original")
        rig.clock.advance()
        try rig.insertText(rig.ana, chat: "acc-bob", id: "mine", from: "acc-ana", text: "mine", status: "sent")
        let store = rig.ana.store
        store.refresh()

        store.edit(messageID: "theirs", in: "acc-bob", text: "changed")
        store.delete(messageID: "theirs", in: "acc-bob")
        await store.settle()
        let theirs = store.messages(in: "acc-bob", before: nil, limit: 10).first { $0.id == "theirs" }
        #expect(text(theirs) == "original")
        #expect(theirs?.editedAt == nil)
        #expect(await rig.server.requests().filter { $0.hasPrefix("POST /v1/conversations") }.isEmpty)

        store.edit(messageID: "mine", in: "acc-bob", text: "better")
        let mine = store.messages(in: "acc-bob", before: nil, limit: 10).first { $0.id == "mine" }
        #expect(text(mine) == "better")
        #expect(mine?.editedAt != nil)
        store.delete(messageID: "mine", in: "acc-bob")
        let gone = store.messages(in: "acc-bob", before: nil, limit: 10).first { $0.id == "mine" }
        #expect(gone?.content == .deleted)
        await store.settle()
    }

    @Test func onChangeCalledAfterInbox() async throws {
        let rig = try await Rig.make()
        try await rig.anaChatsWithBob()
        rig.ana.store.send(.text("ping"), in: "acc-bob")
        await rig.ana.store.settle()

        var calls = 0
        var seenAtCall: String?
        let bob = rig.bob.store
        bob.onChange = { calls += 1; seenAtCall = text(bob.chat(id: "acc-ana")?.messages.last) }
        await bob.syncInbox()
        #expect(calls >= 1)
        #expect(seenAtCall == "ping")
        await bob.settle()
    }

    @Test func encryptionFlagFromMode() async throws {
        let rig = try await Rig.make()
        let store = rig.ana.store
        _ = store.ensureChat(with: "acc-bob")
        try rig.ana.repo.upsertConversation(ConversationRecord(
            serverID: "c1", chatID: "acc-bob", groupID: Data([1]), mode: "server_readable", innerMode: "server_readable",
            modeMismatch: false, epoch: 1, active: true, createdAt: testEpochDate))
        store.refresh()
        #expect(store.chat(id: "acc-bob")?.encryption == .serverReadable)
        #expect(store.chat(id: "acc-bob")?.modeMismatch == false)
    }

    @Test func encryptionFlagIgnoresServerClaim() async throws {
        let rig = try await Rig.make()
        let store = rig.ana.store
        _ = store.ensureChat(with: "acc-bob")
        try rig.ana.repo.upsertConversation(ConversationRecord(
            serverID: "c1", chatID: "acc-bob", groupID: Data([1]), mode: "server_readable", innerMode: "e2ee",
            modeMismatch: false, epoch: 1, active: true, createdAt: testEpochDate))
        try rig.ana.repo.flagModeMismatch(serverID: "c1")
        store.refresh()
        #expect(store.chat(id: "acc-bob")?.encryption == .endToEnd)
        #expect(store.chat(id: "acc-bob")?.modeMismatch == true)
    }

    @Test func unknownInnerModeIsNotServerReadable() async throws {
        let rig = try await Rig.make()
        _ = rig.ana.store.ensureChat(with: "acc-bob")
        try rig.ana.repo.upsertConversation(ConversationRecord(
            serverID: "c1", chatID: "acc-bob", groupID: Data([1]), mode: "server_readable", innerMode: nil,
            modeMismatch: false, epoch: 1, active: true, createdAt: testEpochDate))
        rig.ana.store.refresh()
        #expect(rig.ana.store.chat(id: "acc-bob")?.encryption == .endToEnd)
    }

    @Test func verifiedLapsesOnChange() async throws {
        let rig = try await Rig.make()
        let store = rig.ana.store
        let requestsBefore = await rig.server.requests().count
        _ = store.ensureChat(with: "acc-bob")
        #expect(store.safetyNumber(for: "acc-bob") == nil)
        let first = (0..<12).map { String(format: "%05d", $0 * 7 + 1) }.joined()
        try rig.ana.repo.setSafetyCurrent(chatID: "acc-bob", first)
        let before = try #require(store.safetyNumber(for: "acc-bob"))
        #expect(before.groups.count == 12 && before.groups.allSatisfy { $0.count == 5 })
        #expect(before.groups.joined() == first)
        #expect(!before.isVerified)

        store.setVerified(true, chatID: "acc-bob")
        #expect(store.safetyNumber(for: "acc-bob")?.isVerified == true)
        // A4 stores a new number after a device change: the old verification no longer counts.
        try rig.ana.repo.setSafetyCurrent(chatID: "acc-bob", String(repeating: "9", count: 60))
        #expect(store.safetyNumber(for: "acc-bob")?.isVerified == false)
        store.setVerified(true, chatID: "acc-bob")
        store.setVerified(false, chatID: "acc-bob")
        #expect(store.safetyNumber(for: "acc-bob")?.isVerified == false)
        #expect(try rig.ana.repo.safety(chatID: "acc-bob")?.verified == nil)
        await store.settle()
        #expect(await rig.server.requests().count == requestsBefore)
    }

    @Test func systemLineMapping() async throws {
        let rig = try await Rig.make()
        let repo = rig.bob.repo
        try repo.upsertContact(ContactRecord(accountID: "acc-ana", username: "ana", displayName: "Ana", isContact: true, addedAt: testEpochDate))
        _ = rig.bob.store.ensureChat(with: "acc-ana")
        func line(_ id: String, _ event: SystemEvent, offset: TimeInterval) throws {
            let at = testEpochDate.addingTimeInterval(offset)
            try repo.insertMessage(MessageRecord(
                id: id, chatID: "acc-ana", conversationID: nil, senderAccount: "system", senderDevice: nil, sentAt: at,
                receivedAt: at, envelope: try JSONEncoder().encode(event), status: "sent", replyTo: nil, editedAt: nil,
                deleted: false, undecryptable: false, folded: ""))
        }
        try line("s1", .deviceAdded(name: "acc-ana"), offset: 1)
        try line("s2", .deviceRemoved(name: nil), offset: 2)
        try line("s3", .deviceAdded(name: "acc-unknown"), offset: 3)
        try line("s4", .safetyNumberChanged, offset: 4)
        rig.bob.store.refresh()
        let messages = rig.bob.store.messages(in: "acc-ana", before: nil, limit: 10)
        #expect(messages.map(\.content) == [
            .system(.deviceAdded(name: "Ana")), .system(.deviceRemoved(name: nil)),
            .system(.deviceAdded(name: "Someone")), .system(.safetyNumberChanged),
        ])
        #expect(messages.first?.previewText == "Ana added a new device")
    }

    @Test func callsAndIncomingCallRule() async throws {
        let rig = try await Rig.make()
        let store = rig.ana.store
        try await rig.anaChatsWithBob()
        rig.clock.advance()
        store.recordCall(.audio(duration: 90), contactID: "acc-bob", outgoing: true, at: rig.clock.now())
        rig.clock.advance()
        store.recordCall(.missedVideo, contactID: "acc-bob", outgoing: false, at: rig.clock.now())
        let messages = try #require(store.chat(id: "acc-bob")).messages
        #expect(messages.map(\.content) == [.call(.audio(duration: 90)), .call(.missedVideo)])
        #expect(messages.map(\.status) == [.sent, .delivered])
        #expect(messages.map(\.senderID) == ["acc-ana", "acc-bob"])
        #expect(try rig.ana.repo.calls(limit: 10).count == 2)

        let bob = Contact(id: "acc-bob", displayName: "Bob", username: "bob")
        #expect(store.incomingCall(from: bob, video: false) == .ring)
        let stranger = Contact(id: "acc-eve", displayName: "Eve", username: "eve")
        #expect(store.incomingCall(from: stranger, video: true) == .silentMissed)
        #expect(store.messageRequests.map(\.id) == ["acc-eve"])
        #expect(store.chat(id: "acc-eve")?.messages.map(\.content) == [.call(.missedVideo)])
    }

    @Test func foldersPagingAndSearch() async throws {
        let rig = try await Rig.make()
        let store = rig.ana.store
        try await rig.anaChatsWithBob()
        for i in 1...5 {
            rig.clock.advance()
            try rig.insertText(rig.ana, chat: "acc-bob", id: "m\(i)", from: "acc-bob", text: i == 3 ? "Café plans" : "line \(i)")
        }
        store.refresh()
        #expect(store.messages(in: "acc-bob", before: nil, limit: 2).map(\.id) == ["m4", "m5"])
        #expect(store.messages(in: "acc-bob", before: "m4", limit: 2).map(\.id) == ["m2", "m3"])
        #expect(store.messages(in: "acc-bob", before: "nope", limit: 2).isEmpty)
        #expect(store.searchMessages("  CAFE ", limit: 10).map(\.messageID) == ["m3"])
        #expect(store.searchMessages("", limit: 10).isEmpty)

        let work = store.addFolder(name: "Work", chatIDs: ["acc-bob"])
        let more = store.addFolder(name: "More", chatIDs: [])
        #expect(store.folders.map(\.name) == ["All", "Unread", "Work", "More"])
        #expect(store.chats(in: work).map(\.id) == ["acc-bob"])
        #expect(store.chats(in: more).isEmpty)
        store.renameFolder(id: more.id, to: "Family")
        store.moveFolder(id: more.id, toCustomIndex: 0)
        #expect(store.folders.map(\.name) == ["All", "Unread", "Family", "Work"])
        store.deleteFolder(id: work.id)
        #expect(store.folders.map(\.name) == ["All", "Unread", "Family"])
        #expect(store.chat(id: "acc-bob")?.folderIDs.isEmpty == true)
    }

    @Test func reactionsToggleAndShow() async throws {
        let rig = try await Rig.make()
        try await rig.anaChatsWithBob()
        rig.clock.advance()
        try rig.insertText(rig.ana, chat: "acc-bob", id: "m1", from: "acc-bob", text: "hey")
        let store = rig.ana.store
        store.react("👍", to: "m1", in: "acc-bob")
        #expect(store.messages(in: "acc-bob", before: nil, limit: 5).first?.reactions == ["👍"])
        store.react("👍", to: "m1", in: "acc-bob")
        #expect(store.messages(in: "acc-bob", before: nil, limit: 5).first?.reactions == [])
        await store.settle()
    }

    @Test func addContactUsesLookup() async throws {
        let rig = try await Rig.make()
        let found = await rig.ana.store.addContact(username: " Bob ")
        #expect(found?.id == "acc-bob" && found?.username == "bob")
        #expect(rig.ana.store.contacts.map(\.id) == ["acc-bob"])
        #expect(rig.ana.store.contact(id: "acc-bob")?.username == "bob")
        #expect(await rig.ana.store.addContact(username: "nobody") == nil)
        #expect(rig.ana.store.ensureChat(with: "acc-bob") == "acc-bob")
        #expect(rig.ana.store.chat(id: "acc-bob")?.isRequest == false)
    }

    @Test func noReadReceiptForRequestUntilAccepted() async throws {
        let rig = try await Rig.make()
        try await rig.anaChatsWithBob()
        rig.ana.store.send(.text("hello"), in: "acc-bob")
        await rig.ana.store.settle()
        await rig.bob.store.syncInbox()
        await rig.bob.store.settle()
        await rig.ana.store.syncInbox()
        let bob = rig.bob.store
        #expect(bob.chat(id: "acc-ana")?.isRequest == true)

        bob.markRead(chatID: "acc-ana")
        await bob.settle()
        #expect(bob.chat(id: "acc-ana")?.unreadCount == 0)
        #expect(bob.chat(id: "acc-ana")?.messages.last?.status == .read)   // locally
        await rig.ana.store.syncInbox()
        #expect(rig.ana.store.chat(id: "acc-bob")?.messages.last?.status == .delivered)

        bob.accept(requestID: "acc-ana")
        await bob.settle()
        await rig.ana.store.syncInbox()
        #expect(rig.ana.store.chat(id: "acc-bob")?.messages.last?.status == .read)
    }

    /// Ana sends "hello"; Bob syncs (delivered receipt), accepts the request, and Ana syncs.
    private func helloDeliveredAndAccepted(_ rig: Rig) async throws {
        try await rig.anaChatsWithBob()
        rig.ana.store.send(.text("hello"), in: "acc-bob")
        await rig.ana.store.settle()
        await rig.bob.store.syncInbox()
        await rig.bob.store.settle()
        rig.bob.store.accept(requestID: "acc-ana")
        await rig.bob.store.settle()
        await rig.ana.store.syncInbox()
        #expect(rig.ana.store.chat(id: "acc-bob")?.messages.last?.status == .delivered)
    }

    @Test func readReceiptsOffSendsNoReadReceiptAndIsNotRetroactive() async throws {
        let rig = try await Rig.make()
        try await helloDeliveredAndAccepted(rig)
        let bob = rig.bob.store
        rig.bob.readReceipts.on = false
        bob.markRead(chatID: "acc-ana")
        await bob.settle()
        #expect(bob.chat(id: "acc-ana")?.unreadCount == 0)
        await rig.ana.store.syncInbox()
        #expect(rig.ana.store.chat(id: "acc-bob")?.messages.last?.status == .delivered)

        // Turning it back on sends nothing for messages read while it was off.
        rig.bob.readReceipts.on = true
        bob.markRead(chatID: "acc-ana")
        await bob.flushOutbox()
        await bob.settle()
        await rig.ana.store.syncInbox()
        #expect(rig.ana.store.chat(id: "acc-bob")?.messages.last?.status == .delivered)
    }

    @Test func acceptWithReadReceiptsOffSendsNoReadReceipt() async throws {
        let rig = try await Rig.make()
        try await rig.anaChatsWithBob()
        rig.ana.store.send(.text("hello"), in: "acc-bob")
        await rig.ana.store.settle()
        await rig.bob.store.syncInbox()
        await rig.bob.store.settle()
        let bob = rig.bob.store
        rig.bob.readReceipts.on = false
        bob.markRead(chatID: "acc-ana")
        bob.accept(requestID: "acc-ana")
        await bob.settle()
        await rig.ana.store.syncInbox()
        #expect(rig.ana.store.chat(id: "acc-bob")?.messages.last?.status == .delivered)
    }

    @Test func queuedReadReceiptIsDroppedWhenSwitchedOff() async throws {
        let rig = try await Rig.make()
        try await helloDeliveredAndAccepted(rig)
        let id = try #require(rig.bob.store.chat(id: "acc-ana")?.messages.last?.id)
        // Queued while on (or on another device), found by the outbox after the switch went off.
        try rig.bob.outbox.enqueueControl(.receipt(.read, ids: [id]), chatID: "acc-ana")
        rig.bob.readReceipts.on = false
        await rig.bob.store.flushOutbox()
        #expect(try rig.bob.repo.dueOutbox(at: .distantFuture).isEmpty)
        // Not held: nothing is sent when it goes back on.
        rig.bob.readReceipts.on = true
        await rig.bob.store.flushOutbox()
        await rig.ana.store.syncInbox()
        #expect(rig.ana.store.chat(id: "acc-bob")?.messages.last?.status == .delivered)
    }

    @Test func othersReadTicksHiddenWhileSwitchOff() async throws {
        let rig = try await Rig.make()
        try await helloDeliveredAndAccepted(rig)
        rig.bob.store.markRead(chatID: "acc-ana")
        await rig.bob.store.settle()
        await rig.ana.store.syncInbox()
        #expect(rig.ana.store.chat(id: "acc-bob")?.messages.last?.status == .read)

        rig.ana.readReceipts.on = false
        rig.ana.store.refresh()
        #expect(rig.ana.store.chat(id: "acc-bob")?.messages.last?.status == .delivered)
        // Bob's own view of the message he read is unchanged by Ana's switch.
        #expect(rig.bob.store.chat(id: "acc-ana")?.messages.last?.status == .read)

        // Stored as read, so it shows again when the switch goes back on.
        rig.ana.readReceipts.on = true
        rig.ana.store.refresh()
        #expect(rig.ana.store.chat(id: "acc-bob")?.messages.last?.status == .read)
    }

    @Test func ownViewOfReadIncomingMessagesUnchangedWhenSwitchOff() async throws {
        let rig = try await Rig.make()
        try await helloDeliveredAndAccepted(rig)
        rig.bob.readReceipts.on = false
        rig.bob.store.markRead(chatID: "acc-ana")
        await rig.bob.store.settle()
        #expect(rig.bob.store.chat(id: "acc-ana")?.messages.last?.status == .read)
    }

    @Test func controlsQueueBehindTheirMessageAndRetry() async throws {
        let rig = try await Rig.make()
        try await rig.anaChatsWithBob()
        let store = rig.ana.store
        await rig.server.failNextMessages(statuses: [503])
        rig.clock.advance()
        store.send(.text("first"), in: "acc-bob")
        let id = try #require(store.chat(id: "acc-bob")?.messages.last?.id)
        store.edit(messageID: id, in: "acc-bob", text: "edited")   // queued before the message has left
        await store.settle()
        // The message failed once (503) and backs off; the edit must not have overtaken it.
        let allIDs = await rig.server.conversationIDs()
        let conversation = try #require(allIDs.first)
        #expect(await rig.server.storedMessages(in: conversation).isEmpty)
        rig.clock.advance(5)
        await store.flushOutbox()
        await rig.bob.store.syncInbox()
        await rig.bob.store.settle()
        let seen = rig.bob.store.messages(in: "acc-ana", before: nil, limit: 5).first
        #expect(text(seen) == "edited")
        #expect(seen?.editedAt != nil)
    }

    @Test func blockAndReportRetryUntilServerGetsThemOnce() async throws {
        let rig = try await Rig.make()
        try await rig.anaChatsWithBob()
        let store = rig.ana.store
        rig.clock.advance()
        try rig.insertText(rig.ana, chat: "acc-bob", id: "m1", from: "acc-bob", text: "rude")
        store.refresh()
        await rig.server.failNextRequests("PUT /v1/blocks", count: 1)
        await rig.server.failNextRequests("POST /v1/reports", count: 1)
        store.report(chatID: "acc-bob")
        store.block(contactID: "acc-bob")
        #expect(store.chats(in: .all).isEmpty)   // local state applies at once
        await store.settle()
        // Whether the background runs have already retried past the injected failures depends on how they
        // interleave (a request made during a run makes it go round again), so only the end state is fixed:
        // each request reaches the server exactly once.
        await store.flushOutbox()
        await store.flushOutbox()
        #expect(await rig.server.requests().filter { $0 == "PUT /v1/blocks/acc-bob" }.count == 1)
        #expect(await rig.server.submittedReports().count == 1)
        await store.syncInbox()
        #expect(await rig.server.requests().filter { $0 == "PUT /v1/blocks/acc-bob" }.count == 1)
        #expect(await rig.server.submittedReports().count == 1)
    }
}

@MainActor
@Suite("TestLiveChatStorePendingCreate") struct TestLiveChatStorePendingCreate {
    /// A create whose commit never reached the server is finished by the next inbox sync (the app's launch sync).
    @Test func syncInboxFinishesAPendingCreate() async throws {
        let rig = try await Rig.make()
        await rig.server.failNextRequests("POST /v1/conversations/", count: 1)
        await #expect(throws: (any Error).self) {
            try await ConversationManager(deps: rig.ana.device.deps, escrowKey: { Data(repeating: 7, count: 32) })
                .preferredConversation(with: "acc-bob")
        }
        let serverID = try #require(await rig.server.conversationIDs().first)
        #expect(try rig.ana.repo.conversation(serverID: serverID)?.active == false)

        await rig.ana.store.syncInbox()
        #expect(try rig.ana.repo.conversation(serverID: serverID)?.active == true)
        #expect(await rig.server.epoch(of: serverID) == 1)
        #expect(await rig.server.conversationIDs() == [serverID])
    }
}
