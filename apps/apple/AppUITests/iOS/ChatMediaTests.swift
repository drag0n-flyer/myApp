import XCTest

/// Photo bubbles on sample data. Sample messages have no downloaded file, so the bubble offers "Download".
@MainActor
final class ChatMediaTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testSamplePhotoBubbleOffersDownloadAndTappingItDoesNotCrash() {
        let app = XCUIApplication.launched()
        app.element("chatlist.row.chat-riya").tap()
        XCTAssertTrue(app.element("chat.header").waitToExist())

        let download = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'chat.media.download.'")).firstMatch
        var swipes = 0
        while !download.exists, swipes < 6 {
            app.swipeDown()
            swipes += 1
        }
        XCTAssertTrue(download.waitToExist(), "A photo that is not on the device shows a Download button")
        XCTAssertEqual(download.label, "Download")
        download.tap()
        // Sample data has nothing to fetch: the bubble stays and the app stays up.
        XCTAssertTrue(app.element("chat.header").exists)
    }
}
