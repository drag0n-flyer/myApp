import XCTest

final class ChatFlowTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testFoldersFilterAndStrip() {
        let app = XCUIApplication.launched(network: "mobile")
        let strip = app.element("chatlist.strip")
        XCTAssertTrue(strip.waitToExist())
        XCTAssertTrue(strip.label.contains("Data saver"))
        XCTAssertEqual(app.element("chatlist.requests").label, "Message requests · 1")

        app.element("chatlist.chip.unread").tap()
        let rows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'chatlist.row.'"))
        XCTAssertEqual(rows.count, 1)
        XCTAssertTrue(app.element("chatlist.row.chat-riya").exists)
    }
}
