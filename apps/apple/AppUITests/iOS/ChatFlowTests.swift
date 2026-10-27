import XCTest

final class ChatFlowTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testFoldersFilterAndStrip() {
        let app = XCUIApplication.launched(network: "mobile")
        let strip = app.element("chatlist.strip")
        XCTAssertTrue(strip.waitToExist())
        XCTAssertTrue(strip.label.contains("Data saver"))
        XCTAssertEqual(app.element("chatlist.requests").label, "Message requests · 1")

        app.element("chatlist.chip.unread").tap()
        let rows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'chatlist.row.'"))
        XCTAssertEqual(rows.count, 1)
        XCTAssertTrue(app.element("chatlist.row.chat-riya").exists)
    }

    func testSendMessage() {
        let app = XCUIApplication.launched()
        app.element("chatlist.row.chat-riya").tap()
        let field = app.textFields["chat.composer.field"].exists ? app.textFields["chat.composer.field"] : app.textViews["chat.composer.field"]
        XCTAssertTrue(field.waitToExist())
        field.tap()
        field.typeText("Hello")
        app.element("chat.composer.send").tap()
        XCTAssertTrue(app.staticTexts["Hello"].waitToExist())
        XCTAssertFalse(app.element("chat.composer.send").exists, "Send turns back into the mic when the field is empty")
    }

    func testPhotoNotAutoDownloadedOnMobile() {
        let mobile = XCUIApplication.launched(network: "mobile")
        mobile.element("chatlist.row.chat-meera").tap()
        XCTAssertTrue(mobile.buttons["Photo · 240 KB"].waitToExist())
        mobile.terminate()

        let wifi = XCUIApplication.launched(network: "wifi")
        wifi.element("chatlist.row.chat-meera").tap()
        XCTAssertTrue(wifi.element("chat.header").waitToExist())
        XCTAssertFalse(wifi.buttons["Photo · 240 KB"].exists)
    }

    func testEncryptionOnlyInInfo() {
        let app = XCUIApplication.launched()
        app.element("chatlist.chip.all").tap()
        app.element("chatlist.row.chat-sam").tap()
        XCTAssertTrue(app.element("chat.header").waitToExist())
        let encryptedText = app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] 'encrypted'"))
        XCTAssertEqual(encryptedText.count, 0)
        app.element("chat.header").tap()
        let info = app.element("chatinfo.encryption")
        XCTAssertTrue(info.waitToExist())
        XCTAssertEqual(info.label, "Not end-to-end encrypted: the server admin can read this chat")
    }

    func testAcceptRequest() {
        let app = XCUIApplication.launched()
        app.element("chatlist.requests").tap()
        app.element("requests.row.chat-stranger1").tap()
        app.element("requests.accept").tap()
        XCTAssertTrue(app.element("chat.composer.field").waitToExist())
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.element("chatlist.row.chat-stranger1").waitToExist())
        XCTAssertFalse(app.element("chatlist.requests").exists)
    }

    func testAddContactByUsername() {
        let app = XCUIApplication.launched()
        app.tab("Contacts").tap()
        app.element("contacts.add").tap()
        let field = app.textFields["addcontact.field"]
        XCTAssertTrue(field.waitToExist())
        field.tap()
        field.typeText("neha")
        app.element("addcontact.submit").tap()
        XCTAssertTrue(app.element("contacts.row.neha").waitToExist())
    }
}
