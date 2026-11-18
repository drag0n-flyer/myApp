import XCTest

@MainActor
final class GroupInfoTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private func label(of element: XCUIElement) -> String { "\(element.label) \(element.value as? String ?? "")" }

    private func openInfo(of chatID: String, in app: XCUIApplication) {
        let row = app.element("chatlist.row.\(chatID)")
        XCTAssertTrue(row.scrollToExist(in: app))   // a group with no messages sorts below the fold
        row.tap()
        XCTAssertTrue(app.element("chat.header").waitToExist())
        app.element("chat.header").tap()
        XCTAssertTrue(app.element("groupinfo.name").waitToExist())
    }

    /// A group where the person is a plain member: the member list shows each role, "Add members" is absent, and
    /// Leave group asks first.
    func testAPlainMemberSeesRolesNoAddAndAConfirmationOnLeave() {
        let app = XCUIApplication.launched(extra: ["-sampleMemberGroup"])
        openInfo(of: "g:sample-members", in: app)

        XCTAssertTrue(label(of: app.element("groupinfo.member.riya")).contains("Riya, Owner"))
        XCTAssertTrue(label(of: app.element("groupinfo.member.arjun")).contains("Arjun, Admin"))
        XCTAssertTrue(label(of: app.element("groupinfo.member.sam")).contains("Sam, Member"))
        XCTAssertTrue(label(of: app.element("groupinfo.member.me")).contains("You, Member"))
        XCTAssertFalse(app.element("groupinfo.add").exists)
        XCTAssertFalse(app.element("groupinfo.edit").exists)
        XCTAssertFalse(app.element("chatinfo.timer").exists)   // owner and admins only

        let leave = app.element("groupinfo.leave")
        XCTAssertTrue(leave.scrollToExist(in: app))
        leave.tap()
        XCTAssertTrue(app.staticTexts["Leave this group?"].waitToExist())
        app.buttons["Cancel"].firstMatch.tap()
        XCTAssertTrue(app.element("groupinfo.leave").waitToExist())
    }

    /// A group the person made: "Add members" and the edit button show, and the owner is asked to choose a new owner
    /// before leaving.
    func testAnOwnerSeesAddAndMustChooseANewOwnerBeforeLeaving() {
        let app = XCUIApplication.launched()
        app.element("chatlist.compose").tap()
        app.element("newchat.newgroup").tap()
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
        XCTAssertTrue(app.element("chat.header").waitToExist(10))
        app.element("chat.header").tap()
        XCTAssertTrue(app.element("groupinfo.name").waitToExist())

        XCTAssertTrue(label(of: app.element("groupinfo.member.me")).contains("You, Owner"))
        XCTAssertTrue(app.element("groupinfo.add").exists)
        XCTAssertTrue(app.element("groupinfo.edit").exists)
        XCTAssertTrue(app.element("chatinfo.timer").exists)
        XCTAssertTrue(label(of: app.element("groupinfo.count")).contains("3 members"))

        let leave = app.element("groupinfo.leave")
        XCTAssertTrue(leave.scrollToExist(in: app))
        leave.tap()
        XCTAssertTrue(app.staticTexts["Leave this group?"].waitToExist())
        let confirm = app.buttons["groupinfo.leave.confirm"]
        XCTAssertTrue(confirm.waitToExist())
        confirm.tap()
        XCTAssertTrue(app.staticTexts["Choose a new owner before you leave."].waitToExist())
    }
}
