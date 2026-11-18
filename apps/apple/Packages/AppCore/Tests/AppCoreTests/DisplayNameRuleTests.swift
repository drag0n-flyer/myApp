import Testing
@testable import AppCore

private func scalar(_ cp: UInt32) -> String { String(Unicode.Scalar(cp)!) }

@Test func nameLengthCountsTrimmedCodePoints() {
    #expect(DisplayNameRule.length("  Asha  ") == 4)
    #expect(DisplayNameRule.length("😀😀") == 2)
    #expect(DisplayNameRule.length("") == 0)
    #expect(DisplayNameRule.length(String(repeating: "a", count: 45)) == 45)
}

@Test func nameTrims() { #expect(DisplayNameRule.clean("  Asha  ") == "Asha") }

@Test func nameKeepsInnerSpacesAndUnicode() {
    #expect(DisplayNameRule.clean("Ana María") == "Ana María")
    #expect(DisplayNameRule.clean("李小龍") == "李小龍")
}

@Test func nameRefusesEmptyAndOnlySpaces() {
    #expect(DisplayNameRule.clean("") == nil)
    #expect(DisplayNameRule.clean("    ") == nil)
    #expect(DisplayNameRule.clean("\u{00A0}\u{00A0}") == nil)   // NBSP is trimmed like Kotlin's isSpaceChar
    #expect(DisplayNameRule.clean("\u{00A0}Asha\u{00A0}") == "Asha")
}

@Test func nameLimitIsFortyCodePointsNotGraphemes() {
    #expect(DisplayNameRule.clean(String(repeating: "a", count: 40)) == String(repeating: "a", count: 40))
    #expect(DisplayNameRule.clean(String(repeating: "a", count: 41)) == nil)
    let faces = String(repeating: "😀", count: 40)
    #expect(DisplayNameRule.clean(faces) == faces)
    #expect(DisplayNameRule.clean(faces + "a") == nil)
    #expect(DisplayNameRule.clean("  " + String(repeating: "a", count: 41) + "  ") == nil)
    #expect(DisplayNameRule.clean("  " + String(repeating: "a", count: 40) + "  ") == String(repeating: "a", count: 40))
    // One grapheme cluster of 41 code points (a letter plus 40 combining marks) is still too long.
    #expect(DisplayNameRule.clean("e" + String(repeating: "\u{0301}", count: 40)) == nil)
    #expect(DisplayNameRule.clean("e" + String(repeating: "\u{0301}", count: 39)) != nil)
}

@Test func nameRefusesLineBreaksAndControlCharacters() {
    for c in ["\n", "\r", "\u{0085}", "\u{2028}", "\u{2029}", "\t", "\u{0000}", "\u{001B}", "\u{007F}"] {
        #expect(DisplayNameRule.clean("A\(c)B") == nil, "\(c.unicodeScalars.first!.value) inside")
    }
    #expect(DisplayNameRule.clean("Ana\nMaría") == nil)
}

@Test func nameRefusesDirectionAndFormatControls() {
    let bad: [UInt32] = Array(0x202A...0x202E) + Array(0x2066...0x2069) + [0x200E, 0x200F, 0x061C]
    for cp in bad {
        let c = scalar(cp)
        #expect(DisplayNameRule.clean("A\(c)B") == nil, "U+\(String(cp, radix: 16)) inside")
        #expect(DisplayNameRule.clean("\(c)AB") == nil, "U+\(String(cp, radix: 16)) leading")
    }
    #expect(DisplayNameRule.clean("Asha\u{202E}") == nil)
}

@Test func nameRefusesNamesWithNoVisibleCharacter() {
    let fillers: [UInt32] = [0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x3164, 0x115F, 0x1160, 0xFFA0, 0x2800, 0x034F]
    for cp in fillers {
        let c = scalar(cp)
        #expect(DisplayNameRule.clean(c) == nil, "U+\(String(cp, radix: 16)) alone")
        #expect(DisplayNameRule.clean(" \(c)\(c) ") == nil, "U+\(String(cp, radix: 16)) repeated")
    }
}

@Test func nameAcceptsVisibleThingsAndKeepsInvisibleOnesBesideThem() {
    #expect(DisplayNameRule.clean("A") == "A")
    #expect(DisplayNameRule.clean("7") == "7")
    #expect(DisplayNameRule.clean("!") == "!")
    #expect(DisplayNameRule.clean("\u{00A9}") == "\u{00A9}")
    #expect(DisplayNameRule.clean("😀") == "😀")
    #expect(DisplayNameRule.clean("A\u{200B}B") == nil)   // hidden format characters are refused inside a name
    #expect(DisplayNameRule.clean("A\u{200D}B") == "A\u{200D}B")
    let family = "\u{1F468}\u{200D}\u{1F469}\u{200D}\u{1F467}"
    #expect(DisplayNameRule.clean(family) == family)
}

@Test func nameUnassignedCodePointsAreNotRefused() {
    #expect(DisplayNameRule.clean("A\u{0378}B") == "A\u{0378}B")
}

@Test func forDisplayRemovesDirectionAndBreakControls() {
    #expect(DisplayNameRule.forDisplay("Ana\u{202E}xyz", fallback: "fb") == "Anaxyz")
    #expect(DisplayNameRule.forDisplay("A\u{2066}\u{2069}\u{200E}\u{200F}\u{061C}B", fallback: "fb") == "AB")
    #expect(DisplayNameRule.forDisplay("A\u{2028}\u{2029}\nB\u{0000}", fallback: "fb") == "AB")
    #expect(DisplayNameRule.forDisplay("  Asha  ", fallback: "fb") == "Asha")
}

@Test func forDisplayFallsBackWhenNothingVisibleRemains() {
    #expect(DisplayNameRule.forDisplay("", fallback: "riya") == "riya")
    #expect(DisplayNameRule.forDisplay("\u{202E}\u{200B}\u{3164} ", fallback: "riya") == "riya")
    #expect(DisplayNameRule.forDisplay("\n\u{2028}", fallback: "riya") == "riya")
}

@Test func forDisplayLeavesANormalNameAlone() {
    #expect(DisplayNameRule.forDisplay("Ana María 😀 O\u{2019}Neil", fallback: "fb") == "Ana María 😀 O\u{2019}Neil")
}

@Test func forDisplayCutsToSixtyFourCodePoints() {
    #expect(DisplayNameRule.forDisplay(String(repeating: "😀", count: 70), fallback: "fb") == String(repeating: "😀", count: 64))
    #expect(DisplayNameRule.forDisplay(String(repeating: "a", count: 100), fallback: "fb") == String(repeating: "a", count: 64))
}
