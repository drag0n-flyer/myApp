import XCTest

@MainActor
final class GroupCreateTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// New chat > New group: two people are needed, then a name; the sample group shows in the chat list with its name.
    func testCreatingASampleGroupShowsItInTheChatList() {
        let app = XCUIApplication.launched()
        app.element("chatlist.compose").tap()
        let newGroup = app.element("newchat.newgroup")
        XCTAssertTrue(newGroup.waitToExist())
        newGroup.tap()

        // Fewer than 2 people: the hint shows and Next is off.
        XCTAssertTrue(app.element("newgroup.hint").waitToExist())
        let next = app.buttons["newgroup.next"]
        XCTAssertTrue(next.waitToExist())
        XCTAssertFalse(next.isEnabled)

        let people = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'newgroup.person.'"))
        XCTAssertTrue(people.element(boundBy: 1).waitToExist())
        people.element(boundBy: 0).tap()
        XCTAssertFalse(next.isEnabled)
        people.element(boundBy: 1).tap()
        XCTAssertTrue(next.isEnabled)
        next.tap()

        let name = app.textFields["newgroup.name"]
        XCTAssertTrue(name.waitToExist())
        let create = app.buttons["newgroup.create"]
        XCTAssertFalse(create.isEnabled)
        name.tap()
        name.typeText("Weekend plans")
        XCTAssertTrue(create.isEnabled)
        create.tap()

        // The new chat opens; going back, the list has a row with the group's name.
        XCTAssertTrue(app.staticTexts["Weekend plans"].waitToExist(10))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let row = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS 'Weekend plans'")).firstMatch
        XCTAssertTrue(row.waitToExist(10))
    }
}
