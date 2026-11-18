/// What a display name may be. The same rule as Android's `DisplayNameRule`: 1 to 40 code points after trimming, at
/// least one visible character, no line or paragraph separators, no control characters, no direction or format
/// controls. Lengths count Unicode scalars (Kotlin's code points), never grapheme clusters.
/// `forDisplay` cleans a name that came from somewhere else.
public enum DisplayNameRule {
    public static let max = 40

    /// The longest name the server stores.
    private static let serverMax = 64

    private static let directionControls: Set<UInt32> = [
        0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0x200E, 0x200F, 0x061C,
    ]

    /// Fillers that draw nothing: they do not count as a visible character.
    private static let invisible: Set<UInt32> = [
        0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x3164, 0x115F, 0x1160, 0xFFA0, 0x2800, 0x034F, 0x180E, 0x17B4, 0x17B5,
    ]

    /// The trimmed name, or `nil` when it is not acceptable.
    public static func clean(_ input: String) -> String? {
        let name = trimmed(Array(input.unicodeScalars))
        if name.isEmpty { return nil }
        if name.count > max { return nil }
        for s in name {
            if directionControls.contains(s.value) || isBreakOrControl(s) { return nil }
        }
        guard hasVisible(name) else { return nil }
        var result = String.UnicodeScalarView()
        result.append(contentsOf: name)
        return String(result)
    }

    /// A name for showing, from data this device did not check: direction and format controls, line and paragraph
    /// breaks and control characters removed, trimmed, cut to 64 code points; `fallback` when nothing visible is left.
    public static func forDisplay(_ name: String, fallback: String) -> String {
        let kept = Array(name.unicodeScalars).filter { !directionControls.contains($0.value) && !isBreakOrControl($0) }
        var cut = trimmed(kept)
        if cut.count > serverMax { cut = trimmed(Array(cut.prefix(serverMax))) }
        guard hasVisible(cut) else { return fallback }
        var result = String.UnicodeScalarView()
        result.append(contentsOf: cut)
        return String(result)
    }

    private static func isBreakOrControl(_ s: Unicode.Scalar) -> Bool {
        switch s.properties.generalCategory {
        case .control, .lineSeparator, .paragraphSeparator: true
        default: false
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
                 .mathSymbol, .currencySymbol, .modifierSymbol, .otherSymbol:
                return true
            default: continue
            }
        }
        return false
    }
}
