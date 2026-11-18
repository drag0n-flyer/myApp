import XCTest

@MainActor
final class ProfileNameTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// The name sits inside a row that reads as one element ("Name, Asha Rao"), so look for it in any label.
    private func shows(_ name: String, in app: XCUIApplication) -> Bool {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", name)).firstMatch.waitToExist()
    }

    func testEditingTheNameShowsItOnTheProfileAndInSettings() {
        let app = XCUIApplication.launched()
        app.tab("Settings").tap()
        app.element("settings.profile").tap()
        app.element("profile.name").tap()

        let field = app.textFields["profile.name.field"]
        XCTAssertTrue(field.waitToExist())
        // The field starts with the stored name; clear it, and Save is off while it is empty.
        field.tap()
        let stored = (field.value as? String) ?? ""
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: stored.count))
        XCTAssertFalse(app.buttons["profile.name.save"].isEnabled)

        field.typeText("Asha Rao")
        XCTAssertEqual(app.element("profile.name.count").label, "8/40")
        XCTAssertTrue(app.buttons["profile.name.save"].isEnabled)
        app.buttons["profile.name.save"].tap()

        XCTAssertTrue(shows("Asha Rao", in: app))
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(shows("Asha Rao", in: app))
    }

    func testCancelKeepsTheName() {
        let app = XCUIApplication.launched()
        app.tab("Settings").tap()
        app.element("settings.profile").tap()
        app.element("profile.name").tap()
        let field = app.textFields["profile.name.field"]
        XCTAssertTrue(field.waitToExist())
        field.tap()
        field.typeText("zzz")
        app.buttons["profile.name.cancel"].tap()
        XCTAssertTrue(shows("Your Name", in: app))
    }
}
