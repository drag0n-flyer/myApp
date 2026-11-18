import XCTest

/// Voice notes on sample data with the stand-in recorder (no microphone). A real hold-and-slide is a touch
/// gesture, so the lock is reached by pressing the microphone and dragging up past the lock distance.
@MainActor
final class VoiceNoteTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private func openChat() -> XCUIApplication {
        let app = XCUIApplication.launched()
        app.element("chatlist.row.chat-riya").tap()
        XCTAssertTrue(app.element("chat.header").waitToExist())
        return app
    }

    private func voiceMessageCount(_ app: XCUIApplication) -> Int {
        app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH 'Play voice message'")).count
    }

    private func lockRecording(_ app: XCUIApplication) {
        let mic = app.element("chat.composer.mic")
        XCTAssertTrue(mic.waitToExist())
        let start = mic.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        // The button is about 42 points tall: 3.5 heights up is well past the 70 point lock distance.
        let end = mic.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: -3.0))
        start.press(forDuration: 0.6, thenDragTo: end)
        XCTAssertTrue(app.element("chat.voice.bar").waitToExist())
        XCTAssertTrue(app.element("chat.voice.stop").waitToExist(), "Locked: the bar has Stop")
        XCTAssertTrue(app.element("chat.voice.status").label.contains("Recording locked"))
    }

    func testLockedRecordingStopsToAPreviewAndSendAddsAVoiceMessage() {
        let app = openChat()
        let before = voiceMessageCount(app)
        lockRecording(app)
        app.element("chat.voice.stop").tap()
        XCTAssertTrue(app.element("chat.voice.preview").waitToExist())
        XCTAssertTrue(app.element("chat.voice.play").exists)
        app.element("chat.voice.send").tap()
        XCTAssertFalse(app.element("chat.voice.bar").waitForExistence(timeout: 1), "The bar closes after Send")
        XCTAssertEqual(voiceMessageCount(app), before + 1)
    }

    func testDeleteFromThePreviewAddsNoMessage() {
        let app = openChat()
        let before = voiceMessageCount(app)
        lockRecording(app)
        app.element("chat.voice.stop").tap()
        XCTAssertTrue(app.element("chat.voice.preview").waitToExist())
        app.element("chat.voice.delete").tap()
        XCTAssertFalse(app.element("chat.voice.bar").waitForExistence(timeout: 1))
        XCTAssertTrue(app.element("chat.composer.mic").exists)
        XCTAssertEqual(voiceMessageCount(app), before)
    }

    func testAPlainTapShowsTheHintAndSendsNothing() {
        let app = openChat()
        let before = voiceMessageCount(app)
        app.element("chat.composer.mic").press(forDuration: 0.05)
        XCTAssertTrue(app.element("chat.voice.hint").waitToExist())
        XCTAssertFalse(app.element("chat.voice.bar").exists)
        XCTAssertEqual(voiceMessageCount(app), before)
    }
}
