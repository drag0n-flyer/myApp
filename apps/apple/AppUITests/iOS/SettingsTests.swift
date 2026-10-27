import XCTest

@MainActor
final class SettingsTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private func openData(_ app: XCUIApplication) {
        app.tab("Settings").tap()
        app.element("settings.data").tap()
        XCTAssertTrue(app.element("data.mobilelimit").waitToExist())
    }

    func testMobileLimitChangesStrip() {
        let app = XCUIApplication.launched(network: "mobile")
        openData(app)
        app.element("data.mobilelimit").tap()
        app.buttons["Ultra-low"].tap()
        app.tab("Chats").tap()
        XCTAssertTrue(app.element("chatlist.strip").waitToExist())
        XCTAssertTrue(app.element("chatlist.strip").label.contains("Ultra-low"))
    }

    func testFooterShowsServerValues() {
        let app = XCUIApplication.launched()
        openData(app)
        XCTAssertEqual(app.element("data.footer").label, "Server default: Auto · Server maximum: High quality")
    }

    func testIPHidingDefault() {
        let app = XCUIApplication.launched()
        app.tab("Settings").tap()
        app.element("settings.privacy").tap()
        let row = app.element("privacy.ip.nonContacts")
        XCTAssertTrue(row.waitToExist())
        XCTAssertTrue(row.isSelected)
    }
}
