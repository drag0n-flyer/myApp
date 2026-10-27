import XCTest

@MainActor
final class AccessibilityTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    // Review Focus 1
    func testLargestTextChatList() {
        let app = XCUIApplication.launched(extra: ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"])
        let riya = app.element("chatlist.row.chat-riya")
        XCTAssertTrue(riya.waitToExist())
        XCTAssertTrue(riya.isHittable)
        XCTAssertTrue(riya.label.contains("Riya"))
        XCTAssertTrue(riya.label.contains("unread"), "Row label keeps the unread count at large sizes")

        riya.tap()
        app.element("chat.call.audio").tap()
        for id in ["call.ctrl.speaker", "call.ctrl.mute", "call.ctrl.video", "call.ctrl.data", "call.ctrl.end"] {
            XCTAssertTrue(app.element(id).waitToExist(), id)
            XCTAssertTrue(app.element(id).isHittable, "\(id) must stay tappable at the largest text size")
        }
    }

    // Review Focus 2
    func testRTLAndLongNames() {
        let app = XCUIApplication.launched(extra: ["-AppleLanguages", "(ar)", "-AppleLocale", "ar",
                                                   "-AppleTextDirection", "YES", "-NSForceRightToLeftWritingDirection", "YES"])
        for id in ["chatlist.row.chat-maryam", "chatlist.row.chat-ananth", "chatlist.row.chat-priya"] {
            let row = app.element(id)
            if !row.exists { app.swipeUp() }
            XCTAssertTrue(row.waitToExist(), id)
            XCTAssertTrue(row.isHittable, id)
        }
        for title in ["Contacts", "Calls", "Chats", "Settings"] {
            XCTAssertTrue(app.tab(title).exists, title)
        }
        XCTAssertGreaterThan(app.tab("Contacts").frame.minX, app.tab("Settings").frame.minX,
                             "Tabs mirror in right-to-left: Contacts sits on the right")
    }

    func testEveryButtonHasLabel() {
        let app = XCUIApplication.launched()
        assertLabelled(app, screen: "chat list")
        app.element("chatlist.row.chat-riya").tap()
        XCTAssertTrue(app.element("chat.header").waitToExist())
        assertLabelled(app, screen: "chat")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.tab("Settings").tap()
        assertLabelled(app, screen: "settings")
    }

    private func assertLabelled(_ app: XCUIApplication, screen: String) {
        let buttons = app.buttons.allElementsBoundByIndex.filter { $0.exists && $0.isHittable }
        let unlabelled = buttons.filter { $0.label.trimmingCharacters(in: .whitespaces).isEmpty }
        XCTAssertTrue(unlabelled.isEmpty, "Buttons without a VoiceOver label on \(screen): \(unlabelled.map(\.identifier))")
    }
}
