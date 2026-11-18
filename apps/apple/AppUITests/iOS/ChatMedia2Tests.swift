import XCTest

/// Send preview, upload cancel and the "Media and files" gallery, on sample data. Sample uploads stay "uploading"
/// for a few seconds, which is when the Cancel control is there.
@MainActor
final class ChatMedia2Tests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private func openRiya(_ app: XCUIApplication) {
        app.element("chatlist.row.chat-riya").tap()
        XCTAssertTrue(app.element("chat.header").waitToExist())
    }

    private func openPreview(_ app: XCUIApplication) {
        app.buttons["Attach"].tap()
        app.buttons["Photo"].tap()
        XCTAssertTrue(app.element("media.preview").waitToExist())
    }

    func testThePreviewHasACaptionFieldAndSend() {
        let app = XCUIApplication.launched()
        openRiya(app)
        openPreview(app)
        XCTAssertTrue(app.element("media.preview.caption").exists)
        XCTAssertTrue(app.buttons["media.preview.send"].exists)
        XCTAssertTrue(app.buttons["media.preview.cancel"].exists)
        // Cancel closes it and sends nothing.
        app.buttons["media.preview.cancel"].tap()
        XCTAssertFalse(app.element("media.preview").waitForExistence(timeout: 2))
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'chat.upload.cancel.'")).firstMatch.exists)
    }

    func testSendingShowsTheCaptionAfterThePhotoAndCancelRemovesTheRow() {
        let app = XCUIApplication.launched()
        openRiya(app)
        openPreview(app)
        let caption = app.element("media.preview.caption")
        caption.tap()
        caption.typeText("Look at this")
        app.buttons["media.preview.send"].tap()

        let cancel = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'chat.upload.cancel.'")).firstMatch
        XCTAssertTrue(cancel.waitToExist(), "An uploading photo shows its Cancel control")
        XCTAssertTrue(app.staticTexts["Look at this"].waitToExist(), "The caption follows as a text message")

        let placeholders = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'chat.media.placeholder.'"))
        let before = placeholders.count
        cancel.tap()
        XCTAssertFalse(cancel.waitForExistence(timeout: 2), "Cancel removes the uploading row")
        XCTAssertEqual(placeholders.count, before - 1)
    }

    func testTheGalleryOpensFromChatInfoAndShowsTheSamplePhoto() {
        let app = XCUIApplication.launched()
        openRiya(app)
        app.element("chat.header").tap()
        let row = app.element("chatinfo.media")
        XCTAssertTrue(row.waitToExist())
        row.tap()
        XCTAssertTrue(app.element("gallery").waitToExist())
        let cell = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'gallery.media.'")).firstMatch
        XCTAssertTrue(cell.waitToExist(), "The sample photo is in the Media tab")
        app.buttons["Files"].tap()
        XCTAssertTrue(app.element("gallery.files.empty").waitToExist(), "Riya sent no files")
    }
}
