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
    /// Links are detected on the plain text and are left out of monospace. Kept in a small cache, so a redraw does
    /// not parse again.
    public static func attributed(_ text: String) -> AttributedString {
        if let hit = cache.attributed(text) { return hit }
        let parsed = run(text)
        var result = LinkDetector.attributed(parsed.plain)
        // One walk over the characters gives every offset its index, so spans do not each count from the start.
        let chars = result.characters
        var indices: [AttributedString.Index] = []
        indices.reserveCapacity(chars.count + 1)
        var at = chars.startIndex
        while at < chars.endIndex { indices.append(at); at = chars.index(after: at) }
        indices.append(chars.endIndex)
        for span in parsed.spans where span.range.upperBound < indices.count {
            let slice = indices[span.range.lowerBound]..<indices[span.range.upperBound]
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
        cache.store(attributed: result, for: text)
        return result
    }

    /// Longer text is shown as typed (the same limit as sending), so a huge received message cannot freeze the chat.
    public static let maxLength = 4096

    /// A small bounded cache of parse results and attributed text, keyed by the text itself.
    private final class Cache: @unchecked Sendable {
        private let lock = NSLock()
        private var parsedByText: [String: (plain: String, spans: [Span])] = [:]
        private var styledByText: [String: AttributedString] = [:]
        private let limit = 256

        func parsed(_ text: String) -> (plain: String, spans: [Span])? { lock.withLock { parsedByText[text] } }
        func attributed(_ text: String) -> AttributedString? { lock.withLock { styledByText[text] } }
        func store(parsed value: (plain: String, spans: [Span]), for text: String) {
            lock.withLock {
                if parsedByText.count >= limit { parsedByText.removeAll() }
                parsedByText[text] = value
            }
        }
        func store(attributed value: AttributedString, for text: String) {
            lock.withLock {
                if styledByText.count >= limit { styledByText.removeAll() }
                styledByText[text] = value
            }
        }
    }

    private static let cache = Cache()

    // MARK: Parser

    private static let markers: Set<Character> = ["*", "_", "~", "`"]
    private static let fence: [Character] = ["`", "`", "`"]

    private static func run(_ text: String) -> (plain: String, spans: [Span]) {
        // No marker character at all, or too long to be worth it: shown as typed, nothing to cache.
        guard text.contains(where: { markers.contains($0) }), text.count <= maxLength else { return (text, []) }
        if let hit = cache.parsed(text) { return hit }
        let result = parseLines(text)
        cache.store(parsed: result, for: text)
        return result
    }

    /// "\n", "\r" and "\r\n" (one Character in Swift) end a line; the original separators are kept in the plain text.
    private static func isLineBreak(_ ch: Character) -> Bool { ch == "\n" || ch == "\r" || ch == "\r\n" }

    private static func parseLines(_ text: String) -> (plain: String, spans: [Span]) {
        var lines: [[Character]] = [[]]
        var separators: [Character] = []
        for ch in text {
            if isLineBreak(ch) {
                separators.append(ch)
                lines.append([])
            } else {
                lines[lines.count - 1].append(ch)
            }
        }
        var out: [Character] = []
        var spans: [Span] = []
        var i = 0
        while i < lines.count {
            if i > 0 { out.append(separators[i - 1]) }
            if lines[i] == fence, let close = closingFence(lines, after: i) {
                let start = out.count
                for k in (i + 1)..<close {
                    if k > i + 1 { out.append(separators[k - 1]) }
                    out.append(contentsOf: lines[k])
                }
                spans.append(Span(range: start..<out.count, styles: [.mono, .block]))
                i = close + 1
                continue
            }
            let base = out.count
            let result = inline(lines[i])
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

    /// The line of the closing fence, when at least one line between the fences has something other than whitespace.
    private static func closingFence(_ lines: [[Character]], after open: Int) -> Int? {
        var k = open + 1
        while k < lines.count {
            if lines[k] == fence {
                return lines[(open + 1)..<k].contains(where: { $0.contains(where: { !$0.isWhitespace }) }) ? k : nil
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

    /// A marker may open when it is at the start or after a boundary, is followed by a non-space, and is not part of
    /// a run of the same marker ("**", "``", three backticks on one line).
    private static func canOpen(_ c: [Character], _ i: Int) -> Bool {
        let m = c[i]
        if i > 0, !isBoundary(c[i - 1]) || c[i - 1] == m { return false }
        return i + 1 < c.count && !c[i + 1].isWhitespace && c[i + 1] != m
    }

    /// A marker may close when it follows a non-space, is at the end or before a boundary, and is not part of a run.
    private static func canClose(_ c: [Character], _ i: Int) -> Bool {
        let m = c[i]
        guard i > 0, !c[i - 1].isWhitespace, c[i - 1] != m else { return false }
        return i + 1 == c.count || (isBoundary(c[i + 1]) && c[i + 1] != m)
    }

    /// One line (no line break inside). Offsets in the result count the plain characters.
    ///
    /// 1. Monospace is found first, left to right: an opening backtick pairs with the next closing one, and nothing
    ///    inside is read, so the other markers inside it never pair with markers outside it.
    /// 2. For `*`, `_` and `~`, one left-to-right pass: a closing marker pairs with the innermost open marker of its
    ///    kind; open markers of other kinds above it are left without a partner and stay as typed.
    /// Both passes are linear in the length of the line.
    private static func inline(_ c: [Character]) -> (plain: [Character], spans: [Span]) {
        let n = c.count
        var masked = linkMask(c)
        // close[i] = index of the partner of the opening marker at i, or -1.
        var close = [Int](repeating: -1, count: n)

        // Next valid closing backtick at or after each index, for the monospace pass.
        var nextTick = [Int](repeating: n, count: n + 2)
        var j = n - 1
        var seen = n
        while j >= 0 {
            if c[j] == "`", !masked[j], canClose(c, j) { seen = j }
            nextTick[j] = seen
            j -= 1
        }
        var isMono = [Bool](repeating: false, count: n)
        var i = 0
        while i < n {
            if c[i] == "`", !masked[i], canOpen(c, i), nextTick[i + 2] < n {
                let end = nextTick[i + 2]
                close[i] = end
                isMono[i] = true
                for k in i...end { masked[k] = true }
                i = end + 1
            } else {
                i += 1
            }
        }

        // Stacks of open positions per kind, and one overall order to drop what sits above a pairing.
        var order: [Int] = []
        var byKind: [Character: [Int]] = ["*": [], "_": [], "~": []]
        for k in 0..<n {
            let m = c[k]
            guard m == "*" || m == "_" || m == "~", !masked[k] else { continue }
            if canClose(c, k), let open = byKind[m]!.last {
                close[open] = k
                // Open markers above this pairing never get a partner: forget them.
                while let top = order.last, top >= open {
                    order.removeLast()
                    byKind[c[top]]!.removeLast()
                }
            } else if canOpen(c, k) {
                order.append(k)
                byKind[m]!.append(k)
            }
        }

        var out: [Character] = []
        var spans: [Span] = []
        emit(c, close, isMono, 0, n, &out, &spans)
        return (out, spans)
    }

    /// Reads `c[lo..<hi]`, appending the plain characters to `out` and the spans (in `out` offsets) to `spans`.
    private static func emit(_ c: [Character], _ close: [Int], _ isMono: [Bool], _ lo: Int, _ hi: Int,
                             _ out: inout [Character], _ spans: inout [Span]) {
        var i = lo
        while i < hi {
            let end = close[i]
            guard end > i else {
                out.append(c[i])
                i += 1
                continue
            }
            let start = out.count
            if isMono[i] {
                out.append(contentsOf: c[(i + 1)..<end])
                spans.append(Span(range: start..<out.count, styles: [.mono]))
            } else {
                let style: Style = c[i] == "*" ? .bold : c[i] == "_" ? .italic : .strike
                let index = spans.count
                spans.append(Span(range: start..<start, styles: [style]))
                emit(c, close, isMono, i + 1, end, &out, &spans)
                spans[index].range = start..<out.count
            }
            i = end + 1
        }
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
