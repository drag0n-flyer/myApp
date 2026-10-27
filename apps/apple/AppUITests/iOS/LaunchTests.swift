import XCTest

@MainActor
final class LaunchTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testLaunchesOnChats() {
        let app = XCUIApplication.launched()
        XCTAssertTrue(app.tab("Chats").waitToExist())
        XCTAssertTrue(app.tab("Chats").isSelected)
        let titles = ["Contacts", "Calls", "Chats", "Settings"]
        let frames = titles.map { app.tab($0).frame.minX }
        XCTAssertEqual(frames, frames.sorted(), "Tabs should be in the order Contacts, Calls, Chats, Settings")
    }
}
