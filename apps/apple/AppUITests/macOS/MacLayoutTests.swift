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

    /// With no chat open, ⌘F goes to the chat list's search field (the Edit menu's own Find is replaced).
    func testMacFindShortcutFocusesTheChatListSearch() {
        let app = XCUIApplication.launched()
        XCTAssertTrue(app.element("mac.sidebar.chats").waitToExist())
        XCTAssertFalse(app.element("chat.composer.field").exists)
        app.typeKey("f", modifierFlags: .command)
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitToExist())
        let focused = NSPredicate(format: "hasKeyboardFocus == true")
        expectation(for: focused, evaluatedWith: field)
        waitForExpectations(timeout: 5)
    }

    /// With no chat open the detail shows the empty state; the window fits a 1024x768 screen with the Dock.
    func testMacEmptyDetailAndWindowFitsSmallScreen() {
        let app = XCUIApplication.launched()
        XCTAssertTrue(app.element("mac.sidebar.chats").waitToExist())
        XCTAssertTrue(app.element("mac.detail.empty").exists)
        let frame = app.windows.firstMatch.frame
        XCTAssertLessThanOrEqual(frame.maxY, 690 + 24)   // y is measured from the screen top, menu bar included
        XCTAssertLessThanOrEqual(frame.height, 640)
    }

    /// ⌘2 and ⌘3 jump to Calls and Contacts, ⌘1 back to Chats.
    func testMacSectionShortcuts() {
        let app = XCUIApplication.launched()
        XCTAssertTrue(app.element("mac.sidebar.chats").waitToExist())
        // Each section is checked by its content: the sidebar's selected state is not reliably exposed.
        let callRow = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'calls.row.'")).firstMatch
        app.typeKey("2", modifierFlags: .command)
        XCTAssertTrue(callRow.waitForExistence(timeout: 5))
        app.typeKey("3", modifierFlags: .command)
        XCTAssertTrue(app.element("contacts.invite").waitToExist())
        app.typeKey("1", modifierFlags: .command)
        XCTAssertTrue(app.element("contacts.invite").waitForNonExistence(timeout: 5))
        XCTAssertFalse(callRow.exists)
    }

    /// ⌃Tab opens the next chat, ⌃⇧Tab the previous one.
    func testMacNextAndPreviousChatShortcuts() {
        let app = XCUIApplication.launched()
        XCTAssertTrue(app.element("mac.sidebar.chats").waitToExist())
        app.typeKey("\t", modifierFlags: .control)
        XCTAssertTrue(app.element("chat.composer.field").waitToExist())
        XCTAssertFalse(app.element("mac.detail.empty").exists)
        app.typeKey("\t", modifierFlags: [.control, .shift])
        XCTAssertTrue(app.element("chat.composer.field").waitToExist())
    }

    func testMacOnboardingOffersStartAndLink() {
        let app = XCUIApplication.launched(skipOnboarding: false)
        XCTAssertTrue(app.element("welcome.haveaccount").waitToExist())
        XCTAssertTrue(app.element("welcome.start").exists)   // the Mac can create an account too
        app.element("welcome.haveaccount").click()
        XCTAssertTrue(app.element("link.qr").waitToExist())
    }
}
