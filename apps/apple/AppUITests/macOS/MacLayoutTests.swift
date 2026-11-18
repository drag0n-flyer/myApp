import XCTest

@MainActor
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

    /// The File menu's New Chat item opens the new-chat sheet. With the app lock off, Lock Now is disabled, and with no
    /// chat open the chat commands are too. (The lock-cover case is covered by the AppCore rule tests.)
    func testMacNewChatMenuItemOpensTheSheet() {
        let app = XCUIApplication.launched()
        XCTAssertTrue(app.element("mac.sidebar.chats").waitToExist())
        let newChat = app.menuBars.menuItems["New Chat"]
        XCTAssertTrue(newChat.waitToExist())
        XCTAssertTrue(newChat.isEnabled)
        XCTAssertFalse(app.menuBars.menuItems["Lock Now"].isEnabled)
        XCTAssertFalse(app.menuBars.menuItems["Chat Info"].isEnabled)
        newChat.click()
        XCTAssertTrue(app.sheets.buttons["Cancel"].waitToExist())
    }

    func testMacOnboardingOffersStartAndLink() {
        let app = XCUIApplication.launched(skipOnboarding: false)
        XCTAssertTrue(app.element("welcome.haveaccount").waitToExist())
        XCTAssertTrue(app.element("welcome.start").exists)   // the Mac can create an account too
        app.element("welcome.haveaccount").click()
        XCTAssertTrue(app.element("link.qr").waitToExist())
    }
}
