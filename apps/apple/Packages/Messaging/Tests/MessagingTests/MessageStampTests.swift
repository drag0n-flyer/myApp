import Foundation
import Testing
@testable import Messaging

@Suite struct MessageStampTests {
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    @Test func aBurstKeepsItsOrder() {
        var last = Date.distantPast
        var stamps: [Date] = []
        for _ in 0..<5 {
            last = LiveChatStore.nextStamp(clock: t0, last: last)
            stamps.append(last)
        }
        #expect(stamps == stamps.sorted())
        #expect(Set(stamps).count == 5)
        #expect(stamps[0] == t0)
    }

    @Test func aClockThatStepsBackIsFollowed() {
        let last = LiveChatStore.nextStamp(clock: t0, last: .distantPast)
        let back = t0.addingTimeInterval(-3600)
        #expect(LiveChatStore.nextStamp(clock: back, last: last) == back)
    }

    @Test func manyStampsNeverRunMoreThanASecondAhead() {
        var last = Date.distantPast
        for _ in 0..<10_000 { last = LiveChatStore.nextStamp(clock: t0, last: last) }
        #expect(last.timeIntervalSince(t0) <= 1.001)
        let later = t0.addingTimeInterval(5)
        #expect(LiveChatStore.nextStamp(clock: later, last: last) == later)
    }
}
