import XCTest

final class MacLayoutTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testMacThreeColumns() {
        let app = XCUIApplication.launched()
        let chats = app.element("mac.sidebar.chats")
        XCTAssertTrue(chats.waitToExist())
        chats.click()
        app.element("chatlist.row.chat-riya").click()
        XCTAssertTrue(app.element("chat.composer.field").waitToExist())
    }

    func testMacOnboardingIsLinkOnly() {
        let app = XCUIApplication.launched(skipOnboarding: false)
        XCTAssertTrue(app.element("welcome.haveaccount").waitToExist())
        XCTAssertFalse(app.element("welcome.start").exists)
        app.element("welcome.haveaccount").click()
        XCTAssertTrue(app.element("link.qr").waitToExist())
    }
}
