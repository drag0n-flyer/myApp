import Foundation

/// Display-only text formatting: `*bold*`, `_italic_`, `~strike~`, `` `mono` `` and a block between lines of three
/// backticks. The stored and sent text never changes. The same rules and test table as the Kotlin `MessageFormat`.
///
/// Span ranges count Swift `Character`s of the plain text (`plain(_:)`), that is, the text with the markers removed.
/// A nested span is its own span; the outer one covers the inner one.
public enum MessageFormat {
    public enum Style: Hashable, Sendable { case bold, italic, strike, mono, block }

    public struct Span: Equatable, Sendable {
        public var range: Range<Int>
        public var styles: Set<Style>

        public init(range: Range<Int>, styles: Set<Style>) {
            self.range = range
            self.styles = styles
        }
    }

    /// The spans of `text`, in the character offsets of `plain(text)`, ordered by start, longer first.
    public static func parse(_ text: String) -> [Span] { run(text).spans }

    /// `text` without the markers of valid spans; markers that do not form a span stay as typed.
    public static func plain(_ text: String) -> String { run(text).plain }

    /// The plain text with its styles and its links as an `AttributedString`, for a message bubble. Styles use the
    /// system's inline intents (bold, italic, strikethrough, code), so SwiftUI draws them with the system fonts.
    /// Links are detected on the plain text and are left out of monospace.
    public static func attributed(_ text: String) -> AttributedString {
        let parsed = run(text)
        var result = LinkDetector.attributed(parsed.plain)
        let chars = result.characters
        for span in parsed.spans {
            let slice = chars.index(chars.startIndex, offsetBy: span.range.lowerBound)..<chars.index(chars.startIndex, offsetBy: span.range.upperBound)
            if span.styles.contains(.mono) { result[slice].link = nil }
            #if canImport(Darwin)
            // The inline intents exist on Apple platforms only (the Linux Foundation used by the package tests lacks them).
            var intent: InlinePresentationIntent = []
            if span.styles.contains(.bold) { intent.insert(.stronglyEmphasized) }
            if span.styles.contains(.italic) { intent.insert(.emphasized) }
            if span.styles.contains(.strike) { intent.insert(.strikethrough) }
            if span.styles.contains(.mono) { intent.insert(.code) }
            result[slice].inlinePresentationIntent = (result[slice].inlinePresentationIntent ?? []).union(intent)
            #endif
        }
        return result
    }

    // MARK: Parser

    private static let markers: Set<Character> = ["*", "_", "~", "`"]
    private static let fence: [Character] = ["`", "`", "`"]

    private static func run(_ text: String) -> (plain: String, spans: [Span]) {
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false).map { Array($0) }
        var out: [Character] = []
        var spans: [Span] = []
        var i = 0
        var first = true
        while i < lines.count {
            if !first { out.append("\n") }
            first = false
            if lines[i] == fence, let close = closingFence(lines, after: i) {
                let start = out.count
                for k in (i + 1)..<close {
                    if k > i + 1 { out.append("\n") }
                    out.append(contentsOf: lines[k])
                }
                spans.append(Span(range: start..<out.count, styles: [.mono, .block]))
                i = close + 1
                continue
            }
            let base = out.count
            let result = inline(lines[i], mask: linkMask(lines[i]))
            out.append(contentsOf: result.plain)
            spans.append(contentsOf: result.spans.map { shifted($0, by: base) })
            i += 1
        }
        spans.sort { $0.range.lowerBound != $1.range.lowerBound ? $0.range.lowerBound < $1.range.lowerBound : $0.range.upperBound > $1.range.upperBound }
        return (String(out), spans)
    }

    private static func shifted(_ span: Span, by n: Int) -> Span {
        Span(range: (span.range.lowerBound + n)..<(span.range.upperBound + n), styles: span.styles)
    }

    /// The line of the closing fence, when there is at least one non-empty line between the fences.
    private static func closingFence(_ lines: [[Character]], after open: Int) -> Int? {
        var k = open + 1
        while k < lines.count {
            if lines[k] == fence {
                return lines[(open + 1)..<k].contains(where: { !$0.isEmpty }) ? k : nil
            }
            k += 1
        }
        return nil
    }

    /// True for the characters of a web link (a word that starts with http:// or https://) without its trailing
    /// punctuation and markers, so a marker inside a link is never read.
    private static func linkMask(_ c: [Character]) -> [Bool] {
        var mask = [Bool](repeating: false, count: c.count)
        var i = 0
        while i < c.count {
            if c[i].isWhitespace { i += 1; continue }
            var end = i
            while end < c.count, !c[end].isWhitespace { end += 1 }
            let word = String(c[i..<end]).lowercased()
            if word.hasPrefix("http://") || word.hasPrefix("https://") {
                var stop = end
                while stop > i, "*_~`.,;:!?)".contains(c[stop - 1]) { stop -= 1 }
                for k in i..<stop { mask[k] = true }
            }
            i = end
        }
        return mask
    }

    /// Whitespace or punctuation (Unicode punctuation, and ASCII symbols such as `~` and `` ` ``).
    private static func isBoundary(_ ch: Character) -> Bool {
        if ch.isWhitespace || ch.isPunctuation { return true }
        guard let a = ch.asciiValue else { return false }
        return (33...47).contains(a) || (58...64).contains(a) || (91...96).contains(a) || (123...126).contains(a)
    }

    /// One line (no newline inside). Offsets in the result count the plain characters.
    private static func inline(_ c: [Character], mask: [Bool]) -> (plain: [Character], spans: [Span]) {
        var out: [Character] = []
        var spans: [Span] = []
        var i = 0
        while i < c.count {
            let m = c[i]
            if markers.contains(m), !mask[i], let close = closing(c, mask, open: i) {
                let inner = Array(c[(i + 1)..<close])
                let start = out.count
                if m == "`" {
                    out.append(contentsOf: inner)
                    spans.append(Span(range: start..<out.count, styles: [.mono]))
                } else {
                    let result = inline(inner, mask: Array(mask[(i + 1)..<close]))
                    out.append(contentsOf: result.plain)
                    let style: Style = m == "*" ? .bold : m == "_" ? .italic : .strike
                    spans.append(Span(range: start..<out.count, styles: [style]))
                    spans.append(contentsOf: result.spans.map { shifted($0, by: start) })
                }
                i = close + 1
            } else {
                out.append(m)
                i += 1
            }
        }
        return (out, spans)
    }

    /// The index of the closing marker for the opening one at `open`, if rules 1 and 2 hold (a line has no newline).
    private static func closing(_ c: [Character], _ mask: [Bool], open: Int) -> Int? {
        let m = c[open]
        if open > 0, !isBoundary(c[open - 1]) { return nil }
        // Followed by a non-space; a doubled marker ("**") is not an opening, so an empty pair stays as typed.
        guard open + 1 < c.count, !c[open + 1].isWhitespace, c[open + 1] != m else { return nil }
        var j = open + 2
        while j < c.count {
            if c[j] == m, !mask[j], !c[j - 1].isWhitespace, j + 1 == c.count || isBoundary(c[j + 1]) { return j }
            j += 1
        }
        return nil
    }
}

/// Moving through the results of a search inside a chat. Results are newest first: Next goes to an older one and
/// wraps from the oldest to the newest; Previous goes the other way.
public enum ChatSearch {
    public enum Direction: Sendable { case next, previous }

    /// The index to show after a move, or nil when there are no results. With no current result (or one that is out
    /// of range), Next gives the newest and Previous the oldest.
    public static func step<T>(_ results: [T], current: Int?, direction: Direction) -> Int? {
        guard !results.isEmpty else { return nil }
        guard let current, results.indices.contains(current) else {
            return direction == .next ? 0 : results.count - 1
        }
        switch direction {
        case .next: return (current + 1) % results.count
        case .previous: return (current - 1 + results.count) % results.count
        }
    }
}
