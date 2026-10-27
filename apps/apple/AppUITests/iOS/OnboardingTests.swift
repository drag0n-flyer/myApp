import XCTest

final class OnboardingTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private func type(_ app: XCUIApplication, _ id: String, _ text: String) {
        let field = app.textFields[id]
        XCTAssertTrue(field.waitToExist())
        field.tap()
        field.typeText(text)
    }

    func testDefaultServerSignUp() {
        let app = XCUIApplication.launched(skipOnboarding: false)
        app.element("welcome.start").tap()
        app.element("join.default").tap()
        type(app, "invite.field", "BETA-2026")
        app.element("invite.submit").tap()
        type(app, "account.name", "Your Name")
        type(app, "account.username", "yourname")
        XCTAssertTrue(app.staticTexts["✓ Available"].waitToExist())
        type(app, "account.email", "you@example.com")
        app.element("account.continue").tap()
        type(app, "verify.field", "123456")
        app.element("passkey.create").waitToExist()
        app.element("passkey.create").tap()
        app.element("permissions.continue").tap()
        XCTAssertTrue(app.tab("Chats").waitToExist())
    }

    func testUsernameTaken() {
        let app = XCUIApplication.launched(skipOnboarding: false)
        app.element("welcome.start").tap()
        app.element("join.default").tap()
        type(app, "invite.field", "BETA-2026")
        app.element("invite.submit").tap()
        type(app, "account.username", "riya")
        XCTAssertTrue(app.staticTexts["Taken"].waitToExist())
        XCTAssertFalse(app.element("account.continue").isEnabled)
    }

    func testInviteLinkSkipsCode() {
        let app = XCUIApplication.launched(skipOnboarding: false, extra: ["-openURL", "https://chat.example.org/invite/BETA-2026"])
        app.element("welcome.start").tap()
        XCTAssertTrue(app.textFields["account.username"].waitToExist())
        XCTAssertFalse(app.textFields["invite.field"].exists)
    }
}
