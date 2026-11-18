import XCTest

/// Typing and presence (spec 6.1) with the sample app's scripted fake: `-scriptedTyping` makes Riya online and typing
/// in the sample chat for about ten seconds, then her message arrives.
@MainActor
final class TypingPresenceTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private func waitForLabel(_ element: XCUIElement, contains text: String, key: String = "label", timeout: TimeInterval = 8) {
        let predicate = NSPredicate(format: "\(key) CONTAINS %@", text)
        let result = XCTWaiter().wait(for: [expectation(for: predicate, evaluatedWith: element)], timeout: timeout)
        XCTAssertEqual(result, .completed, "Label of \(element) never contained \(text): \(element.label)")
    }

    func testSampleChatShowsTypingInTheListAndTheHeaderThenTheMessage() {
        let app = XCUIApplication.launched(extra: ["-scriptedTyping"])
        let row = app.element("chatlist.row.chat-riya")
        XCTAssertTrue(row.waitToExist())
        waitForLabel(row, contains: "typing…")
        row.tap()
        let header = app.element("chat.header")
        XCTAssertTrue(header.waitToExist())
        waitForLabel(header, contains: "typing…", key: "value")
        // The message ends the typing line.
        XCTAssertTrue(app.staticTexts["On my way!"].waitToExist(20))
        let notTyping = NSPredicate(format: "NOT (value CONTAINS %@)", "typing…")
        let result = XCTWaiter().wait(for: [expectation(for: notTyping, evaluatedWith: header)], timeout: 5)
        XCTAssertEqual(result, .completed)
    }

    func testThePrivacySwitchesExistAndAreOnByDefault() {
        let app = XCUIApplication.launched()
        app.tab("Settings").tap()
        app.element("settings.privacy").tap()
        for id in ["privacy.typing", "privacy.presence"] {
            let toggle = app.switches[id]
            XCTAssertTrue(toggle.scrollToExist(in: app), id)
            XCTAssertEqual(toggle.value as? String, "1", id)
            // The switch sits at the trailing edge of its row.
            toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
            XCTAssertEqual(toggle.value as? String, "0", id)
        }
    }
}
