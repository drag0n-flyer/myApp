import XCTest
@testable import AppCore

final class HistoryPagerTests: XCTestCase {
    func testShouldLoadThreshold() {
        let pager = HistoryPager()
        XCTAssertTrue(pager.shouldLoad(oldestVisibleIndex: 4))
        XCTAssertFalse(pager.shouldLoad(oldestVisibleIndex: 5))
    }

    func testNotWhileLoading() {
        var pager = HistoryPager()
        pager.begin()
        XCTAssertTrue(pager.isLoading)
        XCTAssertFalse(pager.shouldLoad(oldestVisibleIndex: 0))
        pager.finish(loadedCount: 50)
        XCTAssertFalse(pager.isLoading)
        XCTAssertTrue(pager.shouldLoad(oldestVisibleIndex: 0))
    }

    func testShortPageReachesStart() {
        var pager = HistoryPager()
        pager.begin()
        pager.finish(loadedCount: 49)
        XCTAssertTrue(pager.reachedStart)
        XCTAssertFalse(pager.shouldLoad(oldestVisibleIndex: 0))
    }

    func testFullPageDoesNotReachStart() {
        var pager = HistoryPager()
        pager.begin()
        pager.finish(loadedCount: 50)
        XCTAssertFalse(pager.reachedStart)
    }
}

/// `ChatHistory`: the loaded-older-messages state, with a fake store that has `total` messages.
final class ChatHistoryTests: XCTestCase {
    private func msg(_ n: Int) -> Message {
        Message(id: "m\(n)", senderID: "x", sentAt: Date(timeIntervalSince1970: Double(n) * 60),
                content: .text("\(n)"), status: .read, replyToID: nil, reactions: [])
    }

    /// A store holding m1...m`total`; `loader` behaves like `ChatStore.messages(in:before:limit:)`.
    private func store(total: Int) -> (all: [Message], loader: (String, Int) -> [Message]) {
        let all = (1...total).map(msg)
        return (all, { before, limit in
            guard let at = all.firstIndex(where: { $0.id == before }) else { return [] }
            return Array(all[max(0, at - limit)..<at])
        })
    }

    func testMergeDedupesSortsAndPrefersNewest() {
        var changed = msg(5)
        changed.reactions = ["👍"]
        let merged = MessageMerge.merged(newest: [changed, msg(6)], older: [msg(3), msg(4), msg(5)])
        XCTAssertEqual(merged.map(\.id), ["m3", "m4", "m5", "m6"])
        XCTAssertEqual(merged[2].reactions, ["👍"])
    }

    func testLoadPageStopsAtStart() {
        let (all, loader) = store(total: 120)
        let newest = Array(all.suffix(30))
        var history = ChatHistory()
        XCTAssertEqual(history.loadPage(newest: newest, loader: loader), 50)
        XCTAssertFalse(history.pager.reachedStart)
        XCTAssertEqual(history.loadPage(newest: newest, loader: loader), 40)
        XCTAssertTrue(history.pager.reachedStart)
        XCTAssertEqual(history.loadPage(newest: newest, loader: loader), 0)
        XCTAssertEqual(history.messages(newest: newest).map(\.id), all.map(\.id))
    }

    // Review focus 1: a target older than the loaded page.
    func testJumpLoadsOlderPagesUntilFound() {
        let (all, loader) = store(total: 300)
        let newest = Array(all.suffix(50))
        var history = ChatHistory()
        XCTAssertTrue(history.load(until: "m60", newest: newest, loader: loader))
        let ids = history.messages(newest: newest).map(\.id)
        XCTAssertTrue(ids.contains("m60"))
        XCTAssertEqual(ids.count, Set(ids).count)
        XCTAssertEqual(ids.last, "m300")
    }

    func testJumpToLoadedMessageLoadsNothing() {
        let (all, loader) = store(total: 100)
        let newest = Array(all.suffix(50))
        var history = ChatHistory()
        XCTAssertTrue(history.load(until: "m80", newest: newest, loader: loader))
        XCTAssertFalse(history.pager.isLoading)
        XCTAssertEqual(history.messages(newest: newest).count, 50)
    }

    // Review focus 1: history ends before the target is found.
    func testJumpGivesUpWhenHistoryEnds() {
        let (all, loader) = store(total: 120)
        let newest = Array(all.suffix(50))
        var history = ChatHistory()
        XCTAssertFalse(history.load(until: "does-not-exist", newest: newest, loader: loader))
        XCTAssertTrue(history.pager.reachedStart)
        XCTAssertFalse(history.pager.isLoading)
    }

    func testBadStoreThatRepeatsItselfCannotLoopForever() {
        let page = (1...50).map(msg)
        var history = ChatHistory()
        let newest = [msg(51)]
        XCTAssertFalse(history.load(until: "nope", newest: newest, loader: { _, _ in page }))
        XCTAssertTrue(history.pager.reachedStart)
    }

    // Review focus 2: messages arriving while older pages are loaded.
    func testArrivalsDuringPagingNeitherDuplicateNorLoseMessages() {
        let (all, loader) = store(total: 200)
        var newest = Array(all.suffix(40))
        var history = ChatHistory()
        _ = history.loadPage(newest: newest, loader: loader)
        newest.append(msg(201))
        _ = history.loadPage(newest: newest, loader: loader)
        let ids = history.messages(newest: newest).map(\.id)
        XCTAssertEqual(ids.count, Set(ids).count)
        XCTAssertEqual(ids.last, "m201")
        XCTAssertEqual(ids.first, "m61")
        // The oldest loaded message is unchanged by the arrival, so a scroll anchor on it stays valid.
        XCTAssertEqual(history.messages(newest: Array(newest.dropLast())).first?.id, "m61")
    }

    func testMessageDeletedRemotelyUpdatesLoadedOlderCopy() {
        let (all, loader) = store(total: 100)
        let newest = Array(all.suffix(50))
        var history = ChatHistory()
        _ = history.loadPage(newest: newest, loader: loader)
        var deleted = msg(80)
        deleted.content = .deleted
        var updated = newest
        updated[updated.firstIndex { $0.id == "m80" }!] = deleted
        XCTAssertEqual(history.messages(newest: updated).first { $0.id == "m80" }?.content, .deleted)
    }
}

final class NewestTrackerTests: XCTestCase {
    private func msg(_ n: Int) -> Message {
        Message(id: "m\(n)", senderID: "x", sentAt: Date(timeIntervalSince1970: Double(n)), content: .text("\(n)"), status: .read, replyToID: nil, reactions: [])
    }

    func testFollowsWhileAtBottom() {
        var tracker = NewestTracker()
        tracker.setAtBottom(true, newestID: "m3")
        XCTAssertTrue(tracker.messagesChanged(newestID: "m4"))
        XCTAssertEqual(tracker.unseenCount(in: [msg(3), msg(4)]), 0)
    }

    // Review focus 2: scrolled up, messages arrive.
    func testStaysPutAndCountsWhenScrolledUp() {
        var tracker = NewestTracker()
        tracker.setAtBottom(true, newestID: "m3")
        tracker.setAtBottom(false, newestID: "m3")
        XCTAssertFalse(tracker.messagesChanged(newestID: "m4"))
        XCTAssertFalse(tracker.messagesChanged(newestID: "m5"))
        XCTAssertEqual(tracker.unseenCount(in: [msg(1), msg(2), msg(3), msg(4), msg(5)]), 2)
        // Older pages prepended do not change the count.
        XCTAssertEqual(tracker.unseenCount(in: [msg(0), msg(1), msg(2), msg(3), msg(4), msg(5)]), 2)
        tracker.setAtBottom(true, newestID: "m5")
        XCTAssertEqual(tracker.unseenCount(in: [msg(3), msg(4), msg(5)]), 0)
    }

    func testUnknownAnchorCountsNothing() {
        var tracker = NewestTracker()
        tracker.setAtBottom(true, newestID: "gone")
        tracker.setAtBottom(false, newestID: "gone")
        XCTAssertEqual(tracker.unseenCount(in: [msg(1), msg(2)]), 0)
    }
}
