import XCTest
@testable import AppCore

final class MessageActionsTests: XCTestCase {
    private func message(_ content: MessageContent, status: DeliveryStatus = .sent, id: String = "m1") -> Message {
        Message(id: id, senderID: "x", sentAt: Date(timeIntervalSince1970: 0), content: content, status: status, replyToID: nil, reactions: [])
    }

    func testOwnText() {
        XCTAssertEqual(MessageActions.available(for: message(.text("hi")), isMine: true),
                       [.reply, .react, .copy, .forward, .edit, .delete])
    }

    func testOthersText() {
        XCTAssertEqual(MessageActions.available(for: message(.text("hi")), isMine: false),
                       [.reply, .react, .copy, .forward])
    }

    func testPhoto() {
        XCTAssertEqual(MessageActions.available(for: message(.photo(bytes: 10)), isMine: true), [.reply, .react, .delete])
        XCTAssertEqual(MessageActions.available(for: message(.photo(bytes: 10)), isMine: false), [.reply, .react])
    }

    func testOnlyACaptionedOwnPhotoOrFileCanBeEdited() {
        var photo = message(.photo(bytes: 10))
        photo.caption = "hi"
        XCTAssertEqual(MessageActions.available(for: photo, isMine: true), [.reply, .react, .copy, .edit, .delete])
        XCTAssertEqual(MessageActions.available(for: photo, isMine: false), [.reply, .react, .copy])
        var file = message(.file(name: "a.pdf", bytes: 10))
        file.caption = "hi"
        XCTAssertTrue(MessageActions.available(for: file, isMine: true).contains(.edit))
        photo.status = .failed
        XCTAssertEqual(MessageActions.available(for: photo, isMine: true), [.copy, .delete], "a failed upload keeps its caption")
    }

    func testNothingForDeletedSystemCallUnsupported() {
        let contents: [MessageContent] = [.deleted, .system(.safetyNumberChanged), .call(.missedAudio), .unsupported]
        for content in contents {
            XCTAssertEqual(MessageActions.available(for: message(content), isMine: true), [])
            XCTAssertEqual(MessageActions.available(for: message(content), isMine: false), [])
        }
    }

    func testFailedOwnText() {
        XCTAssertEqual(MessageActions.available(for: message(.text("hi"), status: .failed), isMine: true), [.copy, .delete])
    }

    // Review focus 3: the message changed under the menu.
    func testStillApplicableWhenMessageVanished() {
        XCTAssertFalse(MessageActions.isStillApplicable(.reply, current: nil, isMine: false))
        XCTAssertFalse(MessageActions.isStillApplicable(.delete, current: nil, isMine: true))
    }

    func testStillApplicableAfterRemoteDelete() {
        let gone = message(.deleted)
        for action in [MessageAction.reply, .react, .copy, .forward, .edit, .delete] {
            XCTAssertFalse(MessageActions.isStillApplicable(action, current: gone, isMine: true))
        }
    }

    func testEditNoLongerApplicableOnceSendFailed() {
        let failed = message(.text("hi"), status: .failed)
        XCTAssertFalse(MessageActions.isStillApplicable(.edit, current: failed, isMine: true))
        XCTAssertTrue(MessageActions.isStillApplicable(.edit, current: message(.text("hi")), isMine: true))
    }

    func testReactionGateDropsQuickRepeat() {
        var gate = ReactionGate()
        let t = Date(timeIntervalSince1970: 100)
        XCTAssertTrue(gate.allow(messageID: "m1", emoji: "👍", at: t))
        XCTAssertFalse(gate.allow(messageID: "m1", emoji: "👍", at: t.addingTimeInterval(0.2)))
        XCTAssertTrue(gate.allow(messageID: "m1", emoji: "❤️", at: t.addingTimeInterval(0.2)))
        XCTAssertTrue(gate.allow(messageID: "m2", emoji: "❤️", at: t.addingTimeInterval(0.3)))
        XCTAssertTrue(gate.allow(messageID: "m1", emoji: "❤️", at: t.addingTimeInterval(2)))
    }

    func testReactionEmoji() {
        XCTAssertEqual(MessageActions.reactionEmoji, ["👍", "❤️", "😂", "😮", "😢", "🙏"])
    }
}
