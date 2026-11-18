import Foundation
import Testing
@testable import AppCore

// MARK: Composer edits

@Test func aChangeTheProgramSetIsNotAUserEdit() {
    var tracker = ComposerEditTracker()
    tracker.willAssign("saved draft", current: "")
    #expect(!tracker.isUserEdit(newText: "saved draft"))
    // The next change is the person's.
    #expect(tracker.isUserEdit(newText: "saved draft!"))
}

@Test func anAssignmentThatChangesNothingDoesNotHideTheNextKeystroke() {
    var tracker = ComposerEditTracker()
    tracker.willAssign("", current: "")   // no change event will come
    #expect(tracker.isUserEdit(newText: "h"))
    tracker.willAssign("same", current: "same")
    #expect(tracker.isUserEdit(newText: "same"))
}

@Test func twoProgrammaticChangesInARowAreBothSkippedInOrder() {
    var tracker = ComposerEditTracker()
    tracker.willAssign("edit text", current: "draft")
    #expect(!tracker.isUserEdit(newText: "edit text"))
    tracker.willAssign("draft", current: "edit text")
    #expect(!tracker.isUserEdit(newText: "draft"))
    #expect(tracker.isUserEdit(newText: "draf"))
}

@Test func aDifferentTextThanTheOneSetIsAUserEdit() {
    var tracker = ComposerEditTracker()
    tracker.willAssign("a", current: "")
    #expect(tracker.isUserEdit(newText: "ab"))
}

// MARK: Privacy switches

private func freshDefaults() -> (UserDefaults, String) {
    let name = "test.privacy.\(UUID().uuidString)"
    return (UserDefaults(suiteName: name)!, name)
}

@Test func privacySwitchesDefaultToOnWhenNothingIsStored() {
    let (defaults, name) = freshDefaults()
    defer { defaults.removePersistentDomain(forName: name) }
    let loaded = PrivacySwitchesStore(defaults: defaults).load()
    #expect(loaded == PrivacySwitches(typingIndicator: true, onlineAndLastSeen: true, readReceipts: true))
}

@Test func privacySwitchesOffSurviveAReload() {
    let (defaults, name) = freshDefaults()
    defer { defaults.removePersistentDomain(forName: name) }
    PrivacySwitchesStore(defaults: defaults).save(PrivacySwitches(typingIndicator: false, onlineAndLastSeen: false, readReceipts: false))
    let again = PrivacySwitchesStore(defaults: UserDefaults(suiteName: name)!).load()
    #expect(!again.typingIndicator && !again.onlineAndLastSeen && !again.readReceipts)
    PrivacySwitchesStore(defaults: defaults).save(PrivacySwitches(typingIndicator: true, onlineAndLastSeen: false, readReceipts: true))
    let third = PrivacySwitchesStore(defaults: defaults).load()
    #expect(third.typingIndicator && !third.onlineAndLastSeen && third.readReceipts)
}

@Test func aStoredValueOfTheWrongTypeCountsAsOn() {
    let (defaults, name) = freshDefaults()
    defer { defaults.removePersistentDomain(forName: name) }
    defaults.set("no", forKey: PrivacySwitchesStore.typingKey)
    #expect(PrivacySwitchesStore(defaults: defaults).load().typingIndicator)
}

// MARK: Presence fan-out

@Test func presenceGoesOnlyToChatsWithAMessageInTheLastSevenDays() {
    let now: Int64 = 100 * 86_400_000
    let week = PresenceFanOut.windowMs
    let chats = [
        PresenceFanOut.Candidate(id: "fresh", lastMessageMs: now - 1_000),
        PresenceFanOut.Candidate(id: "edge", lastMessageMs: now - week + 1),
        PresenceFanOut.Candidate(id: "old", lastMessageMs: now - week),
        PresenceFanOut.Candidate(id: "never", lastMessageMs: nil),
    ]
    #expect(PresenceFanOut.targets(chats, nowMs: now) == ["fresh", "edge"])
}

@Test func presenceGoesToAtMost30ChatsMostRecentFirst() {
    let now: Int64 = 100 * 86_400_000
    let chats = (0..<40).map { PresenceFanOut.Candidate(id: "c\($0)", lastMessageMs: now - Int64($0) * 1_000 - 1) }
    let shuffled = chats.reversed()
    let targets = PresenceFanOut.targets(Array(shuffled), nowMs: now)
    #expect(targets.count == 30)
    #expect(targets.first == "c0" && targets.last == "c29")
}
