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

    func testShortPageDoesNotReachStart() {
        var pager = HistoryPager()
        pager.begin()
        pager.finish(loadedCount: 49)
        XCTAssertFalse(pager.reachedStart)
        XCTAssertTrue(pager.shouldLoad(oldestVisibleIndex: 0))
    }

    func testEmptyPageReachesStart() {
        var pager = HistoryPager()
        pager.begin()
        pager.finish(loadedCount: 0)
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
        XCTAssertFalse(history.pager.reachedStart, "A short page is not proof of the start")
        XCTAssertEqual(history.loadPage(newest: newest, loader: loader), 0)
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

    func testLoadUntilWithEmptyNewestEnds() {
        var history = ChatHistory()
        XCTAssertFalse(history.load(until: "x", newest: [], loader: { _, _ in [] }))
        XCTAssertTrue(history.pager.reachedStart)
    }

    /// A store whose contents the test can change; `loader` has the semantics of `ChatStore.messages(in:before:limit:)`.
    private final class MutableStore {
        var all: [Message]
        init(_ all: [Message]) { self.all = all }
        func newest(_ n: Int) -> [Message] { Array(all.suffix(n)) }
        func load(_ before: String, _ limit: Int) -> [Message] {
            guard let at = all.firstIndex(where: { $0.id == before }) else { return [] }
            return Array(all[max(0, at - limit)..<at])
        }
    }

    private func assertContiguous(_ ids: [String], from: Int, to: Int, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(ids, (from...to).map { "m\($0)" }, file: file, line: line)
    }

    // Review: more than 50 messages arrive while the chat is open (no older pages loaded).
    func testBurstOfSixtyWithoutOlderPages() {
        let store = MutableStore((1...140).map(msg))
        var history = ChatHistory()
        let previous = store.newest(50)
        store.all += (141...200).map(msg)
        let now = store.newest(50)
        history.sync(previousNewest: previous, newest: now, loader: store.load)
        let merged = history.messages(newest: now)
        assertContiguous(merged.map(\.id), from: 91, to: 200)
        var tracker = NewestTracker()
        tracker.setAtBottom(true, newestID: "m140")
        tracker.setAtBottom(false, newestID: "m140")
        XCTAssertEqual(tracker.unseenCount(in: merged), 60)
    }

    func testBurstOfSixtyWithOlderPagesLoaded() {
        let store = MutableStore((1...140).map(msg))
        var history = ChatHistory()
        let previous = store.newest(50)
        _ = history.loadPage(newest: previous, loader: store.load)
        store.all += (141...200).map(msg)
        let now = store.newest(50)
        history.sync(previousNewest: previous, newest: now, loader: store.load)
        assertContiguous(history.messages(newest: now).map(\.id), from: 41, to: 200)
    }

    func testBurstLargerThanOnePageLeavesNoGap() {
        let store = MutableStore((1...100).map(msg))
        var history = ChatHistory()
        let previous = store.newest(50)
        store.all += (101...400).map(msg)
        let now = store.newest(50)
        history.sync(previousNewest: previous, newest: now, loader: store.load)
        assertContiguous(history.messages(newest: now).map(\.id), from: 51, to: 400)
    }

    // Review: a message older than the newest window is deleted or reacted to in the store.
    func testOlderMessageDeletedInStoreShowsDeleted() {
        let store = MutableStore((1...120).map(msg))
        var history = ChatHistory()
        let newest = store.newest(50)
        _ = history.loadPage(newest: newest, loader: store.load)
        var gone = msg(60)
        gone.content = .deleted
        gone.reactions = ["👍"]
        store.all[59] = gone
        history.sync(previousNewest: newest, newest: newest, loader: store.load)
        let shown = history.messages(newest: newest).first { $0.id == "m60" }
        XCTAssertEqual(shown?.content, .deleted)
        XCTAssertEqual(shown?.reactions, ["👍"])
    }

    // B-M1: unchanged windows cost nothing.
    func testUnchangedSyncsReadNothing() {
        let store = MutableStore((1...120).map(msg))
        var history = ChatHistory()
        let newest = store.newest(50)
        _ = history.loadPage(newest: newest, loader: store.load)
        let before = history.older.map(\.id)
        var reads = 0
        for _ in 0..<10 {
            history.sync(previousNewest: newest, newest: newest, refreshOlder: false) { b, l in reads += 1; return store.load(b, l) }
        }
        XCTAssertEqual(reads, 0)
        XCTAssertEqual(history.older.map(\.id), before)
    }

    func testOneNewMessageCostsOneRightSizedRead() {
        let store = MutableStore((1...120).map(msg))
        var history = ChatHistory()
        let previous = store.newest(50)
        _ = history.loadPage(newest: previous, loader: store.load)
        store.all.append(msg(121))
        let now = store.newest(50)
        var limits: [Int] = []
        history.sync(previousNewest: previous, newest: now) { b, l in limits.append(l); return store.load(b, l) }
        XCTAssertEqual(limits, [51], "one read for the loaded older range (50 loaded + 1 that slid out)")
        assertContiguous(history.messages(newest: now).map(\.id), from: 21, to: 121)
        // Repeated changes do not make it grow.
        for _ in 0..<5 {
            history.sync(previousNewest: now, newest: now) { b, l in limits.append(l); return store.load(b, l) }
        }
        XCTAssertEqual(history.older.count, 51)
    }

    // B-M2: a message the store removed is gone, with no paging.
    func testRemovedMessageDisappearsWithoutReads() {
        let store = MutableStore((1...120).map(msg))
        var history = ChatHistory()
        let previous = store.newest(50)
        store.all.removeLast()
        let now = store.newest(50)
        var reads = 0
        history.sync(previousNewest: previous, newest: now) { b, l in reads += 1; return store.load(b, l) }
        XCTAssertEqual(reads, 0)
        let ids = history.messages(newest: now).map(\.id)
        XCTAssertFalse(ids.contains("m120"))
        assertContiguous(ids, from: 70, to: 119)
    }

    func testRemovedOlderMessageIsDropped() {
        let store = MutableStore((1...120).map(msg))
        var history = ChatHistory()
        let newest = store.newest(50)
        _ = history.loadPage(newest: newest, loader: store.load)
        store.all.removeAll { $0.id == "m60" }
        history.sync(previousNewest: newest, newest: newest) { b, l in store.load(b, l) }
        XCTAssertFalse(history.messages(newest: newest).contains { $0.id == "m60" })
    }

    /// 1050 older messages loaded, then one arrives.
    private func hugeRangeThenArrival(allowTrim: Bool) -> (history: ChatHistory, now: [Message], store: MutableStore) {
        let store = MutableStore((1...3000).map(msg))
        var history = ChatHistory()
        let previous = store.newest(50)
        for _ in 0..<21 { _ = history.loadPage(newest: previous, loader: store.load) }
        XCTAssertEqual(history.older.count, 1050)
        store.all.append(msg(3001))
        let now = store.newest(50)
        history.sync(previousNewest: previous, newest: now, allowTrim: allowTrim) { b, l in store.load(b, l) }
        return (history, now, store)
    }

    // The person is reading older messages: nothing may be removed under them.
    func testHugeLoadedRangeIsKeptWhileAwayFromBottom() {
        let (history, now, _) = hugeRangeThenArrival(allowTrim: false)
        assertContiguous(history.messages(newest: now).map(\.id), from: 1901, to: 3001)
    }

    // B-S1: a huge loaded range is trimmed (at the bottom) so paging reloads it fresh.
    func testHugeLoadedRangeIsTrimmedAtBottom() {
        let (history, now, store) = hugeRangeThenArrival(allowTrim: true)
        XCTAssertEqual(history.older.count, 500)
        XCTAssertFalse(history.pager.reachedStart)
        assertContiguous(history.messages(newest: now).map(\.id), from: 2452, to: 3001)
        var paged = history
        XCTAssertEqual(paged.loadPage(newest: now, loader: store.load), 50)
        assertContiguous(paged.messages(newest: now).map(\.id), from: 2402, to: 3001)
    }

    func testTrimWhenReturningToBottom() {
        let result = hugeRangeThenArrival(allowTrim: false)
        var history = result.history
        let now = result.now
        history.trimIfNeeded()
        XCTAssertEqual(history.older.count, 500)
        assertContiguous(history.messages(newest: now).map(\.id), from: 2452, to: 3001)
    }

    // A jump far back survives the next arrival while away from the bottom.
    func testJumpTargetSurvivesArrivalAwayFromBottom() {
        let store = MutableStore((1...3000).map(msg))
        var history = ChatHistory()
        let previous = store.newest(50)
        XCTAssertTrue(history.load(until: "m1000", newest: previous, loader: store.load))
        XCTAssertGreaterThan(history.older.count, 1000)
        store.all.append(msg(3001))
        let now = store.newest(50)
        history.sync(previousNewest: previous, newest: now, allowTrim: false) { b, l in store.load(b, l) }
        let ids = history.messages(newest: now).map(\.id)
        XCTAssertTrue(ids.contains("m1000"))
        assertContiguous(ids, from: Int(ids.first!.dropFirst())!, to: 3001)
    }

    // B-S2: a burst too big to close leaves no hole.
    func testHugeBurstDiscardsOlderAndPagingContinues() {
        let store = MutableStore((1...100).map(msg))
        var history = ChatHistory()
        let previous = store.newest(50)
        _ = history.loadPage(newest: previous, loader: store.load)
        store.all += (101...1600).map(msg)
        let now = store.newest(50)
        history.sync(previousNewest: previous, newest: now) { b, l in store.load(b, l) }
        assertContiguous(history.messages(newest: now).map(\.id), from: 1551, to: 1600)
        XCTAssertEqual(history.loadPage(newest: now, loader: store.load), 50)
        assertContiguous(history.messages(newest: now).map(\.id), from: 1501, to: 1600)
    }

    // B-S3: looking for a message that is not there stops at the cap.
    func testJumpToMissingMessageStopsAtCap() {
        let store = MutableStore((1...5000).map(msg))
        var history = ChatHistory()
        var reads = 0
        let found = history.load(until: "nope", newest: store.newest(50)) { b, l in reads += 1; return store.load(b, l) }
        XCTAssertFalse(found)
        XCTAssertLessThanOrEqual(reads, 40)
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
