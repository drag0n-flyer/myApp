import XCTest

/// On the Mac, activating the microphone starts a locked recording (the Mac never uses hold).
@MainActor
final class VoiceNoteMacTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private func openChat() -> XCUIApplication {
        let app = XCUIApplication.launched()
        let chats = app.element("mac.sidebar.chats")
        XCTAssertTrue(chats.waitToExist())
        chats.click()
        app.element("chatlist.row.chat-riya").click()
        XCTAssertTrue(app.element("chat.composer.field").waitToExist())
        return app
    }

    private func voiceMessageCount(_ app: XCUIApplication) -> Int {
        app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH 'Play voice message'")).count
    }

    func testActivatingTheMicStartsALockedRecordingThatSendsAfterStop() {
        let app = openChat()
        let before = voiceMessageCount(app)
        app.element("chat.composer.mic").click()
        XCTAssertTrue(app.element("chat.voice.stop").waitToExist(), "Locked at once: the bar has Stop")
        app.element("chat.voice.stop").click()
        XCTAssertTrue(app.element("chat.voice.preview").waitToExist())
        app.element("chat.voice.send").click()
        XCTAssertFalse(app.element("chat.voice.bar").waitForExistence(timeout: 1))
        XCTAssertEqual(voiceMessageCount(app), before + 1)
    }

    func testDeleteAddsNoMessage() {
        let app = openChat()
        let before = voiceMessageCount(app)
        app.element("chat.composer.mic").click()
        XCTAssertTrue(app.element("chat.voice.delete").waitToExist())
        app.element("chat.voice.delete").click()
        XCTAssertFalse(app.element("chat.voice.bar").waitForExistence(timeout: 1))
        XCTAssertEqual(voiceMessageCount(app), before)
    }
}
