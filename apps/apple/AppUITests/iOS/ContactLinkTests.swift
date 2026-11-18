import XCTest

@MainActor
final class ContactLinkTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testMyQRCodeShowsTheCodeAndTheUsername() {
        let app = XCUIApplication.launched()
        app.tab("Settings").tap()
        app.element("settings.profile").tap()
        let row = app.element("profile.qr")
        XCTAssertTrue(row.waitToExist())
        row.tap()
        XCTAssertTrue(app.element("myqr.code").waitToExist())
        let username = app.element("myqr.username")
        XCTAssertTrue(username.waitToExist())
        XCTAssertTrue(username.label.contains("yourname"))
        XCTAssertTrue(app.element("myqr.share").exists)
    }

    func testOpeningAPersonLinkAsksToAddThem() {
        let app = XCUIApplication.launched(extra: ["-openURL", "https://home.example.org/u/neha"])
        let username = app.element("link.confirm.username")
        XCTAssertTrue(username.waitToExist())
        XCTAssertTrue(username.label.contains("neha"))
        XCTAssertTrue(app.buttons["link.confirm.add"].exists)
    }

    /// New chat > Add contact > Paste link: both sheets must be gone before the confirmation comes up.
    func testPastingALinkInNewChatEndsOnTheConfirmation() {
        let app = XCUIApplication.launched()
        app.element("chatlist.compose").tap()
        let add = app.element("newchat.addtoolbar")
        XCTAssertTrue(add.waitToExist())
        add.tap()
        let paste = app.element("addcontact.paste")
        XCTAssertTrue(paste.waitToExist())
        paste.tap()
        let field = app.alerts.textFields.firstMatch
        XCTAssertTrue(field.waitToExist())
        field.typeText("https://home.example.org/u/neha")
        app.alerts.buttons["Open"].tap()
        let username = app.element("link.confirm.username")
        XCTAssertTrue(username.waitToExist(10))
        XCTAssertTrue(username.label.contains("neha"))
    }

    func testALinkForAnotherServerSaysSo() {
        let app = XCUIApplication.launched(extra: ["-openURL", "https://other.example.com/u/neha"])
        XCTAssertTrue(app.alerts.firstMatch.waitToExist())
        XCTAssertTrue(app.alerts.firstMatch.label.contains("different server"))
    }
}
