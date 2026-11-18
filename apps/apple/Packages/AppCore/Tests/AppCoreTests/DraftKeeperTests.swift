import XCTest
@testable import AppCore

final class DraftKeeperTests: XCTestCase {
    func testSetAndGet() {
        var keeper = DraftKeeper()
        keeper.set("hello", for: "a")
        XCTAssertEqual(keeper.draft(for: "a"), "hello")
    }

    func testChatsAreIndependent() {
        var keeper = DraftKeeper()
        keeper.set("one", for: "a")
        keeper.set("two", for: "b")
        XCTAssertEqual(keeper.draft(for: "a"), "one")
        XCTAssertEqual(keeper.draft(for: "b"), "two")
        XCTAssertEqual(keeper.draft(for: "c"), "")
    }

    func testWhitespaceOnlyIsDropped() {
        var keeper = DraftKeeper()
        keeper.set("hi", for: "a")
        keeper.set("   ", for: "a")
        XCTAssertEqual(keeper.draft(for: "a"), "")
    }
}

final class LinkDetectorTests: XCTestCase {
    func testDetectsWebLink() {
        let text = LinkDetector.attributed("see https://example.com/a now")
        let links = text.runs.compactMap(\.link)
        XCTAssertEqual(links.map(\.absoluteString), ["https://example.com/a"])
        XCTAssertEqual(String(text.characters), "see https://example.com/a now")
    }

    func testPlainTextHasNoLink() {
        XCTAssertTrue(LinkDetector.attributed("hello there").runs.compactMap(\.link).isEmpty)
    }

    func testOnlyWebAndMailLinks() {
        let links = LinkDetector.attributed("call tel:+123456789 or x@example.com").runs.compactMap(\.link)
        XCTAssertTrue(links.allSatisfy { ["http", "https", "mailto"].contains($0.scheme ?? "") })
    }
}

extension LinkDetectorTests {
    func testSkipsLinksWithBidiControls() {
        let text = LinkDetector.attributed("open https://exa\u{202E}mple.com now")
        XCTAssertTrue(text.runs.compactMap(\.link).isEmpty)
    }

    func testNoLinksWhenDirectionControlIsOutsideTheLink() {
        for control in ["\u{202E}", "\u{2066}", "\u{200F}", "\u{061C}"] {
            let text = LinkDetector.attributed("\(control)https://example.com/a")
            XCTAssertTrue(text.runs.compactMap(\.link).isEmpty, "control \(control.unicodeScalars.first!.value)")
        }
    }
}

final class OwnerGuardTests: XCTestCase {
    func testSwitchingAccountsReportsChange() {
        var owner = OwnerGuard()
        XCTAssertTrue(owner.switchTo("a"))
        XCTAssertFalse(owner.switchTo("a"))
        XCTAssertTrue(owner.switchTo("b"))
        XCTAssertTrue(owner.isOwner("b"))
        XCTAssertFalse(owner.isOwner("a"))
        owner.clear()
        XCTAssertFalse(owner.isOwner("b"))
        XCTAssertTrue(owner.switchTo("b"))
    }
}
