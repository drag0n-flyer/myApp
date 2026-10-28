import XCTest

@MainActor
final class LinkingTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Sample mode: the scanner shows its paste field, then the approval screen asks before linking.
    func testApprovingANewDeviceAddsItToTheList() {
        let app = XCUIApplication.launched()
        app.tab("Settings").tap()
        app.element("settings.devices").tap()
        XCTAssertTrue(app.element("devices.link").waitToExist())
        app.element("devices.link").tap()

        let paste = app.element("scanner.paste")
        XCTAssertTrue(paste.waitToExist())
        paste.tap()
        paste.typeText("https://home.example.org/link/sample#7Q2K9")
        app.element("scanner.done").tap()

        XCTAssertTrue(app.element("link.approval.details").waitToExist())
        app.element("link.approve").tap()
        XCTAssertTrue(app.staticTexts["New Mac"].waitToExist())
    }
}
