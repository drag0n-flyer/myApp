import XCTest

@MainActor
final class ProfilePhotoTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Sample mode: the first sample contact ("Riya") has a sample photo. The avatar shows it (the photo carries the
    /// identifier `avatar.photo` while UI testing).
    func testTheSampleContactsPhotoShowsInTheChatListAndChatInfo() {
        let app = XCUIApplication.launched()
        XCTAssertTrue(app.element("avatar.photo").waitToExist())

        let row = app.element("chatlist.row.chat-riya")
        XCTAssertTrue(row.waitToExist())
        row.tap()
        XCTAssertTrue(app.element("chat.header").waitToExist())
        app.element("chat.header").tap()
        XCTAssertTrue(app.element("chatinfo.encryption").waitToExist())
        XCTAssertTrue(app.element("avatar.photo").waitToExist())
    }

    func testTheProfileScreenOffersChangePhoto() {
        let app = XCUIApplication.launched()
        app.tab("Settings").tap()
        app.element("settings.profile").tap()
        XCTAssertTrue(app.element("profile.photo.change").waitToExist())
        // No photo yet, so nothing to remove.
        XCTAssertFalse(app.element("profile.photo.remove").exists)
        app.element("profile.photo.change").tap()
        XCTAssertTrue(app.buttons["Choose photo"].waitToExist())
    }
}
