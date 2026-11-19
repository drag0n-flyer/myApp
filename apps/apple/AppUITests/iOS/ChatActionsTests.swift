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
        for i in 1...20 {
            // The simulator sometimes drops keyboard focus after a send; typing needs it back.
            if !(field.value(forKey: "hasKeyboardFocus") as? Bool ?? false) { field.tap() }
            field.typeText("Filler message \(i)")
            app.element("chat.composer.send").tap()
        }
        XCTAssertFalse(app.element("chat.scrollToNewest").exists, "At the bottom there is no button")
        for _ in 0..<4 { app.swipeDown() }
        let button = app.element("chat.scrollToNewest")
        XCTAssertTrue(button.waitToExist())
        button.tap()
        let reachedNewest = app.staticTexts["Filler message 20"].waitForExistence(timeout: 5)
        if !reachedNewest {
            // Diagnostics for a failed scroll: did the action run, is the button still there, where is the list.
            let tapsValue = String(describing: app.element("chat.debug.scrollTaps").value)
            let stillShown = button.exists
            let lastFiller = (1...20).reversed().first { app.staticTexts["Filler message \($0)"].exists } ?? 0
            let attachment = XCTAttachment(string: app.debugDescription)
            attachment.lifetime = .keepAlways
            add(attachment)
            XCTFail("Did not reach the newest message: taps=\(tapsValue) buttonStillShown=\(stillShown) lastFiller=\(lastFiller)")
        }
    }

    func testSearchInChat() {
        let app = openRiya()
        app.element("chat.header").tap()
        app.buttons["Search"].tap()
        let field = app.textFields["chat.search.field"]
        XCTAssertTrue(field.waitToExist())
        field.typeText("leave")
        let count = app.element("chat.search.count")
        XCTAssertTrue(count.waitToExist())
        // The sample chat has two messages with "leave": the bar starts on the newest.
        XCTAssertTrue((count.label as NSString).hasPrefix("1 of 2"), "Count was \(count.label)")
        app.buttons["chat.search.next"].tap()
        XCTAssertTrue(count.waitForLabel("2 of 2"), "Next moves to the older result")
        app.buttons["chat.search.next"].tap()
        XCTAssertTrue(count.waitForLabel("1 of 2"), "Next wraps to the first result")
        app.buttons["chat.search.previous"].tap()
        XCTAssertTrue(count.waitForLabel("2 of 2"), "Previous wraps to the last result")

        field.clearAndType("zzzz-nothing")
        XCTAssertTrue(app.element("chat.search.empty").waitToExist())
        XCTAssertTrue(app.staticTexts["No results"].exists)

        app.buttons["chat.search.cancel"].tap()
        XCTAssertFalse(field.waitForExistence(timeout: 2))
    }
}

private extension XCUIElement {
    /// True once the label equals `text` (the label changes a moment after a tap).
    func waitForLabel(_ text: String, timeout: TimeInterval = 5) -> Bool {
        let done = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", text), object: self)
        return XCTWaiter().wait(for: [done], timeout: timeout) == .completed
    }

    func clearAndType(_ text: String) {
        guard let current = value as? String, !current.isEmpty else { typeText(text); return }
        tap()
        typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count) + text)
    }
}
