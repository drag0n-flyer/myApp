import XCTest

/// Pin and archive from the chat list's long-press menu, on sample data.
@MainActor
final class ChatListActionsTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private func choose(_ action: String, onRow id: String, in app: XCUIApplication) {
        let row = app.element("chatlist.row.\(id)")
        XCTAssertTrue(row.waitToExist())
        row.press(forDuration: 1.0)
        let button = app.buttons[action]
        XCTAssertTrue(button.waitToExist())
        button.tap()
    }

    func testArchiveShowsUnderArchivedAndUnarchiveBringsItBack() {
        let app = XCUIApplication.launched()
        XCTAssertFalse(app.element("chatlist.archived").exists, "No Archived row without archived chats")

        choose("chatlist.action.archive", onRow: "chat-sam", in: app)
        XCTAssertTrue(app.element("chatlist.archived").waitToExist())
        XCTAssertFalse(app.element("chatlist.row.chat-sam").exists, "An archived chat leaves the list")

        app.element("chatlist.archived").tap()
        XCTAssertTrue(app.element("archive.list").waitToExist())
        XCTAssertTrue(app.element("chatlist.row.chat-sam").waitToExist())

        choose("chatlist.action.archive", onRow: "chat-sam", in: app)
        XCTAssertTrue(app.staticTexts["No archived chats"].waitToExist())
    }

    func testPinnedChatListsFirst() {
        let app = XCUIApplication.launched()
        let first = app.element("chatlist.row.chat-riya")
        XCTAssertTrue(first.waitToExist())
        let meera = app.element("chatlist.row.chat-meera")
        XCTAssertTrue(meera.scrollToExist(in: app))
        XCTAssertGreaterThan(meera.frame.minY, first.frame.minY, "Meera is below Riya before the pin")

        choose("chatlist.action.pin", onRow: "chat-meera", in: app)
        let pinnedMeera = app.element("chatlist.row.chat-meera")
        XCTAssertTrue(pinnedMeera.waitToExist())
        XCTAssertLessThan(pinnedMeera.frame.minY, app.element("chatlist.row.chat-riya").frame.minY, "A pinned chat is first")
    }
}
