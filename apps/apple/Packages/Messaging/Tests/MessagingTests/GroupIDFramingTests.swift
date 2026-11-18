import Foundation
import Testing
@testable import Messaging

@Suite("GroupIDFraming") struct GroupIDFramingTests {
    @Test func theGroupIDIsReadFromThePrivateMessageFraming() {
        let one = Data([0, 1, 0, 2, 3, 9, 8, 7, 0xAA])
        #expect(NotificationProcessor.groupID(in: one) == Data([9, 8, 7]))
        let two = Data([0, 1, 0, 2, 0x40, 2, 5, 6, 0xFF])
        #expect(NotificationProcessor.groupID(in: two) == Data([5, 6]))
    }

    @Test func malformedOrHostilePayloadsGiveNothingAndNeverCrash() {
        let cases: [Data] = [
            Data(), Data([0, 1, 0, 2]), Data([0, 1, 0, 2, 5, 1]), Data([0, 1, 0, 2, 0x40]), Data([0, 1, 0, 2, 0xC0, 1, 2, 3]),
            Data([0, 1, 0, 2, 0, 1]), Data([0, 1, 0, 3, 1, 1]), Data([1, 2, 3]),
            Data(#"{"fake":"app","group":"zz"}"#.utf8), Data(#"{"fake":"app","group":"abc"}"#.utf8),
            Data(repeating: 0xFF, count: 10_000),
        ]
        for payload in cases { #expect(NotificationProcessor.groupID(in: payload) == nil) }
    }

    @Test func theFakeEnginesGroupIsReadToo() {
        #expect(NotificationProcessor.groupID(in: Data(#"{"fake":"app","group":"0a0b"}"#.utf8)) == Data([0x0a, 0x0b]))
    }
}
