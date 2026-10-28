import AppCore
import Foundation
import LocalStore
import Testing
@testable import Messaging

@Suite struct NotificationBudgetTests {
    @Test func deadlineIsTwentyFiveSecondsAfterArrival() {
        let now = Date(timeIntervalSince1970: 1_000)
        #expect(NotificationBudget.deadline(from: now) == Date(timeIntervalSince1970: 1_025))
    }

    @Test func decodesStandardBase64() {
        let raw = Data((0..<256).map { UInt8($0 & 0xff) })
        #expect(NotificationBudget.decodePush(raw.base64EncodedString()) == raw)
    }

    @Test func acceptsLargestBucket() {
        let raw = Data(repeating: 7, count: NotificationBudget.maxPushBytes)
        #expect(NotificationBudget.decodePush(raw.base64EncodedString()) == raw)
    }

    @Test func rejectsOversizeBadAndMissing() {
        let big = Data(repeating: 7, count: NotificationBudget.maxPushBytes + 1)
        #expect(NotificationBudget.decodePush(big.base64EncodedString()) == nil)
        #expect(NotificationBudget.decodePush(String(repeating: "A", count: 100_000)) == nil)
        #expect(NotificationBudget.decodePush("not base64!!") == nil)
        #expect(NotificationBudget.decodePush("") == nil)
        #expect(NotificationBudget.decodePush(nil) == nil)
        #expect(NotificationBudget.decodePush(42) == nil)
        // base64url characters are not standard base64
        #expect(NotificationBudget.decodePush("_-8=") == nil)
    }

    @Test func configuresSmallPageCache() throws {
        let db = try Database.inMemory()
        try NotificationBudget.configure(db)
        let size = try db.query("PRAGMA cache_size") { $0.int(0) }
        #expect(size == [-2000])
    }

    @Test func gateRunsOnceAcrossThreads() async {
        let gate = OnceGate()
        let counter = Counter()
        await withTaskGroup(of: Void.self) { group in
            for _ in 0..<50 { group.addTask { gate.run { counter.bump() } } }
        }
        #expect(counter.value == 1)
        #expect(gate.run { counter.bump() } == false)
    }
}

private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var n = 0
    func bump() { lock.withLock { n += 1 } }
    var value: Int { lock.withLock { n } }
}

@Suite struct NotificationPresentationTests {
    let strings = NotificationStrings()
    let contact = Contact(id: "a", displayName: "Ana", username: "ana")

    @Test func showBecomesContentWithSound() {
        let p = NotificationPresentation(outcome: .show(title: "Ana", body: "Lunch?", threadID: "a"), filtering: false, strings: strings)
        #expect(p == .content(title: "Ana", body: "Lunch?", threadID: "a", sound: true))
    }

    @Test func dropIsEmptyOnlyWhenFiltering() {
        #expect(NotificationPresentation(outcome: .drop, filtering: true, strings: strings) == .empty)
        #expect(NotificationPresentation(outcome: .drop, filtering: false, strings: strings) == .fallback(strings))
    }

    @Test func fallbackIsGeneric() {
        let p = NotificationPresentation(outcome: .fallback, filtering: true, strings: strings)
        #expect(p == .content(title: "[App]", body: "New message", threadID: nil, sound: true))
    }

    @Test func callPassesThrough() {
        let payload = CallSignalPayload(kind: .ring, callID: "c1", video: false)
        let p = NotificationPresentation(outcome: .incomingCall(payload, from: contact), filtering: true, strings: strings)
        #expect(p == .call(payload, from: contact))
    }
}

@Suite struct MessagingLocationsTests {
    @Test func namesMatchTheDesign() {
        let root = URL(fileURLWithPath: "/g")
        #expect(MessagingLocations.lockURL(in: root).path == "/g/messaging.lock")
        #expect(MessagingLocations.databaseURL(in: root).path == "/g/messaging.sqlite")
    }

    /// The app group comes from Info.plist (project.yml writes `group.$(APP_ID)` from AppID.xcconfig).
    @Test func appGroupFromInfoPlist() {
        #expect(MessagingLocations.appGroupInfoKey == "AppGroupID")
        #expect(MessagingLocations.appGroup(in: ["AppGroupID": "group.com.example.chat"]) == "group.com.example.chat")
        #expect(MessagingLocations.appGroup(in: ["AppGroupID": " "]) == nil)
        #expect(MessagingLocations.appGroup(in: [:]) == nil)
    }
}
