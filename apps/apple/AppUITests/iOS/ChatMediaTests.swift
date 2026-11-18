import XCTest

/// Photo bubbles on sample data. Sample messages have no file and nothing to fetch, so the bubble is a plain
/// placeholder with the size, not a Download button.
@MainActor
final class ChatMediaTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testSamplePhotoBubbleIsAPlaceholderAndTappingItDoesNotCrash() {
        let app = XCUIApplication.launched()
        app.element("chatlist.row.chat-riya").tap()
        XCTAssertTrue(app.element("chat.header").waitToExist())

        let placeholder = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'chat.media.placeholder.'")).firstMatch
        var swipes = 0
        while !placeholder.exists, swipes < 6 {
            app.swipeDown()
            swipes += 1
        }
        XCTAssertTrue(placeholder.waitToExist(), "A sample photo shows a placeholder")
        XCTAssertTrue(placeholder.label.hasPrefix("Photo"))
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'chat.media.download.'")).firstMatch.exists,
                       "Nothing to download with sample data")
        placeholder.tap()
        // The app stays up.
        XCTAssertTrue(app.element("chat.header").exists)
    }
}
