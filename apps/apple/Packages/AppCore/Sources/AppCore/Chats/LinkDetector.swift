import Foundation

public enum LinkDetector {
    /// `text` with web and e-mail links marked, so the system opens them when tapped.
    public static func attributed(_ text: String) -> AttributedString {
        var result = AttributedString(text)
        // Direction controls anywhere in the message can change how a link is displayed.
        if text.unicodeScalars.contains(where: isDirectionControl) { return result }
        for (range, url) in links(in: text) {
            guard let scheme = url.scheme?.lowercased(), ["http", "https", "mailto"].contains(scheme),
                  let lower = AttributedString.Index(range.lowerBound, within: result),
                  let upper = AttributedString.Index(range.upperBound, within: result) else { continue }
            result[lower..<upper].link = url
        }
        return result
    }

    /// U+202A-202E, U+2066-2069, U+200E, U+200F, U+061C.
    private static func isDirectionControl(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x202A...0x202E, 0x2066...0x2069, 0x200E, 0x200F, 0x061C: true
        default: false
        }
    }

    #if canImport(Darwin)
    private static func links(in text: String) -> [(Range<String.Index>, URL)] {
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else { return [] }
        let length = (text as NSString).length
        return detector.matches(in: text, range: NSRange(location: 0, length: length)).compactMap { match in
            guard let url = match.url, let range = Range(match.range, in: text) else { return nil }
            return (range, url)
        }
    }
    #else
    /// Linux (tests only): `NSDataDetector` is not available, so find http(s) words by hand.
    private static func links(in text: String) -> [(Range<String.Index>, URL)] {
        var found: [(Range<String.Index>, URL)] = []
        var start = text.startIndex
        while start < text.endIndex {
            var end = start
            while end < text.endIndex, !text[end].isWhitespace { end = text.index(after: end) }
            let word = text[start..<end]
            if word.lowercased().hasPrefix("http://") || word.lowercased().hasPrefix("https://"), let url = URL(string: String(word)) {
                found.append((start..<end, url))
            }
            start = end < text.endIndex ? text.index(after: end) : end
        }
        return found
    }
    #endif
}

/// Remembers whose screen state it is. When another account signs in, the state is dropped.
public struct OwnerGuard: Sendable {
    public private(set) var ownerID: String?

    public init() {}

    /// True when `id` differs from the current owner, so the caller must reset what it holds.
    public mutating func switchTo(_ id: String) -> Bool {
        guard ownerID != id else { return false }
        ownerID = id
        return true
    }

    public mutating func clear() { ownerID = nil }

    public func isOwner(_ id: String) -> Bool { ownerID == id }
}
