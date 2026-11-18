import XCTest

/// Message menu, reply and edit bars, scroll button and in-chat search, on sample data.
@MainActor
final class ChatActionsTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private func openRiya() -> XCUIApplication {
        let app = XCUIApplication.launched()
        app.element("chatlist.row.chat-riya").tap()
        XCTAssertTrue(app.element("chat.header").waitToExist())
        return app
    }

    private func composerField(_ app: XCUIApplication) -> XCUIElement {
        app.textFields["chat.composer.field"].exists ? app.textFields["chat.composer.field"] : app.textViews["chat.composer.field"]
    }

    func testLongPressShowsReplyAndCopy() {
        let app = openRiya()
        // Unique text: "Are you coming tonight?" also appears as the quote inside a later reply.
        let bubble = app.staticTexts["See you at 6, call me when you leave"]
        XCTAssertTrue(bubble.waitToExist())
        bubble.press(forDuration: 1.0)
        XCTAssertTrue(app.buttons["Reply"].waitToExist())
        XCTAssertTrue(app.buttons["Copy"].exists)
        XCTAssertTrue(app.buttons["Forward"].exists)
        XCTAssertFalse(app.buttons["Edit"].exists, "Edit is only for your own messages")
        XCTAssertFalse(app.buttons["Delete"].exists, "Delete is only for your own messages")
    }

    func testReplyShowsBarAndSendsQuote() {
        let app = openRiya()
        let original = "See you at 6, call me when you leave"
        let bubble = app.staticTexts[original]
        XCTAssertTrue(bubble.waitToExist())
        bubble.press(forDuration: 1.0)
        app.buttons["Reply"].tap()
        XCTAssertTrue(app.element("chat.replybar").waitToExist())
        XCTAssertTrue(app.staticTexts["Replying to Riya"].exists)

        let field = composerField(app)
        field.tap()
        field.typeText("On my way")
        app.element("chat.composer.send").tap()
        XCTAssertTrue(app.staticTexts["On my way"].waitToExist())
        XCTAssertFalse(app.element("chat.replybar").exists, "The bar closes once the reply is sent")
        // The original text now shows twice: its own bubble and the quote in the reply.
        XCTAssertEqual(app.staticTexts.matching(NSPredicate(format: "label == %@", original)).count, 2)
    }

    func testEditShowsEditingBar() {
        let app = openRiya()
        let own = app.staticTexts["Yes! Around 6 🙂"]
        XCTAssertTrue(own.waitToExist())
        own.press(forDuration: 1.0)
        app.buttons["Edit"].tap()
        XCTAssertTrue(app.element("chat.editbar").waitToExist())
        XCTAssertTrue(app.staticTexts["Editing"].exists)
        app.element("chat.bar.close").tap()
        XCTAssertFalse(app.element("chat.editbar").exists)
    }

    func testScrollButtonAppearsAfterScrollingUp() {
        let app = openRiya()
        let field = composerField(app)
        field.tap()
        for i in 1...20 {
            field.typeText("Filler message \(i)")
            app.element("chat.composer.send").tap()
        }
        XCTAssertFalse(app.element("chat.scrollToNewest").exists, "At the bottom there is no button")
        for _ in 0..<4 { app.swipeDown() }
        let button = app.element("chat.scrollToNewest")
        XCTAssertTrue(button.waitToExist())
        button.tap()
        XCTAssertTrue(app.staticTexts["Filler message 20"].waitToExist())
    }

    func testSearchInChat() {
        let app = openRiya()
        app.element("chat.header").tap()
        app.buttons["Search"].tap()
        let field = app.textFields["chat.search.field"]
        XCTAssertTrue(field.waitToExist())
        field.typeText("leave")
        let hits = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'chat.search.hit.'"))
        XCTAssertTrue(hits.firstMatch.waitToExist())
        XCTAssertGreaterThanOrEqual(hits.count, 1)

        field.clearAndType("zzzz-nothing")
        XCTAssertTrue(app.element("chat.search.empty").waitToExist())
        XCTAssertTrue(app.staticTexts["No results"].exists)

        field.clearAndType("leave")
        hits.firstMatch.tap()
        XCTAssertFalse(app.element("chat.search.results").waitForExistence(timeout: 2))
    }
}

private extension XCUIElement {
    func clearAndType(_ text: String) {
        guard let current = value as? String, !current.isEmpty else { typeText(text); return }
        tap()
        typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count) + text)
    }
}
