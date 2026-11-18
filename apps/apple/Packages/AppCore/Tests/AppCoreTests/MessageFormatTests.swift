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

    func testCrossingMarkersFirstPairToCloseWins() {
        check("*a _b* c_", plain: "a _b c_", [(0..<4, [.bold])])
        check("~~a~~", plain: "~~a~~")
    }

    func testRunsOfOneMarkerDoNotClose() {
        check("*a**", plain: "*a**")
        check("~a~~b~", plain: "a~~b", [(0..<4, [.strike])])
        check("`a``b`", plain: "a``b", [(0..<4, [.mono])])
    }

    func testCRLFAndCRAreLineBreaks() {
        check("*a\r\nb*", plain: "*a\r\nb*")
        check("*a\rb*", plain: "*a\rb*")
        check("*a*\r\n_b_", plain: "a\r\nb", [(0..<1, [.bold]), (2..<3, [.italic])])
        check("x\r\n```\r\n*a*\r\n```\r\ny", plain: "x\r\n*a*\r\ny", [(2..<5, [.mono, .block])])
    }

    func testMonospaceIsFoundFirst() {
        check("*a `b* c` d*", plain: "a b* c d", [(0..<8, [.bold]), (2..<6, [.mono])])
    }

    func testACloserPairsWithTheInnermostOpenMarkerOfItsKind() {
        check("*a *b* c*", plain: "a b c", [(0..<5, [.bold]), (2..<3, [.bold])])
    }

    func testBlockOfBlankLinesIsNotABlock() {
        check("```\n  \n```", plain: "```\n  \n```")
    }

    // MARK: Hostile input (received text is not limited by the sender's rules)

    private func fast(_ text: String, file: StaticString = #filePath, line: UInt = #line) {
        let start = Date()
        _ = MessageFormat.parse(text)
        _ = MessageFormat.plain(text)
        _ = MessageFormat.attributed(text)
        XCTAssertLessThan(Date().timeIntervalSince(start), 1.0, "took too long for \(text.count) characters", file: file, line: line)
    }

    func testSixtyFourKilobytesOfHostileTextIsFast() {
        for unit in [" *a", "*a* ", "*_a_* ", "`a` ", " _a", "~a~ "] {
            fast(String(repeating: unit, count: 65_536 / unit.count))
        }
    }

    func testTextAtTheLimitOfHostileShapesIsFast() {
        for unit in [" *a", "*a* ", "*_a_* ", " _a", "*a _b* c_ "] {
            fast(String(repeating: unit, count: MessageFormat.maxLength / unit.count))
        }
    }

    func testTextOverTheLimitIsNotFormatted() {
        let long = "*a* " + String(repeating: "b", count: MessageFormat.maxLength)
        XCTAssertEqual(MessageFormat.plain(long), long)
        XCTAssertTrue(MessageFormat.parse(long).isEmpty)
        XCTAssertEqual(String(MessageFormat.attributed(long).characters), long)
        let atLimit = "*a* " + String(repeating: "b", count: MessageFormat.maxLength - 4)
        XCTAssertEqual(MessageFormat.parse(atLimit).count, 1)
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
