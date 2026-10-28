import XCTest

@MainActor
final class CallInfoSheetUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testInfoSheetOpens() {
        let app = XCUIApplication.launched()
        app.element("chatlist.row.chat-riya").tap()
        app.element("chat.call.audio").tap()
        XCTAssertTrue(app.element("call.mode").waitToExist())

        app.buttons["Call info"].tap()
        XCTAssertTrue(app.buttons["Done"].waitToExist(), "the call info sheet opens")
        let labels = ["Connection", "Delay", "Sound lost", "Quality", "Data used"]
        for (index, label) in labels.enumerated() {
            let row = app.element("callinfo.row.\(index)")
            XCTAssertTrue(row.waitToExist(), "row \(index) is visible")
            XCTAssertTrue(row.label.hasPrefix(label), "row \(index) is read as \"\(row.label)\"")
        }
        XCTAssertFalse(app.element("callinfo.row.5").exists)

        app.buttons["Done"].tap()
        XCTAssertTrue(app.element("call.mode").waitToExist())
    }
}
