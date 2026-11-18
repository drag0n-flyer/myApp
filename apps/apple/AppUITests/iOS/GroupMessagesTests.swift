import XCTest

@MainActor
final class GroupMessagesTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Makes a sample group of the first two people in the list and opens it.
    private func openSampleGroup() -> XCUIApplication {
        let app = XCUIApplication.launched()
        app.element("chatlist.compose").tap()
        let newGroup = app.element("newchat.newgroup")
        XCTAssertTrue(newGroup.waitToExist())
        newGroup.tap()
        let people = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'newgroup.person.'"))
        XCTAssertTrue(people.element(boundBy: 1).waitToExist())
        people.element(boundBy: 0).tap()
        people.element(boundBy: 1).tap()
        app.buttons["newgroup.next"].tap()
        let name = app.textFields["newgroup.name"]
        XCTAssertTrue(name.waitToExist())
        name.tap()
        name.typeText("Weekend plans")
        app.buttons["newgroup.create"].tap()
        XCTAssertTrue(app.staticTexts["Hello everyone"].waitToExist(10))
        return app
    }

    /// The sample group shows two senders' names above their bubbles; Message info of my message lists both members.
    func testTheSampleGroupShowsTwoSendersAndMessageInfoListsBothMembers() {
        let app = openSampleGroup()
        let headers = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'chat.sender.'"))
        XCTAssertEqual(headers.count, 2, "one name and tile above each sender's first bubble")
        XCTAssertNotEqual(headers.element(boundBy: 0).label, headers.element(boundBy: 1).label)
        // No call buttons in a group.
        XCTAssertFalse(app.buttons["chat.call.audio"].exists)
        XCTAssertFalse(app.buttons["chat.call.video"].exists)

        app.staticTexts["Hello everyone"].press(forDuration: 1.0)
        let info = app.buttons["Message info"]
        XCTAssertTrue(info.waitToExist())
        info.tap()
        XCTAssertTrue(app.element("messageinfo.readBy").waitToExist())
        XCTAssertTrue(app.element("messageinfo.deliveredTo").exists)
        let rows = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'messageinfo.row.'"))
        XCTAssertEqual(rows.count, 2, "both members are listed")
        app.buttons["messageinfo.done"].tap()
        XCTAssertFalse(app.element("messageinfo.readBy").waitForExistence(timeout: 2))
    }

    /// Another member's message offers no Message info.
    func testMessageInfoIsOnlyForMyOwnMessages() {
        let app = openSampleGroup()
        app.staticTexts["Welcome to the group!"].press(forDuration: 1.0)
        XCTAssertTrue(app.buttons["Copy"].waitToExist())
        XCTAssertFalse(app.buttons["Message info"].exists)
    }
}
