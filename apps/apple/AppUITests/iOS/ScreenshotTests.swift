import XCTest

/// Walks the main screens in sample mode and attaches a screenshot of each. Every section starts from a fresh
/// launch so one missing element only skips that screen.
@MainActor
final class ScreenshotTests: XCTestCase {
    override func setUp() { continueAfterFailure = true }

    private func shot(_ name: String) {
        Thread.sleep(forTimeInterval: 1)
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }

    /// Taps the element when it shows up; false when it does not.
    @discardableResult
    private func open(_ el: XCUIElement) -> Bool {
        guard el.waitForExistence(timeout: 8) else { print("Skipped, missing: \(el)"); return false }
        el.tap()
        return true
    }

    private func settingsRow(_ app: XCUIApplication, _ id: String, _ name: String) {
        guard open(app.tab("Settings")) else { return }
        if open(app.element(id)) { shot(name) }
    }

    func testCaptureScreens() {
        // Chat list, one-to-one chat, chat info, gallery.
        var app = XCUIApplication.launched()
        if app.element("chatlist.row.chat-riya").waitForExistence(timeout: 10) { shot("01-chat-list") }
        if open(app.element("chatlist.row.chat-riya")) {
            shot("02-chat-one-to-one")
            if open(app.element("chat.header")) {
                shot("03-chat-info")
                if open(app.element("chatinfo.media")) { shot("04-media-and-files") }
            }
        }

        // A chat with a photo placeholder.
        app = XCUIApplication.launched()
        if open(app.element("chatlist.row.chat-meera")) { shot("05-chat-with-photo") }

        // New chat, new group picker, group name.
        app = XCUIApplication.launched()
        if open(app.element("chatlist.compose")) {
            shot("06-new-chat")
            if open(app.element("newchat.newgroup")) {
                let people = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'newgroup.person.'"))
                if people.element(boundBy: 1).waitForExistence(timeout: 8) {
                    people.element(boundBy: 0).tap()
                    people.element(boundBy: 1).tap()
                    shot("07-new-group-people")
                    app.buttons["newgroup.next"].tap()
                    let name = app.textFields["newgroup.name"]
                    if name.waitForExistence(timeout: 8) {
                        name.tap()
                        name.typeText("Weekend plans")
                        shot("08-new-group-name")
                        app.buttons["newgroup.create"].tap()
                        if app.staticTexts["Weekend plans"].waitForExistence(timeout: 10) { shot("09-group-chat") }
                    }
                }
            }
        }

        // Settings and its pages, each from a fresh launch.
        app = XCUIApplication.launched()
        if open(app.tab("Settings")) { shot("10-settings") }
        if app.element("settings.profile").waitForExistence(timeout: 5) {
            app.element("settings.profile").tap()
            shot("11-profile")
            if open(app.element("profile.qr")) { shot("12-my-qr-code") }
        }
        app = XCUIApplication.launched()
        settingsRow(app, "settings.privacy", "13-privacy")
        app = XCUIApplication.launched()
        settingsRow(app, "settings.notifications", "14-notifications")
        app = XCUIApplication.launched()
        settingsRow(app, "settings.data", "15-data-and-storage")

        // Disappearing messages.
        app = XCUIApplication.launched()
        if open(app.element("chatlist.row.chat-riya")), open(app.element("chat.header")), open(app.element("chatinfo.timer")) {
            shot("16-disappearing-messages")
        }

        // Dark appearance.
        app = XCUIApplication.launched(extra: ["-appearance", "dark"])
        if app.element("chatlist.row.chat-riya").waitForExistence(timeout: 10) { shot("17-chat-list-dark") }
        if open(app.element("chatlist.row.chat-riya")) { shot("18-chat-dark") }
        app = XCUIApplication.launched(extra: ["-appearance", "dark"])
        if open(app.tab("Settings")) { shot("19-settings-dark") }

        XCTFail("Screenshots captured; this failure only exists so CI keeps the results.")
    }
}
