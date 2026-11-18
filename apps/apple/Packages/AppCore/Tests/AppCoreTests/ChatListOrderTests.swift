import XCTest
@testable import AppCore

final class ChatListOrderTests: XCTestCase {
    private func at(_ n: Double) -> Date { Date(timeIntervalSince1970: 1_000_000 + n) }

    private func chat(_ id: String, lastAt: Double? = nil, pinnedAt: Double? = nil, unread: Int = 0, muted: Bool = false, archived: Bool = false) -> Chat {
        let messages = lastAt.map {
            [Message(id: "m-\(id)", senderID: "c-\(id)", sentAt: at($0), content: .text("hi"), status: .delivered, replyToID: nil, reactions: [])]
        } ?? []
        return Chat(id: id, contactID: "c-\(id)", encryption: .endToEnd, folderIDs: [], unreadCount: unread, messages: messages, isRequest: false,
                    pinnedAt: pinnedAt.map(at), muted: muted, archived: archived)
    }

    func testMaxPinsIsFive() { XCTAssertEqual(ChatListOrder.maxPins, 5) }

    func testPinnedFirstNewestPinFirstThenByLastMessage() {
        let list = [chat("old", lastAt: 1), chat("pinEarly", lastAt: 2, pinnedAt: 10), chat("new", lastAt: 50), chat("pinLate", lastAt: 1, pinnedAt: 20)]
        XCTAssertEqual(ChatListOrder.sorted(list).map(\.id), ["pinLate", "pinEarly", "new", "old"])
    }

    func testChatWithNoMessagesSortsLastAmongUnpinned() {
        XCTAssertEqual(ChatListOrder.sorted([chat("empty"), chat("a", lastAt: 1)]).map(\.id), ["a", "empty"])
    }

    func testEqualTimesKeepAStableOrderByID() {
        XCTAssertEqual(ChatListOrder.sorted([chat("b", lastAt: 5), chat("a", lastAt: 5)]).map(\.id), ["a", "b"])
    }

    func testMainListHidesArchivedAndArchiveShowsOnlyArchived() {
        let list = [chat("a"), chat("b", archived: true), chat("c")]
        XCTAssertEqual(ChatListOrder.visible(list, archive: false).map(\.id), ["a", "c"])
        XCTAssertEqual(ChatListOrder.visible(list, archive: true).map(\.id), ["b"])
    }

    func testBadgeCountsUnreadUnmutedUnarchivedChats() {
        let list = [chat("unread", unread: 3), chat("another", unread: 1), chat("read"), chat("muted", unread: 4, muted: true), chat("archived", unread: 2, archived: true)]
        XCTAssertEqual(ChatListOrder.badgeCount(list), 2)
    }

    func testDraftPreviewIsTheFirstNonBlankLine() {
        XCTAssertEqual(ChatListOrder.draftPreview("\n  \n  hello there \nsecond"), "hello there")
        XCTAssertNil(ChatListOrder.draftPreview("  \n "))
        XCTAssertNil(ChatListOrder.draftPreview(nil))
    }

    func testArchivedUnreadCountsArchivedChatsWithUnreadMessages() {
        let list = [chat("a", unread: 2, archived: true), chat("b", unread: 5), chat("c", unread: 1, archived: true), chat("d", archived: true)]
        XCTAssertEqual(ChatListOrder.archivedUnread(list), 2)
    }

    func testPinnedFirstKeepsTheOrderOfTheRest() {
        // The input is already in the store's activity order; only the pins move to the front.
        let list = [chat("c"), chat("a", pinnedAt: 5), chat("b"), chat("d", pinnedAt: 9)]
        XCTAssertEqual(ChatListOrder.pinnedFirst(list).map(\.id), ["d", "a", "c", "b"])
    }
}
