import AppCore
import Foundation
import Testing
@testable import Messaging

@MainActor
private final class FakeSender: SignalSending {
    struct Sent: Equatable { let chatID: String; let body: ContentEnvelope.Body }
    var sent: [Sent] = []
    func send(chatID: String, body: ContentEnvelope.Body) async { sent.append(Sent(chatID: chatID, body: body)) }
}

@MainActor
private final class Rig {
    let sender = FakeSender()
    var nowMs: Int64 = 1_000_000
    var typingOn = true
    var presenceOn = true
    var blocked: Set<String> = []
    var targets = ["acc-bob", "acc-cy"]
    var seen: [String: Int64] = [:]
    var changes = 0
    var signals: PeopleSignals!

    init() {
        signals = make()
    }

    func make() -> PeopleSignals {
        let made = PeopleSignals(
            sender: sender, now: { [unowned self] in nowMs },
            typingEnabled: { [unowned self] in typingOn }, presenceEnabled: { [unowned self] in presenceOn },
            isBlocked: { [unowned self] in blocked.contains($0) }, presenceTargets: { [unowned self] in targets },
            loadSeen: { [unowned self] in seen[$0] }, saveSeen: { [unowned self] in seen[$0] = $1 })
        made.onChange = { [unowned self] in changes += 1 }
        return made
    }

    func advance(_ ms: Int64) { nowMs += ms }
    func sentBodies() async -> [FakeSender.Sent] { await signals.flush(); return sender.sent }
}

@MainActor @Suite("TestPeopleSignals") struct TestPeopleSignals {
    @Test func typingIsSentOnStartRepeatedEvery8sAndEndedOnSend() async {
        let rig = Rig()
        rig.signals.composerChanged(chatID: "acc-bob", isEmpty: false)
        rig.advance(1_000)
        rig.signals.composerChanged(chatID: "acc-bob", isEmpty: false)
        rig.advance(6_999)
        rig.signals.composerChanged(chatID: "acc-bob", isEmpty: false)   // 7,999 ms after the first signal
        var sent = await rig.sentBodies()
        #expect(sent == [.init(chatID: "acc-bob", body: .typing(on: true))])
        rig.advance(1)
        rig.signals.composerChanged(chatID: "acc-bob", isEmpty: false)
        rig.signals.composerSent(chatID: "acc-bob")
        sent = await rig.sentBodies()
        #expect(sent.map(\.body) == [.typing(on: true), .typing(on: true), .typing(on: false)])
    }

    @Test func typingGoesOffAfterFiveIdleSecondsOnTick() async {
        let rig = Rig()
        rig.signals.composerChanged(chatID: "acc-bob", isEmpty: false)
        rig.advance(4_999)
        rig.signals.tick()
        rig.advance(1)
        rig.signals.tick()
        rig.signals.tick()
        let sent = await rig.sentBodies()
        #expect(sent.map(\.body) == [.typing(on: true), .typing(on: false)])
    }

    @Test func eachChatKeepsItsOwnTypingState() async {
        let rig = Rig()
        rig.signals.composerChanged(chatID: "acc-bob", isEmpty: false)
        rig.signals.composerChanged(chatID: "acc-cy", isEmpty: false)
        rig.signals.composerChanged(chatID: "acc-bob", isEmpty: true)
        let sent = await rig.sentBodies()
        #expect(sent == [.init(chatID: "acc-bob", body: .typing(on: true)), .init(chatID: "acc-cy", body: .typing(on: true)),
                         .init(chatID: "acc-bob", body: .typing(on: false))])
    }

    @Test func nothingIsSentWhenTheTypingSwitchIsOffOrTheContactIsBlocked() async {
        let rig = Rig()
        rig.typingOn = false
        rig.signals.composerChanged(chatID: "acc-bob", isEmpty: false)
        rig.signals.composerSent(chatID: "acc-bob")
        rig.typingOn = true
        rig.blocked = ["acc-cy"]
        rig.signals.composerChanged(chatID: "acc-cy", isEmpty: false)
        rig.signals.composerSent(chatID: "acc-cy")
        let sent = await rig.sentBodies()
        #expect(sent.isEmpty)
    }

    @Test func aSignalQueuedBeforeABlockOrSwitchOffIsNotSent() async {
        let rig = Rig()
        rig.signals.composerChanged(chatID: "acc-bob", isEmpty: false)
        rig.blocked = ["acc-bob"]   // before the queue ran
        let sent = await rig.sentBodies()
        #expect(sent.isEmpty)
    }

    @Test func presenceOnlineOnForegroundThenEvery60sAndAwayOnBackground() async {
        let rig = Rig()
        rig.signals.foreground()
        rig.advance(59_999)
        rig.signals.tick()
        rig.advance(1)
        rig.signals.tick()
        rig.signals.background()
        let sent = await rig.sentBodies()
        let online = FakeSender.Sent.self
        #expect(sent == [
            online.init(chatID: "acc-bob", body: .presence(.online)), online.init(chatID: "acc-cy", body: .presence(.online)),
            online.init(chatID: "acc-bob", body: .presence(.online)), online.init(chatID: "acc-cy", body: .presence(.online)),
            online.init(chatID: "acc-bob", body: .presence(.away)), online.init(chatID: "acc-cy", body: .presence(.away)),
        ])
        // Nothing repeats in the background.
        rig.advance(120_000)
        rig.signals.tick()
        #expect(await rig.sentBodies().count == 6)
    }

    @Test func presenceSkipsBlockedContactsAndIsSilentWhenTheSwitchIsOff() async {
        let rig = Rig()
        rig.blocked = ["acc-cy"]
        rig.signals.foreground()
        var sent = await rig.sentBodies()
        #expect(sent.map(\.chatID) == ["acc-bob"])
        rig.sender.sent = []
        rig.presenceOn = false
        rig.signals.background()
        rig.advance(70_000)
        rig.signals.foreground()
        sent = await rig.sentBodies()
        #expect(sent.isEmpty)
    }

    @Test func incomingTypingShowsFor10sAndOffClearsIt() async {
        let rig = Rig()
        rig.signals.ingest(.init(chatID: "acc-bob", accountID: "acc-bob", body: .typing(on: true)))
        #expect(rig.signals.isTyping(chatID: "acc-bob"))
        rig.advance(9_999)
        #expect(rig.signals.isTyping(chatID: "acc-bob"))
        rig.advance(1)
        #expect(!rig.signals.isTyping(chatID: "acc-bob"))
        rig.signals.ingest(.init(chatID: "acc-bob", accountID: "acc-bob", body: .typing(on: true)))
        rig.signals.ingest(.init(chatID: "acc-bob", accountID: "acc-bob", body: .typing(on: false)))
        #expect(!rig.signals.isTyping(chatID: "acc-bob"))
    }

    @Test func aNewIncomingMessageClearsTyping() async {
        let rig = Rig()
        rig.signals.incomingMessage(chatID: "acc-bob", id: "m1")   // first look: nothing to clear, nothing witnessed
        #expect(rig.seen["acc-bob"] == nil)
        rig.signals.ingest(.init(chatID: "acc-bob", accountID: "acc-bob", body: .typing(on: true)))
        rig.signals.incomingMessage(chatID: "acc-bob", id: "m1")   // same message
        #expect(rig.signals.isTyping(chatID: "acc-bob"))
        rig.advance(500)
        rig.signals.incomingMessage(chatID: "acc-bob", id: "m2")
        #expect(!rig.signals.isTyping(chatID: "acc-bob"))
        #expect(rig.seen["acc-bob"] == rig.nowMs)
    }

    @Test func nothingIsShownWhenTheSwitchIsOffOrTheContactIsBlocked() async {
        let rig = Rig()
        rig.signals.ingest(.init(chatID: "acc-bob", accountID: "acc-bob", body: .typing(on: true)))
        rig.signals.ingest(.init(chatID: "acc-bob", accountID: "acc-bob", body: .presence(.online)))
        #expect(rig.signals.isTyping(chatID: "acc-bob") && rig.signals.presence(chatID: "acc-bob") == .online)
        rig.typingOn = false
        #expect(!rig.signals.isTyping(chatID: "acc-bob"))
        rig.presenceOn = false
        #expect(rig.signals.presence(chatID: "acc-bob") == PresenceLine.Line.none)
        rig.typingOn = true
        rig.presenceOn = true
        rig.blocked = ["acc-bob"]
        #expect(!rig.signals.isTyping(chatID: "acc-bob"))
        #expect(rig.signals.presence(chatID: "acc-bob") == PresenceLine.Line.none)
        // A signal that arrives for a blocked contact is not even remembered.
        rig.signals.ingest(.init(chatID: "acc-bob", accountID: "acc-bob", body: .presence(.online)))
        rig.blocked = []
        rig.advance(100_000)
        #expect(rig.signals.presence(chatID: "acc-bob") == .lastSeen(1_000_000))
    }

    @Test func signalsAreIgnoredWhileTheirSwitchIsOff() async {
        let rig = Rig()
        rig.presenceOn = false
        rig.typingOn = false
        rig.signals.ingest(.init(chatID: "acc-bob", accountID: "acc-bob", body: .typing(on: true)))
        rig.signals.ingest(.init(chatID: "acc-bob", accountID: "acc-bob", body: .presence(.online)))
        rig.presenceOn = true
        rig.typingOn = true
        #expect(!rig.signals.isTyping(chatID: "acc-bob"))
        #expect(rig.signals.presence(chatID: "acc-bob") == PresenceLine.Line.none)
        #expect(rig.seen.isEmpty)
    }

    @Test func presenceIsOnlineFor90sThenLastSeenAndAwayEndsItAtOnce() async {
        let rig = Rig()
        rig.signals.ingest(.init(chatID: "acc-bob", accountID: "acc-bob", body: .presence(.online)))
        rig.advance(89_999)
        #expect(rig.signals.presence(chatID: "acc-bob") == .online)
        rig.advance(1)
        #expect(rig.signals.presence(chatID: "acc-bob") == .lastSeen(1_000_000))
        rig.signals.ingest(.init(chatID: "acc-bob", accountID: "acc-bob", body: .presence(.online)))
        rig.advance(10)
        rig.signals.ingest(.init(chatID: "acc-bob", accountID: "acc-bob", body: .presence(.away)))
        #expect(rig.signals.presence(chatID: "acc-bob") == .lastSeen(rig.nowMs))
    }

    @Test func lastWitnessedSurvivesARestartThroughTheStore() async {
        let rig = Rig()
        rig.signals.ingest(.init(chatID: "acc-bob", accountID: "acc-bob", body: .presence(.online)))
        let again = rig.make()   // a new launch with the same store
        rig.advance(500_000)
        #expect(again.presence(chatID: "acc-bob") == .lastSeen(1_000_000))
        #expect(again.presence(chatID: "acc-nobody") == PresenceLine.Line.none)
    }

    @Test func theViewIsToldWhenWhatIsShownChangesByTheClock() async {
        let rig = Rig()
        rig.signals.ingest(.init(chatID: "acc-bob", accountID: "acc-bob", body: .typing(on: true)))
        let afterIngest = rig.changes
        rig.advance(5_000)
        rig.signals.tick()
        #expect(rig.changes == afterIngest)   // still typing: nothing new to show
        rig.advance(5_000)
        rig.signals.tick()
        #expect(rig.changes == afterIngest + 1)
    }
}
