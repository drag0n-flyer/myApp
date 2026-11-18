import XCTest
@testable import AppCore

final class MessageFormatTests: XCTestCase {
    private func check(_ input: String, plain: String, _ spans: [(Range<Int>, Set<MessageFormat.Style>)] = [],
                       file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(MessageFormat.plain(input), plain, file: file, line: line)
        let got = MessageFormat.parse(input).map { "\($0.range)|\($0.styles.map { "\($0)" }.sorted())" }
        let want = spans.map { "\($0.0)|\($0.1.map { "\($0)" }.sorted())" }
        XCTAssertEqual(got, want, "spans of \(input.debugDescription)", file: file, line: line)
    }

    func testBold() { check("*a*", plain: "a", [(0..<1, [.bold])]) }
    func testItalic() { check("_a_", plain: "a", [(0..<1, [.italic])]) }
    func testStrike() { check("~a~", plain: "a", [(0..<1, [.strike])]) }
    func testMonoOnly() { check("`a*b*`", plain: "a*b*", [(0..<4, [.mono])]) }
    func testNested() { check("*a _b_ c*", plain: "a b c", [(0..<5, [.bold]), (2..<3, [.italic])]) }
    func testWithinWords() {
        for text in ["2*3*4", "snake_case_name", "a * b * c", "*a", "*a\nb*", "**", "a ** b", "**a**", "```a```", "``` a ```"] {
            check(text, plain: text)
        }
    }
    func testPunctuationNeighbours() {
        check("(*a*)", plain: "(a)", [(1..<2, [.bold])])
        check("*a*,", plain: "a,", [(0..<1, [.bold])])
    }
    func testBlockKeepsInnerTextVerbatim() {
        check("x\n```\n*a*\n_b_\n```\ny", plain: "x\n*a*\n_b_\ny", [(2..<9, [.mono, .block])])
    }
    func testUnclosedOrEmptyBlockIsAsTyped() {
        check("```\nabc", plain: "```\nabc")
        check("```\n```", plain: "```\n```")
    }
    func testLinkStaysWhole() {
        check("*a* https://x.example/_b_", plain: "a https://x.example/_b_", [(0..<1, [.bold])])
    }
    func testMarkedTextAroundLink() {
        check("*see https://x.example*", plain: "see https://x.example", [(0..<21, [.bold])])
    }
    func testEmojiAndCombinedCharactersCountAsCharacters() {
        check("*👨‍👩‍👧 e\u{301}*", plain: "👨‍👩‍👧 e\u{301}", [(0..<3, [.bold])])
        check("é _x_", plain: "é x", [(2..<3, [.italic])])
    }
    func testTextWithoutMarkersIsUnchanged() {
        check("hello world", plain: "hello world")
        check("", plain: "")
    }

    func testAttributedStyles() {
        let text = MessageFormat.attributed("*a* `b`")
        XCTAssertEqual(String(text.characters), "a b")
        #if canImport(Darwin)
        let intents = text.runs.map { $0.inlinePresentationIntent }
        XCTAssertTrue(intents.contains(.stronglyEmphasized))
        XCTAssertTrue(intents.contains(.code))
        #endif
    }
    func testLinksStayOutsideMono() {
        let text = MessageFormat.attributed("`https://x.example/a` and https://y.example/b")
        XCTAssertEqual(text.runs.compactMap(\.link).map(\.absoluteString), ["https://y.example/b"])
    }
    func testLinkInBoldStaysLinked() {
        let text = MessageFormat.attributed("*see https://x.example*")
        XCTAssertEqual(text.runs.compactMap(\.link).map(\.absoluteString), ["https://x.example"])
    }
    func testPreviewShowsPlainAndStoredTextIsKept() {
        let typed = "*bold* and 2*3*4"
        let message = Message(id: "1", senderID: "a", sentAt: .distantPast, content: .text(typed), status: .sent, replyToID: nil, reactions: [])
        XCTAssertEqual(message.previewText, "bold and 2*3*4")
        if case .text(let stored) = message.content { XCTAssertEqual(stored, typed) } else { XCTFail() }
    }
    func testNotificationBodyShowsPlain() {
        let plan = NotificationPlan.content(chatID: "c", sender: "Ann", text: "*hi* there", muted: false, appLockOn: false)
        XCTAssertEqual(plan.body, "hi there")
    }
}

final class ChatSearchTests: XCTestCase {
    func testNextAndPreviousWrap() {
        let r = ["a", "b", "c"]
        XCTAssertEqual(ChatSearch.step(r, current: 0, direction: .next), 1)
        XCTAssertEqual(ChatSearch.step(r, current: 2, direction: .next), 0)
        XCTAssertEqual(ChatSearch.step(r, current: 0, direction: .previous), 2)
        XCTAssertEqual(ChatSearch.step(r, current: 2, direction: .previous), 1)
    }
    func testNoCurrentStartsAtTheEnds() {
        XCTAssertEqual(ChatSearch.step(["a", "b"], current: nil, direction: .next), 0)
        XCTAssertEqual(ChatSearch.step(["a", "b"], current: nil, direction: .previous), 1)
        XCTAssertEqual(ChatSearch.step(["a", "b"], current: 9, direction: .next), 0)
    }
    func testEmptyAndSingle() {
        XCTAssertNil(ChatSearch.step([String](), current: nil, direction: .next))
        XCTAssertEqual(ChatSearch.step(["a"], current: 0, direction: .next), 0)
        XCTAssertEqual(ChatSearch.step(["a"], current: 0, direction: .previous), 0)
    }
}
