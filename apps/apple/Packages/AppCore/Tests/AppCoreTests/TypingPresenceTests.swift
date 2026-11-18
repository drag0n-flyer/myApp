import Testing
@testable import AppCore

private func run(_ events: [(TypingSender.Event, Int64)], from state: TypingSender.State = .idle) -> (TypingSender.State, [Bool?]) {
    var state = state
    var sends: [Bool?] = []
    for (event, now) in events {
        let out = TypingSender.step(state, event, nowMs: now)
        state = out.state
        sends.append(out.send)
    }
    return (state, sends)
}

@Test func typingStartsWithOneSignalAndStaysQuietInside8Seconds() {
    let (state, sends) = run([(.textChanged(isEmpty: false), 0), (.textChanged(isEmpty: false), 1_000), (.tick, 3_000)])
    #expect(sends == [true, nil, nil])
    #expect(state.isTyping)
}

@Test func typingRepeatsEvery8SecondsAtTheBoundary() {
    let (s1, _) = run([(.textChanged(isEmpty: false), 0), (.textChanged(isEmpty: false), 4_000)])
    #expect(TypingSender.step(s1, .textChanged(isEmpty: false), nowMs: 7_999).send == nil)
    #expect(TypingSender.step(s1, .textChanged(isEmpty: false), nowMs: 8_000).send == true)
    // The tick repeats it too, while the last keystroke is recent.
    let (s2, _) = run([(.textChanged(isEmpty: false), 0), (.textChanged(isEmpty: false), 6_000)])
    #expect(TypingSender.step(s2, .tick, nowMs: 7_999).send == nil)
    #expect(TypingSender.step(s2, .tick, nowMs: 8_000).send == true)
}

@Test func typingEndsAfter5SecondsWithoutAKeystroke() {
    let (s, _) = run([(.textChanged(isEmpty: false), 0)])
    let before = TypingSender.step(s, .tick, nowMs: 4_999)
    #expect(before.send == nil && before.state.isTyping)
    let at = TypingSender.step(s, .tick, nowMs: 5_000)
    #expect(at.send == false && !at.state.isTyping)
    // Nothing more is sent once it has ended.
    #expect(TypingSender.step(at.state, .tick, nowMs: 20_000).send == nil)
}

@Test func typingEndsOnSendAndOnEmptyText() {
    let (s, _) = run([(.textChanged(isEmpty: false), 0)])
    let sent = TypingSender.step(s, .sent, nowMs: 100)
    #expect(sent.send == false && !sent.state.isTyping)
    let emptied = TypingSender.step(s, .textChanged(isEmpty: true), nowMs: 100)
    #expect(emptied.send == false && !emptied.state.isTyping)
    // Empty text or a send with no typing in progress sends nothing.
    #expect(TypingSender.step(.idle, .textChanged(isEmpty: true), nowMs: 0).send == nil)
    #expect(TypingSender.step(.idle, .sent, nowMs: 0).send == nil)
    #expect(TypingSender.step(.idle, .tick, nowMs: 0).send == nil)
}

@Test func typingStartsAgainAfterItEnded() {
    let (s, _) = run([(.textChanged(isEmpty: false), 0), (.tick, 5_000)])
    #expect(!s.isTyping)
    #expect(TypingSender.step(s, .textChanged(isEmpty: false), nowMs: 6_000).send == true)
}

@Test func typingIsShownForTenSecondsAfterTheLastSignal() {
    #expect(!TypingShown.isTyping(lastOnMs: nil, nowMs: 1_000))
    #expect(TypingShown.isTyping(lastOnMs: 1_000, nowMs: 1_000))
    #expect(TypingShown.isTyping(lastOnMs: 1_000, nowMs: 10_999))
    #expect(!TypingShown.isTyping(lastOnMs: 1_000, nowMs: 11_000))
    // A clock that went back does not show it forever.
    #expect(!TypingShown.isTyping(lastOnMs: 50_000, nowMs: 1_000))
}

@Test func presenceIsOnlineFor90SecondsThenLastSeen() {
    #expect(PresenceLine.text(lastOnlineMs: 0, lastWitnessedMs: 0, nowMs: 89_999) == .online)
    #expect(PresenceLine.text(lastOnlineMs: 0, lastWitnessedMs: 0, nowMs: 90_000) == .lastSeen(0))
    #expect(PresenceLine.text(lastOnlineMs: 0, lastWitnessedMs: 40_000, nowMs: 120_000) == .lastSeen(40_000))
    // The newest of the two is shown.
    #expect(PresenceLine.text(lastOnlineMs: 70_000, lastWitnessedMs: 10_000, nowMs: 200_000) == .lastSeen(70_000))
}

@Test func presenceWithNothingWitnessedShowsNothing() {
    #expect(PresenceLine.text(lastOnlineMs: nil, lastWitnessedMs: nil, nowMs: 1_000) == PresenceLine.Line.none)
    #expect(PresenceLine.text(lastOnlineMs: nil, lastWitnessedMs: 500, nowMs: 1_000) == .lastSeen(500))
}

@Test func theTwoPrivacySwitchesAreOnByDefault() {
    let settings = UserSettings()
    #expect(settings.typingIndicator && settings.onlineAndLastSeen)
}

@Test func awayEndsOnlineAtOnce() {
    // `away` clears the last online time and records when it was witnessed.
    let after = PresenceLine.afterAway(nowMs: 5_000)
    #expect(after.lastOnlineMs == nil && after.lastWitnessedMs == 5_000)
    #expect(PresenceLine.text(lastOnlineMs: after.lastOnlineMs, lastWitnessedMs: after.lastWitnessedMs, nowMs: 5_001) == .lastSeen(5_000))
}
