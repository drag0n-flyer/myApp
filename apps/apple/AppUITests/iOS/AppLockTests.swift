import XCTest

/// App lock with the sample fake for the system prompt. `-fakeAuth success,cancel,success` answers, in order:
/// 1. the prompt that turns the switch on, 2. the cover's automatic prompt on return (cancelled: the cover stays),
/// 3. the Unlock button.
@MainActor
final class AppLockTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testLockCoversTheAppOnReturnAndUnlocks() {
        let app = XCUIApplication.launched(extra: ["-fakeAuth", "success,cancel,success"])
        app.tab("Settings").tap()
        app.element("settings.privacy").tap()
        let toggle = app.switches["privacy.applock"]
        XCTAssertTrue(toggle.waitToExist())
        // The switch sits at the trailing edge of its row.
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        XCTAssertTrue(app.element("privacy.lockafter").waitToExist())   // on: the delay picker appears
        app.element("privacy.lockafter").tap()
        app.buttons["Immediately"].tap()

        XCUIDevice.shared.press(.home)
        app.activate()

        XCTAssertTrue(app.element("lock.cover").waitToExist())
        XCTAssertTrue(app.element("lock.unlock").waitToExist())
        XCTAssertFalse(app.tab("Chats").exists, "no chat screen is behind the cover")
        XCTAssertFalse(app.element("chatlist.strip").exists)

        app.element("lock.unlock").tap()
        XCTAssertTrue(app.tab("Chats").waitToExist())
        XCTAssertFalse(app.element("lock.cover").exists)
    }
}
