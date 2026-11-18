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

@MainActor
private struct Side {
    let device: TestDevice
    let outbox: Outbox
    let conversations: ConversationManager
    let store: LiveChatStore
    var repo: Repository { device.deps.repo }

    init(_ device: TestDevice) {
        self.device = device
        let deps = device.deps
        conversations = ConversationManager(deps: deps, escrowKey: { Data(repeating: 7, count: 32) })
        outbox = Outbox(deps: deps, conversations: conversations, readReceiptsEnabled: { true })
        let inbox = InboxProcessor(
            deps: deps, conversations: conversations, handler: IncomingHandler(me: deps.me, now: deps.now, log: { _ in }),
            controls: outbox)
        store = LiveChatStore(deps: deps, outbox: outbox, inbox: inbox, conversations: conversations, attachments: nil)
    }

    /// Sends an envelope exactly as given, through the chat's conversation, as an ordinary stored message would travel.
    func sendRaw(_ envelope: ContentEnvelope, to chatID: String) async throws {
        let conversation = try await conversations.preferredConversation(with: chatID)
        try await outbox.send(envelope, in: conversation)
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
        let rig = Rig(server: server, clock: clock, ana: Side(anaDevice), bob: Side(bobDevice))
        _ = await rig.ana.store.addContact(username: "bob")
        _ = rig.ana.store.ensureChat(with: "acc-bob")
        return rig
    }

    var ms: Int64 { Int64((clock.now().timeIntervalSince1970 * 1000).rounded()) }

    func envelope(_ id: String, _ body: ContentEnvelope.Body, ttl: Int? = nil, at: Int64? = nil) -> ContentEnvelope {
        ContentEnvelope(id: id, at: at ?? ms, body: body, ttl: ttl)
    }

    func bobSync() async {
        await bob.store.syncInbox()
        await bob.store.settle()
    }

    func bobMessages() throws -> [MessageRecord] { try bob.repo.messages(chatID: "acc-ana", before: nil, limit: 100) }
}

private let jpeg = Data([0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x0B, 0x08, 0x01, 0x00, 0x01, 0x00, 0x01, 0x01, 0x11, 0x00]) + Data(count: 100)

@Suite("TestPeopleStore") @MainActor struct TestPeopleStore {
    // MARK: timer and ttl on send

    @Test func aTimerMakesTheNextSendCarryTtlAndExpiresAt() async throws {
        let rig = try await Rig.make()
        let ana = rig.ana.store
        #expect(ana.timerSeconds("acc-bob") == 0)
        ana.setTimer("acc-bob", seconds: 604_800)
        #expect(ana.timerSeconds("acc-bob") == 604_800)
        await ana.settle()
        ana.send(.text("hi"), in: "acc-bob")
        await ana.settle()

        let sent = try rig.ana.repo.messages(chatID: "acc-bob", before: nil, limit: 10).filter { $0.senderAccount == "acc-ana" }
        let text = try #require(sent.first)
        let envelope = ContentEnvelope.decode(text.envelope)
        #expect(envelope.ttl == 604_800)
        #expect(text.expiresAt == Date(timeIntervalSince1970: testEpochDate.timeIntervalSince1970 + 604_800))

        // The server was told: the control message carries none, the text carries expiresAt.
        let conversation = try #require(await rig.server.conversationIDs().first)
        let posted = await rig.server.storedMessages(in: conversation).filter { $0.senderDeviceID == rig.ana.device.deviceID }
        let expiries = posted.compactMap(\.expiresAt)
        #expect(posted.count >= 2 && expiries == ["2023-11-21T22:13:20.000Z"])
    }

    @Test func withTheTimerOffNothingExpires() async throws {
        let rig = try await Rig.make()
        rig.ana.store.setTimer("acc-bob", seconds: 86_400)
        rig.ana.store.setTimer("acc-bob", seconds: 0)
        await rig.ana.store.settle()
        rig.ana.store.send(.text("hi"), in: "acc-bob")
        await rig.ana.store.settle()
        let text = try #require(try rig.ana.repo.messages(chatID: "acc-bob", before: nil, limit: 10).last { $0.senderAccount == "acc-ana" })
        #expect(text.expiresAt == nil && ContentEnvelope.decode(text.envelope).ttl == nil)
    }

    @Test func aNumberThatIsNotAChoiceIsRefused() async throws {
        let rig = try await Rig.make()
        rig.ana.store.setTimer("acc-bob", seconds: 60)
        #expect(rig.ana.store.timerSeconds("acc-bob") == 0)
    }

    @Test func settingTheTimerWritesAnOwnSystemLine() async throws {
        let rig = try await Rig.make()
        rig.ana.store.setTimer("acc-bob", seconds: 86_400)
        await rig.ana.store.settle()
        let last = try #require(rig.ana.store.chat(id: "acc-bob")?.messages.last)
        #expect(last.content == .system(.timer(seconds: 86_400, name: nil)))
    }

    // MARK: timer on receive

    @Test func aTimerMessageSetsTheChatTimerAndStoresASystemLineWithoutUnread() async throws {
        let rig = try await Rig.make()
        rig.ana.store.setTimer("acc-bob", seconds: 604_800)
        await rig.ana.store.settle()
        await rig.bobSync()
        #expect(rig.bob.store.timerSeconds("acc-ana") == 604_800)
        let chat = try #require(rig.bob.store.chat(id: "acc-ana"))
        guard case .system(.timer(let seconds, let name?))? = chat.messages.last?.content else {
            Issue.record("no timer line: \(String(describing: chat.messages.last?.content))"); return
        }
        #expect(seconds == 604_800 && !name.isEmpty)
        #expect(chat.unreadCount == 0)
        // Turning it off.
        rig.ana.store.setTimer("acc-bob", seconds: 0)
        await rig.ana.store.settle()
        await rig.bobSync()
        #expect(rig.bob.store.timerSeconds("acc-ana") == 0)
        #expect(rig.bob.store.chat(id: "acc-ana")?.messages.count == 2)
    }

    @Test func aBadTimerStoresNothingAndKeepsTheTimer() async throws {
        let rig = try await Rig.make()
        rig.ana.store.setTimer("acc-bob", seconds: 86_400)
        await rig.ana.store.settle()
        await rig.bobSync()
        try await rig.ana.sendRaw(ContentEnvelope(id: "bad-timer", at: rig.ms, body: .unsupported(kind: "timer")), to: "acc-bob")
        await rig.bobSync()
        #expect(rig.bob.store.timerSeconds("acc-ana") == 86_400)
        #expect(try rig.bobMessages().filter { $0.id == "bad-timer" }.isEmpty)
    }

    // MARK: arrival, expiry

    @Test func aMessageWithTtlIsStoredWithItsExpiry() async throws {
        let rig = try await Rig.make()
        try await rig.ana.sendRaw(rig.envelope("m1", .text("hello", replyTo: nil), ttl: 3_600), to: "acc-bob")
        await rig.bobSync()
        let row = try #require(try rig.bob.repo.message(id: "m1"))
        #expect(row.expiresAt == Date(timeIntervalSince1970: testEpochDate.timeIntervalSince1970 + 3_600))
        #expect(rig.bob.store.chat(id: "acc-ana")?.messages.last?.id == "m1")
    }

    @Test func aMessageAlreadyExpiredOnArrivalIsNeverStoredOrCounted() async throws {
        let rig = try await Rig.make()
        // Sent an hour ago with a 60 second life, read by a device that was offline.
        try await rig.ana.sendRaw(rig.envelope("old", .text("secret", replyTo: nil), ttl: 60, at: rig.ms - 3_600_000), to: "acc-bob")
        try await rig.ana.sendRaw(rig.envelope("edge", .text("edge", replyTo: nil), ttl: 3_600, at: rig.ms - 3_600_000), to: "acc-bob")
        try await rig.ana.sendRaw(rig.envelope("live", .text("fresh", replyTo: nil), ttl: 3_600), to: "acc-bob")
        await rig.bobSync()
        #expect(try rig.bobMessages().map(\.id) == ["live"])
        #expect(rig.bob.store.chat(id: "acc-ana")?.unreadCount == 1)
    }

    @Test func anExpiredAttachmentMessageGetsNoAttachmentRow() async throws {
        let rig = try await Rig.make()
        let att = Att(id: "att-old", key: Data(count: 32), dg: Data(count: 32), sz: 10, csz: 26)
        try await rig.ana.sendRaw(rig.envelope("photo-old", .photo(att, w: 1, h: 1, thumb: nil, replyTo: nil), ttl: 60, at: rig.ms - 3_600_000), to: "acc-bob")
        let live = Att(id: "att-live", key: Data(count: 32), dg: Data(count: 32), sz: 10, csz: 26)
        try await rig.ana.sendRaw(rig.envelope("photo-live", .photo(live, w: 1, h: 1, thumb: nil, replyTo: nil), ttl: 3_600), to: "acc-bob")
        await rig.bobSync()
        #expect(try rig.bob.repo.attachment(id: "att-old") == nil)
        #expect(try rig.bob.repo.message(id: "photo-old") == nil)
        #expect(try rig.bob.repo.attachment(id: "att-live") != nil)
    }

    @Test func sweepRemovesTheRowTheFileAndTheSearchEntryAndReturnsTheIDs() async throws {
        let rig = try await Rig.make()
        let att = Att(id: "att1", key: Data(count: 32), dg: Data(count: 32), sz: 10, csz: 26)
        try await rig.ana.sendRaw(rig.envelope("t1", .text("pineapple soon gone", replyTo: nil), ttl: 100), to: "acc-bob")
        try await rig.ana.sendRaw(rig.envelope("p1", .photo(att, w: 1, h: 1, thumb: nil, replyTo: nil), ttl: 200), to: "acc-bob")
        try await rig.ana.sendRaw(rig.envelope("keep", .text("pineapple stays", replyTo: nil), ttl: 100_000), to: "acc-bob")
        await rig.bobSync()
        let file = rig.bob.device.deps.mediaDirectory.appendingPathComponent("att1.jpg")
        try Data([1, 2, 3]).write(to: file)
        try rig.bob.repo.setAttachmentState(id: "att1", state: "local", downloaded: 26, localPath: file.path)
        #expect(rig.bob.store.searchMessages("pineapple", in: "acc-ana", limit: 10).count == 2)

        // A second before the first expiry nothing goes; at the second it goes, not before.
        rig.clock.advance(99)
        #expect(rig.bob.store.sweepExpired(now: rig.clock.now()).isEmpty)
        rig.clock.advance(1)
        #expect(rig.bob.store.sweepExpired(now: rig.clock.now()) == ["t1"])
        rig.clock.advance(100)
        #expect(rig.bob.store.sweepExpired(now: rig.clock.now()) == ["p1"])

        #expect(try rig.bob.repo.message(id: "t1") == nil && rig.bob.repo.message(id: "p1") == nil)
        #expect(try rig.bob.repo.attachment(id: "att1") == nil)
        #expect(!FileManager.default.fileExists(atPath: file.path))
        #expect(rig.bob.store.searchMessages("pineapple", in: "acc-ana", limit: 10).map(\.messageID) == ["keep"])
        #expect(rig.bob.store.chat(id: "acc-ana")?.messages.map(\.id) == ["keep"])
    }

    @Test func theSenderSideExpiresAtTheSameInstant() async throws {
        let rig = try await Rig.make()
        rig.ana.store.setTimer("acc-bob", seconds: 86_400)
        await rig.ana.store.settle()
        rig.ana.store.send(.text("bye"), in: "acc-bob")
        await rig.ana.store.settle()
        await rig.bobSync()
        let mine = try #require(try rig.ana.repo.messages(chatID: "acc-bob", before: nil, limit: 10).last { $0.senderAccount == "acc-ana" })
        let theirs = try #require(try rig.bobMessages().last { !$0.id.hasPrefix("sys") })
        #expect(mine.expiresAt != nil && mine.expiresAt == theirs.expiresAt)
    }

    @Test func aQueuedMessageWhoseLifeHasPassedIsNeverSent() async throws {
        let rig = try await Rig.make()
        try rig.ana.outbox.enqueue(rig.envelope("late", .text("too late", replyTo: nil), ttl: 60), chatID: "acc-bob")
        rig.clock.advance(61)
        try await rig.ana.outbox.flush()
        #expect(await rig.server.conversationIDs().isEmpty)
        #expect(try rig.ana.repo.message(id: "late")?.status == "failed")
        #expect(try rig.ana.repo.dueOutbox(at: rig.clock.now().addingTimeInterval(10_000)).isEmpty)
    }

    // MARK: profile

    @Test func aProfileIsKeptOnlyWhenItsVersionIsHigher() async throws {
        let rig = try await Rig.make()
        try await rig.ana.sendRaw(rig.envelope("p1", .profile(name: "Ana", photo: jpeg, ver: 2)), to: "acc-bob")
        await rig.bobSync()
        #expect(rig.bob.store.profile(for: "acc-ana") == StoredProfile(accountID: "acc-ana", name: "Ana", photo: jpeg, ver: 2))
        // Same version, lower version: ignored whole.
        try await rig.ana.sendRaw(rig.envelope("p2", .profile(name: "Same", photo: nil, ver: 2)), to: "acc-bob")
        try await rig.ana.sendRaw(rig.envelope("p3", .profile(name: "Older", photo: nil, ver: 1)), to: "acc-bob")
        await rig.bobSync()
        #expect(rig.bob.store.profile(for: "acc-ana")?.name == "Ana")
        #expect(rig.bob.store.profile(for: "acc-ana")?.photo == jpeg)
        try await rig.ana.sendRaw(rig.envelope("p4", .profile(name: "Ana B", photo: nil, ver: 3)), to: "acc-bob")
        await rig.bobSync()
        #expect(rig.bob.store.profile(for: "acc-ana") == StoredProfile(accountID: "acc-ana", name: "Ana B", photo: nil, ver: 3))
    }

    @Test func aProfileWithABadPhotoOrNameChangesNothingAndShowsNoRow() async throws {
        let rig = try await Rig.make()
        try await rig.ana.sendRaw(rig.envelope("p1", .profile(name: "Ana", photo: nil, ver: 1)), to: "acc-bob")
        await rig.bobSync()
        let tooBig = Data([0xFF, 0xD8]) + Data(count: 25 * 1024)
        try await rig.ana.sendRaw(rig.envelope("p2", .profile(name: "Big", photo: tooBig, ver: 9)), to: "acc-bob")
        try await rig.ana.sendRaw(rig.envelope("p3", .profile(name: "Ev\u{202E}il", photo: nil, ver: 10)), to: "acc-bob")
        try await rig.ana.sendRaw(rig.envelope("p4", .unsupported(kind: "profile")), to: "acc-bob")
        await rig.bobSync()
        #expect(rig.bob.store.profile(for: "acc-ana") == StoredProfile(accountID: "acc-ana", name: "Ana", photo: nil, ver: 1))
        #expect(try rig.bobMessages().isEmpty)
    }

    // MARK: signals that arrive as stored messages

    @Test func strayTypingPresenceAndBrokenSignalsMakeNoRowNoUnreadNoNotice() async throws {
        let rig = try await Rig.make()
        try await rig.ana.sendRaw(rig.envelope("t1", .typing(on: true)), to: "acc-bob")
        try await rig.ana.sendRaw(rig.envelope("t2", .presence(.online)), to: "acc-bob")
        try await rig.ana.sendRaw(rig.envelope("t3", .unsupported(kind: "typing")), to: "acc-bob")
        try await rig.ana.sendRaw(rig.envelope("t4", .unsupported(kind: "presence")), to: "acc-bob")
        await rig.bobSync()
        #expect(try rig.bobMessages().isEmpty)
        let chat = rig.bob.store.chat(id: "acc-ana")
        #expect(chat == nil || (chat?.messages.isEmpty == true && chat?.unreadCount == 0))
    }

    @Test func anUnknownKindIsStillShownAsUnsupported() async throws {
        let rig = try await Rig.make()
        try await rig.ana.sendRaw(rig.envelope("u1", .unsupported(kind: "sticker")), to: "acc-bob")
        await rig.bobSync()
        #expect(try rig.bobMessages().map(\.id) == ["u1"])
    }

    // MARK: caption and preview are stored and searchable

    @Test func captionAndPreviewAreStoredAndTheCaptionIsSearchable() async throws {
        let rig = try await Rig.make()
        let att = Att(id: "att9", key: Data(count: 32), dg: Data(count: 32), sz: 10, csz: 26)
        var photo = rig.envelope("c1", .photo(att, w: 1, h: 1, thumb: nil, replyTo: nil))
        photo.caption = "Sunset at the lake"
        try await rig.ana.sendRaw(photo, to: "acc-bob")
        let link = "https://example.com/a"
        var text = rig.envelope("c2", .text("look \(link)", replyTo: nil))
        text.preview = LinkPreview(url: link, title: "Example", desc: "A page", img: jpeg)
        try await rig.ana.sendRaw(text, to: "acc-bob")
        await rig.bobSync()
        let c1 = try #require(try rig.bob.repo.message(id: "c1"))
        #expect(c1.caption == "Sunset at the lake")
        #expect(rig.bob.store.searchMessages("lake", in: "acc-ana", limit: 5).map(\.messageID) == ["c1"])
        let c2 = try #require(try rig.bob.repo.message(id: "c2"))
        let stored = try #require(c2.linkPreview)
        #expect(stored.contains("Example") && stored.contains(link))
    }
}

@Suite("TestPeopleStoreFixes") @MainActor struct TestPeopleStoreFixes {
    @Test func aFutureSendTimeDoesNotExtendALife() async throws {
        let rig = try await Rig.make()
        try await rig.ana.sendRaw(rig.envelope("f1", .text("from the future", replyTo: nil), ttl: 60, at: rig.ms + 3_600_000), to: "acc-bob")
        try await rig.ana.sendRaw(rig.envelope("p1", .text("from the past", replyTo: nil), ttl: 3_600, at: rig.ms - 1_800_000), to: "acc-bob")
        await rig.bobSync()
        #expect(try rig.bob.repo.message(id: "f1")?.expiresAt == testEpochDate.addingTimeInterval(60))
        #expect(try rig.bob.repo.message(id: "p1")?.expiresAt == testEpochDate.addingTimeInterval(1_800))
    }

    @Test func anOlderTimerChangeIsIgnored() async throws {
        let rig = try await Rig.make()
        try await rig.ana.sendRaw(rig.envelope("t-new", .timer(seconds: 86_400)), to: "acc-bob")
        try await rig.ana.sendRaw(rig.envelope("t-old", .timer(seconds: 604_800), at: rig.ms - 10_000), to: "acc-bob")
        await rig.bobSync()
        #expect(rig.bob.store.timerSeconds("acc-ana") == 86_400)
        #expect(try rig.bob.repo.message(id: "sys-timer-t-old") == nil)
        // A newer one still applies.
        try await rig.ana.sendRaw(rig.envelope("t-newer", .timer(seconds: 0), at: rig.ms + 1_000), to: "acc-bob")
        await rig.bobSync()
        #expect(rig.bob.store.timerSeconds("acc-ana") == 0)
    }

    @Test func anExpiredUnreadMessageNoLongerCountsBeforeOrAfterTheSweep() async throws {
        let rig = try await Rig.make()
        try await rig.ana.sendRaw(rig.envelope("a", .text("a", replyTo: nil), ttl: 100), to: "acc-bob")
        try await rig.ana.sendRaw(rig.envelope("b", .text("b", replyTo: nil), ttl: 100_000), to: "acc-bob")
        await rig.bobSync()
        #expect(rig.bob.store.chat(id: "acc-ana")?.unreadCount == 2)
        rig.clock.advance(100)
        rig.bob.store.refresh()
        #expect(rig.bob.store.chat(id: "acc-ana")?.unreadCount == 1)   // before the sweep
        #expect(rig.bob.store.sweepExpired(now: rig.clock.now()) == ["a"])
        #expect(rig.bob.store.chat(id: "acc-ana")?.unreadCount == 1)   // after: not subtracted twice
        #expect(try rig.bob.repo.chat(id: "acc-ana")?.unread == 1)
    }

    @Test func aReadMessageThatExpiresDoesNotLowerUnread() async throws {
        let rig = try await Rig.make()
        try await rig.ana.sendRaw(rig.envelope("a", .text("a", replyTo: nil), ttl: 100), to: "acc-bob")
        await rig.bobSync()
        rig.bob.store.markRead(chatID: "acc-ana")
        try await rig.ana.sendRaw(rig.envelope("b", .text("b", replyTo: nil), ttl: 100_000), to: "acc-bob")
        await rig.bobSync()
        rig.clock.advance(100)
        rig.bob.store.sweepExpired(now: rig.clock.now())
        #expect(rig.bob.store.chat(id: "acc-ana")?.unreadCount == 1)
    }
}

@Suite("TestPeopleStoreRound3") @MainActor struct TestPeopleStoreRound3 {
    @Test func aFarFutureTimerDoesNotFreezeTheTimer() async throws {
        let rig = try await Rig.make()
        try await rig.ana.sendRaw(rig.envelope("far", .timer(seconds: 604_800), at: rig.ms + 10 * 365 * 86_400_000), to: "acc-bob")
        await rig.bobSync()
        #expect(rig.bob.store.timerSeconds("acc-ana") == 604_800)
        let line = try #require(try rig.bob.repo.message(id: "sys-timer-far"))
        #expect(line.sentAt == testEpochDate.addingTimeInterval(300))
        rig.clock.advance(6 * 60)
        try await rig.ana.sendRaw(rig.envelope("next", .timer(seconds: 86_400)), to: "acc-bob")
        await rig.bobSync()
        #expect(rig.bob.store.timerSeconds("acc-ana") == 86_400)
    }

    @Test func aLocalSetIsNotOverriddenByAnOlderIncomingTimer() async throws {
        let rig = try await Rig.make()
        // Ana sent her change a moment ago; bob set his own afterwards, before he read hers.
        let old = rig.envelope("old", .timer(seconds: 604_800), at: rig.ms - 10_000)
        try await rig.ana.sendRaw(old, to: "acc-bob")
        rig.bob.store.setTimer("acc-ana", seconds: 86_400)
        #expect(rig.bob.store.timerSeconds("acc-ana") == 86_400)
        await rig.bobSync()
        #expect(rig.bob.store.timerSeconds("acc-ana") == 86_400)
        #expect(try rig.bob.repo.message(id: "sys-timer-old") == nil)
    }

    @Test func theWireExpiryStaysAMinuteInsideTheServerLimit() async throws {
        let rig = try await Rig.make()
        try await rig.ana.sendRaw(rig.envelope("max", .text("x", replyTo: nil), ttl: 31_622_400), to: "acc-bob")
        try await rig.ana.sendRaw(rig.envelope("one", .text("y", replyTo: nil), ttl: 86_400), to: "acc-bob")
        let conversation = try #require(await rig.server.conversationIDs().first)
        let posted = await rig.server.storedMessages(in: conversation).compactMap(\.expiresAt)
        #expect(posted.count == 2)
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let dates = posted.compactMap { formatter.date(from: $0) }
        #expect(dates[0] == testEpochDate.addingTimeInterval(31_622_400 - 60))
        #expect(dates[1] == testEpochDate.addingTimeInterval(86_400))
    }
}

@Suite("TestPeopleStoreRound4") @MainActor struct TestPeopleStoreRound4 {
    @Test func aRepeatedTimerAddsNoSecondLine() async throws {
        let rig = try await Rig.make()
        try await rig.ana.sendRaw(rig.envelope("t1", .timer(seconds: 86_400)), to: "acc-bob")
        rig.clock.advance(5)
        try await rig.ana.sendRaw(rig.envelope("t2", .timer(seconds: 86_400)), to: "acc-bob")
        await rig.bobSync()
        let lines = try rig.bobMessages().filter { $0.id.hasPrefix("sys-timer-") }
        #expect(lines.map(\.id) == ["sys-timer-t1"])
        #expect(rig.bob.store.timerSeconds("acc-ana") == 86_400)
        // timer-at advanced: a change older than the repeat is ignored, a newer one applies.
        try await rig.ana.sendRaw(rig.envelope("t3", .timer(seconds: 0), at: rig.ms - 2_000), to: "acc-bob")
        await rig.bobSync()
        #expect(rig.bob.store.timerSeconds("acc-ana") == 86_400)
        rig.clock.advance(5)
        try await rig.ana.sendRaw(rig.envelope("t4", .timer(seconds: 0)), to: "acc-bob")
        await rig.bobSync()
        #expect(rig.bob.store.timerSeconds("acc-ana") == 0)
    }
}

@Suite("TestPeopleStoreTimerTie") @MainActor struct TestPeopleStoreTimerTie {
    private func stored(_ rig: Rig) throws -> (at: Int64, id: String) {
        let at = try #require(try rig.bob.repo.get("timer-at:acc-ana").flatMap { Int64(String(decoding: $0, as: UTF8.self)) })
        let id = try #require(try rig.bob.repo.get("timer-id:acc-ana").map { String(decoding: $0, as: UTF8.self) })
        return (at, id)
    }

    /// Both sides set a different value in the same millisecond: the greater envelope id wins on both.
    @Test func theGreaterIDWinsAtTheSameMillisecond() async throws {
        let rig = try await Rig.make()
        rig.bob.store.setTimer("acc-ana", seconds: 86_400)
        let local = try stored(rig)
        try await rig.ana.sendRaw(rig.envelope(local.id + "z", .timer(seconds: 604_800), at: local.at), to: "acc-bob")
        await rig.bobSync()
        #expect(rig.bob.store.timerSeconds("acc-ana") == 604_800)
        #expect(try stored(rig).id == local.id + "z")
    }

    @Test func aSmallerIDLosesAtTheSameMillisecond() async throws {
        let rig = try await Rig.make()
        rig.bob.store.setTimer("acc-ana", seconds: 86_400)
        let local = try stored(rig)
        try await rig.ana.sendRaw(rig.envelope("-", .timer(seconds: 604_800), at: local.at), to: "acc-bob")
        await rig.bobSync()
        #expect(rig.bob.store.timerSeconds("acc-ana") == 86_400)
        #expect(try rig.bob.repo.message(id: "sys-timer--") == nil)
        #expect(try stored(rig).id == local.id)
    }

    @Test func aControlCanBeQueuedUnderAGivenID() async throws {
        let rig = try await Rig.make()
        try rig.ana.outbox.enqueueControl(.timer(seconds: 86_400), chatID: "acc-bob", id: "fixed-id")
        #expect(try rig.ana.repo.dueOutbox(at: .distantFuture).contains { $0.messageID == "fixed-id" })
    }
}
