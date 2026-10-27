import XCTest

@MainActor
final class CallFlowTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private func startAudioCall(_ app: XCUIApplication) {
        app.element("chatlist.row.chat-riya").tap()
        app.element("chat.call.audio").tap()
        XCTAssertTrue(app.element("call.mode").waitToExist())
    }

    func testAudioCallShowsModeAndData() {
        let app = XCUIApplication.launched(network: "mobile")
        startAudioCall(app)
        XCTAssertEqual(app.element("call.mode").label, "Auto · Data saver")

        app.element("call.ctrl.data").tap()
        let high = app.element("datasheet.option.4")
        XCTAssertTrue(high.waitToExist())
        XCTAssertTrue(high.isEnabled)
        XCTAssertTrue(high.label.contains("Above your mobile data limit"))

        app.element("datasheet.option.3").tap()
        XCTAssertTrue(app.element("call.mode").waitToExist())
        XCTAssertEqual(app.element("call.mode").label, "Balanced")
    }

    func testReconnectBanner() {
        let app = XCUIApplication.launched()
        startAudioCall(app)
        app.element("call.simulate").tap()
        app.buttons["Lost"].tap()
        XCTAssertTrue(waitForLabel(app.element("call.banner"), "Reconnecting…"))
        for _ in 0..<3 {
            app.element("call.simulate").tap()
            app.buttons["Advance 10 s"].tap()
        }
        XCTAssertTrue(waitForLabel(app.element("call.banner"), "Call ended: connection lost"))
    }

    private func waitForLabel(_ element: XCUIElement, _ label: String) -> Bool {
        let match = expectation(for: NSPredicate(format: "label == %@", label), evaluatedWith: element)
        return XCTWaiter().wait(for: [match], timeout: 5) == .completed
    }

    func testCallHistoryMissedFilter() {
        let app = XCUIApplication.launched()
        app.tab("Calls").tap()
        app.element("calls.chip.missed").tap()
        XCTAssertTrue(app.staticTexts["Arjun"].waitToExist())
        XCTAssertFalse(app.staticTexts["Dev"].exists)
    }
}
