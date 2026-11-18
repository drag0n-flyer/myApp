import XCTest

/// Link previews (spec 6.3) in sample mode: a canned page answers every https link, so no network is used.
@MainActor
final class LinkPreviewTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private func openPrivacy(_ app: XCUIApplication) -> XCUIElement {
        app.tab("Settings").tap()
        XCTAssertTrue(app.element("settings.privacy").waitToExist())
        app.element("settings.privacy").tap()
        let toggle = app.element("privacy.linkpreviews")
        XCTAssertTrue(toggle.scrollToExist(in: app))
        return toggle
    }

    private func turnOn(_ app: XCUIApplication) {
        let toggle = openPrivacy(app)
        // A switch in a form row is tapped at its trailing edge.
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        XCTAssertEqual(toggle.value as? String, "1")
        app.tab("Chats").tap()
    }

    private func field(_ app: XCUIApplication) -> XCUIElement {
        let field = app.textFields["chat.composer.field"].exists ? app.textFields["chat.composer.field"] : app.textViews["chat.composer.field"]
        XCTAssertTrue(field.waitToExist())
        return field
    }

    func testPrivacySwitchExistsAndIsOff() {
        let app = XCUIApplication.launched()
        let toggle = openPrivacy(app)
        XCTAssertTrue(toggle.label.contains("Link previews"))
        XCTAssertEqual(toggle.value as? String, "0")
        XCTAssertTrue(app.staticTexts["To make a preview, your device contacts the website, which learns your IP address."].exists)
    }

    func testNothingIsOfferedWhileTheSwitchIsOff() {
        let app = XCUIApplication.launched()
        app.element("chatlist.row.chat-riya").tap()
        let field = field(app)
        field.tap()
        field.typeText("look https://example.com/page ")
        XCTAssertFalse(app.element("chat.linkpreview.card").waitForExistence(timeout: 3))
    }

    func testTheSampleMessageShowsThePreviewCard() {
        let app = XCUIApplication.launched()
        turnOn(app)
        app.element("chatlist.row.chat-riya").tap()
        let field = field(app)
        field.tap()
        field.typeText("look https://example.com/page ")
        let card = app.element("chat.linkpreview.card")
        XCTAssertTrue(card.waitToExist(10), "the composer shows the canned preview")
        XCTAssertTrue(app.element("chat.linkpreview.remove").exists)
        app.element("chat.composer.send").tap()
        XCTAssertFalse(app.element("chat.linkpreview.composer").waitForExistence(timeout: 2), "the composer card is gone after Send")
        let bubbleCard = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Link preview")).firstMatch
        XCTAssertTrue(bubbleCard.waitToExist(), "the sent message shows the card")
        XCTAssertEqual(bubbleCard.value as? String, "Example Page")
    }

    func testRemovedPreviewStaysRemoved() {
        let app = XCUIApplication.launched()
        turnOn(app)
        app.element("chatlist.row.chat-riya").tap()
        let field = field(app)
        field.tap()
        field.typeText("look https://example.com/page ")
        XCTAssertTrue(app.element("chat.linkpreview.card").waitToExist(10))
        app.element("chat.linkpreview.remove").tap()
        XCTAssertFalse(app.element("chat.linkpreview.composer").exists)
        field.typeText("and more")
        XCTAssertFalse(app.element("chat.linkpreview.card").waitForExistence(timeout: 3), "removed stays removed for this draft")
        app.element("chat.composer.send").tap()
        XCTAssertFalse(app.element("chat.linkpreview.card").waitForExistence(timeout: 2), "a message sent without the preview has none")
    }
}
