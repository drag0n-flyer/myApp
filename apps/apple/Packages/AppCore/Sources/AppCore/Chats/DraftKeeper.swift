import Foundation

/// Unsent composer text per chat, kept for the life of the process.
public struct DraftKeeper: Sendable {
    private var drafts: [String: String] = [:]

    public init() {}

    public mutating func set(_ text: String, for chatID: String) {
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            drafts[chatID] = nil
        } else {
            drafts[chatID] = text
        }
    }

    public func draft(for chatID: String) -> String { drafts[chatID] ?? "" }
}

public enum LinkDetector {
    /// `text` with web and e-mail links marked, so the system opens them when tapped.
    public static func attributed(_ text: String) -> AttributedString {
        var result = AttributedString(text)
        for (range, url) in links(in: text) {
            guard let scheme = url.scheme?.lowercased(), ["http", "https", "mailto"].contains(scheme),
                  let lower = AttributedString.Index(range.lowerBound, within: result),
                  let upper = AttributedString.Index(range.upperBound, within: result) else { continue }
            result[lower..<upper].link = url
        }
        return result
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
