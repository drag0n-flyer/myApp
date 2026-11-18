import Testing
@testable import AppCore

@Suite struct NotificationMessageKeyTests {
    @Test func aNotificationOfASweptMessageMatches() {
        #expect(NotificationMessageKey.isSwept(userInfo: ["messageID": "m1", "d": "x"], swept: ["m1", "m2"]))
    }

    @Test func otherNotificationsStay() {
        #expect(!NotificationMessageKey.isSwept(userInfo: ["messageID": "m3"], swept: ["m1"]))
        #expect(!NotificationMessageKey.isSwept(userInfo: ["chatID": "m1"], swept: ["m1"]))
        #expect(!NotificationMessageKey.isSwept(userInfo: ["messageID": 5], swept: ["5"]))
    }
}
