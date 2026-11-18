/// What a display name may be. The same rule as Android's `DisplayNameRule` and the server's `ValidDisplayName`:
/// 1 to 40 code points after trimming, at least one visible character, no line or paragraph separators, no control
/// characters, no format characters (listed by code point, plus every other Cf one) except U+200C, U+200D and the
/// tag characters U+E0020...U+E007F that emoji sequences need. Unassigned code points count as visible, so the
/// Unicode table version of a platform does not change the result. Lengths count Unicode scalars (Kotlin's code
/// points), never grapheme clusters.
/// `forDisplay` cleans a name that came from somewhere else.
public enum DisplayNameRule {
    public static let max = 40

    /// The longest name the server stores.
    private static let serverMax = 64

    /// Format and direction characters refused by code point, whatever the platform's tables say.
    private static let refusedRanges: [ClosedRange<UInt32>] = [
        0x00AD...0x00AD, 0x0600...0x0605, 0x061C...0x061C, 0x06DD...0x06DD, 0x070F...0x070F, 0x0890...0x0891,
        0x08E2...0x08E2, 0x180E...0x180E, 0x200B...0x200B, 0x200E...0x200F, 0x202A...0x202E, 0x2060...0x2064,
        0x2066...0x206F, 0xFEFF...0xFEFF, 0xFFF9...0xFFFB, 0x110BD...0x110BD, 0x110CD...0x110CD,
        0x13430...0x1343F, 0x1BCA0...0x1BCA3, 0x1D173...0x1D17A, 0xE0001...0xE0001,
    ]

    /// Fillers that draw nothing: they do not count as a visible character.
    private static let invisible: Set<UInt32> = [
        0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x3164, 0x115F, 0x1160, 0xFFA0, 0x2800, 0x034F, 0x180E, 0x17B4, 0x17B5,
    ]

    /// Code points of the trimmed text: the "12/40" count in the editor.
    public static func length(_ input: String) -> Int {
        trimmed(Array(input.unicodeScalars)).count
    }

    /// The trimmed name, or `nil` when it is not acceptable.
    public static func clean(_ input: String) -> String? {
        let name = trimmed(Array(input.unicodeScalars))
        if name.isEmpty { return nil }
        if name.count > max { return nil }
        for s in name {
            if isRefused(s) { return nil }
        }
        guard hasVisible(name) else { return nil }
        var result = String.UnicodeScalarView()
        result.append(contentsOf: name)
        return String(result)
    }

    /// A name for showing, from data this device did not check: direction and format controls, line and paragraph
    /// breaks and control characters removed, trimmed, cut to 64 code points; `fallback` when nothing visible is left.
    public static func forDisplay(_ name: String, fallback: String) -> String {
        let kept = Array(name.unicodeScalars).filter { !isRefused($0) }
        var cut = trimmed(kept)
        if cut.count > serverMax { cut = trimmed(Array(cut.prefix(serverMax))) }
        guard hasVisible(cut) else { return fallback }
        var result = String.UnicodeScalarView()
        result.append(contentsOf: cut)
        return String(result)
    }

    private static func isRefused(_ s: Unicode.Scalar) -> Bool {
        let v = s.value
        if refusedRanges.contains(where: { $0.contains(v) }) { return true }
        switch s.properties.generalCategory {
        case .control, .lineSeparator, .paragraphSeparator: return true
        case .format: return !(v == 0x200C || v == 0x200D || (0xE0020...0xE007F).contains(v))
        default: return false
        }
    }

    /// Java's `isWhitespace || isSpaceChar`: space separators (NBSP included), line and paragraph separators, and the
    /// ASCII whitespace controls.
    private static func isTrimmable(_ s: Unicode.Scalar) -> Bool {
        switch s.value {
        case 0x09...0x0D, 0x1C...0x1F: return true
        default: break
        }
        switch s.properties.generalCategory {
        case .spaceSeparator, .lineSeparator, .paragraphSeparator: return true
        default: return false
        }
    }

    private static func trimmed(_ scalars: [Unicode.Scalar]) -> [Unicode.Scalar] {
        var start = 0
        var end = scalars.count
        while start < end, isTrimmable(scalars[start]) { start += 1 }
        while end > start, isTrimmable(scalars[end - 1]) { end -= 1 }
        return Array(scalars[start..<end])
    }

    private static func hasVisible(_ scalars: [Unicode.Scalar]) -> Bool {
        for s in scalars {
            if invisible.contains(s.value) { continue }
            switch s.properties.generalCategory {
            case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter,
                 .decimalNumber, .letterNumber, .otherNumber,
                 .connectorPunctuation, .dashPunctuation, .openPunctuation, .closePunctuation,
                 .initialPunctuation, .finalPunctuation, .otherPunctuation,
                 .mathSymbol, .currencySymbol, .modifierSymbol, .otherSymbol, .unassigned:
                return true
            default: continue
            }
        }
        return false
    }
}
