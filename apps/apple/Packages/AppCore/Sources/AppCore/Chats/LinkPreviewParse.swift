import Foundation

/// Reads a web page's preview fields (spec 6.3). Never throws and never reads more than `limit` bytes.
public enum LinkPreviewParse {
    public struct Result: Equatable, Sendable {
        /// 1...200 code points.
        public var title: String?
        /// At most 300 code points.
        public var desc: String?
        /// An `https` URL, not yet fetched and not yet checked by `LinkPreviewRule.allowedHost`.
        public var imageURL: URL?
    }

    public static let maxTitle = 200
    public static let maxDescription = 300

    /// `og:title` (else `<title>`), `og:description` (else `<meta name="description">`) and `og:image`. `nil` when there is
    /// no title. `base` resolves a relative image address.
    public static func fromHTML(_ bytes: Data, limit: Int, base: URL? = nil) -> Result? {
        let text = String(decoding: bytes.prefix(max(limit, 0)), as: UTF8.self)
        var ogTitle: String?, ogDesc: String?, nameDesc: String?, ogImage: String?, pageTitle: String?
        var scanner = HTMLScanner(text)
        while let tag = scanner.nextTag() {
            switch tag.name {
            case "meta":
                guard let content = tag.attributes["content"] else { continue }
                switch (tag.attributes["property"] ?? tag.attributes["name"] ?? "").lowercased() {
                case "og:title": if ogTitle == nil { ogTitle = content }
                case "og:description": if ogDesc == nil { ogDesc = content }
                case "description": if nameDesc == nil { nameDesc = content }
                case "og:image", "og:image:url": if ogImage == nil { ogImage = content }
                default: break
                }
            case "title":
                if pageTitle == nil { pageTitle = scanner.textUntilClose("title") }
            default: break
            }
        }
        guard let title = [ogTitle, pageTitle].lazy.compactMap({ clean($0, max: maxTitle) }).first else { return nil }
        return Result(title: title, desc: clean(ogDesc ?? nameDesc, max: maxDescription), imageURL: imageURL(ogImage, base: base))
    }

    /// Entities decoded, controls and direction marks dropped, white space collapsed, cut to `max` code points. `nil` when empty.
    static func clean(_ raw: String?, max: Int) -> String? {
        guard let raw else { return nil }
        var out = String.UnicodeScalarView()
        var pendingSpace = false
        for scalar in decodeEntities(raw).unicodeScalars {
            if scalar.properties.isWhitespace {
                pendingSpace = !out.isEmpty
                continue
            }
            if isDropped(scalar) { continue }
            if pendingSpace { out.append(" "); pendingSpace = false }
            out.append(scalar)
            if out.count >= max { break }
        }
        let result = String(out)
        return result.isEmpty ? nil : result
    }

    private static func isDropped(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x00...0x1F, 0x7F...0x9F, 0x200B...0x200F, 0x202A...0x202E, 0x2060...0x2069, 0x061C, 0xFEFF, 0xFFFC, 0xFFFD: true
        default: false
        }
    }

    private static func imageURL(_ raw: String?, base: URL?) -> URL? {
        guard let raw = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty, raw.count <= 2048 else { return nil }
        let text = decodeEntities(raw)
        guard let url = URL(string: text, relativeTo: base)?.absoluteURL,
              url.scheme?.lowercased() == "https", url.host?.isEmpty == false else { return nil }
        return url
    }

    static func decodeEntities(_ text: String) -> String {
        guard text.contains("&") else { return text }
        let named = ["amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": " ", "ndash": "\u{2013}", "mdash": "\u{2014}",
                     "hellip": "\u{2026}", "rsquo": "\u{2019}", "lsquo": "\u{2018}", "rdquo": "\u{201D}", "ldquo": "\u{201C}"]
        var out = ""
        var rest = Substring(text)
        while let amp = rest.firstIndex(of: "&") {
            out += rest[..<amp]
            let after = rest[rest.index(after: amp)...]
            guard let semi = after.prefix(10).firstIndex(of: ";") else { out += "&"; rest = after; continue }
            let name = String(after[..<semi])
            var replacement: String?
            if name.hasPrefix("#") {
                let digits = name.dropFirst()
                let value = digits.hasPrefix("x") || digits.hasPrefix("X") ? UInt32(digits.dropFirst(), radix: 16) : UInt32(digits)
                replacement = value.flatMap(Unicode.Scalar.init).map { String(Character($0)) }
            } else {
                replacement = named[name]
            }
            if let replacement { out += replacement; rest = after[after.index(after: semi)...] } else { out += "&"; rest = after }
        }
        return out + rest
    }

    /// A tolerant walk over the tags of a page: skips comments and the insides of `script` and `style`.
    private struct HTMLScanner {
        struct Tag { var name: String; var attributes: [String: String] }

        private let chars: [Character]
        private var at = 0

        init(_ text: String) { chars = Array(text) }

        mutating func nextTag() -> Tag? {
            while at < chars.count {
                guard chars[at] == "<" else { at += 1; continue }
                if starts(with: "<!--") {
                    at = find("-->", from: at + 4).map { $0 + 3 } ?? chars.count
                    continue
                }
                var cursor = at + 1
                if cursor < chars.count, chars[cursor] == "/" || chars[cursor] == "!" || chars[cursor] == "?" {
                    at = find(">", from: cursor).map { $0 + 1 } ?? chars.count
                    continue
                }
                var name = ""
                while cursor < chars.count, chars[cursor].isLetter || chars[cursor].isNumber { name.append(chars[cursor]); cursor += 1 }
                guard !name.isEmpty else { at += 1; continue }
                name = name.lowercased()
                var attributes: [String: String] = [:]
                var closed = false
                while cursor < chars.count {
                    while cursor < chars.count, chars[cursor].isWhitespace || chars[cursor] == "/" { cursor += 1 }
                    if cursor >= chars.count { break }
                    if chars[cursor] == ">" { cursor += 1; closed = true; break }
                    var key = ""
                    while cursor < chars.count, !chars[cursor].isWhitespace, !"=>/".contains(chars[cursor]) { key.append(chars[cursor]); cursor += 1 }
                    if key.isEmpty { cursor += 1; continue }
                    while cursor < chars.count, chars[cursor].isWhitespace { cursor += 1 }
                    var value = ""
                    if cursor < chars.count, chars[cursor] == "=" {
                        cursor += 1
                        while cursor < chars.count, chars[cursor].isWhitespace { cursor += 1 }
                        if cursor < chars.count, chars[cursor] == "\"" || chars[cursor] == "'" {
                            let quote = chars[cursor]
                            cursor += 1
                            while cursor < chars.count, chars[cursor] != quote { value.append(chars[cursor]); cursor += 1 }
                            if cursor >= chars.count { cursor = chars.count; key = ""; break }   // unterminated: drop the attribute
                            cursor += 1
                        } else {
                            while cursor < chars.count, !chars[cursor].isWhitespace, chars[cursor] != ">" { value.append(chars[cursor]); cursor += 1 }
                        }
                    }
                    attributes[key.lowercased()] = value
                }
                at = cursor
                guard closed else { return nil }
                if name == "script" || name == "style" {
                    at = findCaseless("</\(name)", from: at).map { $0 } ?? chars.count
                    continue
                }
                return Tag(name: name, attributes: attributes)
            }
            return nil
        }

        /// The text after a just-read `<title>` up to its `</title>`; `nil` when there is none.
        mutating func textUntilClose(_ name: String) -> String? {
            guard let end = findCaseless("</\(name)", from: at) else { return nil }
            let text = String(chars[at..<end])
            at = end
            return text
        }

        private func starts(with text: String) -> Bool {
            let needle = Array(text)
            return at + needle.count <= chars.count && Array(chars[at..<at + needle.count]) == needle
        }

        private func find(_ text: String, from start: Int) -> Int? {
            let needle = Array(text)
            guard start >= 0, chars.count >= needle.count, start <= chars.count - needle.count else { return nil }
            for i in start...(chars.count - needle.count) where chars[i] == needle[0] && Array(chars[i..<i + needle.count]) == needle { return i }
            return nil
        }

        private func findCaseless(_ text: String, from start: Int) -> Int? {
            let needle = Array(text.lowercased())
            guard start >= 0, chars.count >= needle.count, start <= chars.count - needle.count else { return nil }
            for i in start...(chars.count - needle.count) {
                var match = true
                for (offset, c) in needle.enumerated() where chars[i + offset].lowercased() != String(c) { match = false; break }
                if match { return i }
            }
            return nil
        }
    }
}
