import XCTest

@MainActor
final class DisappearingMessagesTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Sample mode: choose 1 day in chat info, the row shows it, and the chat shows the system line.
    func testChoosingOneDayShowsTheRowAndTheSystemLine() {
        let app = XCUIApplication.launched()
        app.element("chatlist.row.chat-riya").tap()
        XCTAssertTrue(app.element("chat.header").waitToExist())
        app.element("chat.header").tap()

        let row = app.element("chatinfo.timer")
        XCTAssertTrue(row.waitToExist())
        XCTAssertTrue("\(row.label) \(row.value as? String ?? "")".contains("Off"))
        row.tap()

        let day = app.element("chatinfo.timer.86400")
        XCTAssertTrue(day.waitToExist())
        XCTAssertTrue(app.staticTexts["New messages disappear from this chat after the time you choose. People can still copy or photograph them."].exists)
        day.tap()

        app.navigationBars.buttons.element(boundBy: 0).tap()
        let updated = app.element("chatinfo.timer")
        XCTAssertTrue(updated.waitToExist())
        XCTAssertTrue("\(updated.label) \(updated.value as? String ?? "")".contains("1 day"))

        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["You set messages to disappear after 1 day"].waitToExist())
    }

    /// A message sent under a timer shows the timer glyph, labelled for VoiceOver.
    func testAMessageUnderATimerShowsTheGlyph() {
        let app = XCUIApplication.launched()
        app.element("chatlist.row.chat-riya").tap()
        XCTAssertTrue(app.element("chat.header").waitToExist())
        app.element("chat.header").tap()
        app.element("chatinfo.timer").tap()
        app.element("chatinfo.timer.604800").tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()

        let field = app.textFields["chat.composer.field"].exists ? app.textFields["chat.composer.field"] : app.textViews["chat.composer.field"]
        XCTAssertTrue(field.waitToExist())
        field.tap()
        field.typeText("Timed")
        app.element("chat.composer.send").tap()
        XCTAssertTrue(app.staticTexts["Timed"].waitToExist())
        let glyph = app.images.matching(NSPredicate(format: "label == %@", "Disappearing message")).firstMatch
        XCTAssertTrue(glyph.waitToExist())
    }
}
