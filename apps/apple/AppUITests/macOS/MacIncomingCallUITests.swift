import XCTest

@MainActor
final class MacIncomingCallUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testBannerAnswerStartsCall() {
        let app = XCUIApplication.launched()
        app.element("mac.simulateIncoming").click()
        let accept = app.element("mac.incoming.accept")
        XCTAssertTrue(accept.waitToExist())
        accept.click()
        XCTAssertTrue(app.element("call.mode").waitToExist(10))
        XCTAssertFalse(app.element("mac.incoming.accept").exists)
    }

    func testBannerDeclineDismisses() {
        let app = XCUIApplication.launched()
        app.element("mac.simulateIncoming").click()
        let decline = app.element("mac.incoming.decline")
        XCTAssertTrue(decline.waitToExist())
        decline.click()
        XCTAssertFalse(app.element("mac.incoming.accept").waitToExist(1))
    }
}
