import Foundation

/// The rules for making a link preview (spec 6.3): which link, and which hosts the sender's phone may contact.
/// Pure, so Android and Apple share one test table. The receiver never uses any of this: it shows what the message carries.
public enum LinkPreviewRule {
    /// The first link of `text`, only when it is an `https` link that is not inside a `code` span or block. `nil` when
    /// the first link is anything else (`http`, `mailto`), so a plain-http link never gets a preview from a later one.
    public static func firstLink(_ text: String) -> URL? {
        let marked = MessageFormat.attributed(text)
        for run in marked.runs {
            guard let url = run.link, let scheme = url.scheme?.lowercased(), ["http", "https", "mailto"].contains(scheme) else { continue }
            guard scheme == "https" else { return nil }
            // The text as typed, so the preview's url is always found in the message (the receiver checks that).
            let typed = String(marked[run.range].characters)
            guard text.contains(typed), let typedURL = URL(string: typed), typedURL.host?.isEmpty == false else { return nil }
            return typedURL
        }
        return nil
    }

    /// True when the phone may contact `host`, whose DNS answer was `resolved` (address texts). False for an empty
    /// answer, an IP-literal host (any form), a name without a dot, `.local`, `.internal`, `localhost`, and any answer
    /// that holds one address which is loopback, private, link-local, unique-local, CGNAT, multicast, unspecified,
    /// reserved or documentation. One bad address refuses the whole answer.
    public static func allowedHost(_ host: String, resolved: [String]) -> Bool {
        var name = host.lowercased()
        if name.hasSuffix(".") { name.removeLast() }
        guard !name.isEmpty, name.unicodeScalars.count <= 253 else { return false }
        // Only a-z, 0-9, "-" and "." (no underscore, no non-ASCII): a lower-case ASCII name is what DNS and the checks agree on.
        guard name.utf8.allSatisfy({ ($0 >= 0x61 && $0 <= 0x7A) || ($0 >= 0x30 && $0 <= 0x39) || $0 == 0x2D || $0 == 0x2E }) else { return false }
        let labels = name.split(separator: ".", omittingEmptySubsequences: false)
        guard labels.count >= 2, labels.allSatisfy({ !$0.isEmpty }) else { return false }
        // 127.1, 2130706433, 0x7f.0.0.1, 017700000001: the last label of a number is a number.
        if let last = labels.last, last.allSatisfy(\.isNumber) || last.hasPrefix("0x") { return false }
        for suffix in [".local", ".internal", ".localhost", ".localdomain", ".home.arpa"] where name.hasSuffix(suffix) { return false }
        guard !resolved.isEmpty else { return false }
        return resolved.allSatisfy { address in IPAddress(address).map { !$0.isNonPublic } ?? false }
    }

    /// An IPv4 or IPv6 address as bytes (4 or 16).
    public struct IPAddress: Equatable, Sendable {
        public let bytes: [UInt8]

        public init?(_ text: String) {
            var text = text
            if text.hasPrefix("["), text.hasSuffix("]") { text = String(text.dropFirst().dropLast()) }
            if let zone = text.firstIndex(of: "%") { text = String(text[..<zone]) }
            if text.contains(":") {
                guard let parsed = Self.v6(text) else { return nil }
                bytes = parsed
            } else {
                guard let parsed = Self.v4(text) else { return nil }
                bytes = parsed
            }
        }

        static func v4(_ text: String) -> [UInt8]? {
            let parts = text.split(separator: ".", omittingEmptySubsequences: false)
            guard parts.count == 4 else { return nil }
            var out: [UInt8] = []
            for part in parts {
                guard !part.isEmpty, part.count <= 3, part.allSatisfy({ $0.isASCII && $0.isNumber }), let n = UInt8(part) else { return nil }
                out.append(n)
            }
            return out
        }

        static func v6(_ source: String) -> [UInt8]? {
            var text = source
            var tail: [UInt8] = []
            // A dotted IPv4 ending: ::ffff:1.2.3.4
            if let dot = text.lastIndex(of: "."), let colon = text[..<dot].lastIndex(of: ":") {
                guard let v4 = v4(String(text[text.index(after: colon)...])) else { return nil }
                tail = v4
                text = String(text[...colon]) + "0:0"
            }
            let halves = text.components(separatedBy: "::")
            guard halves.count <= 2 else { return nil }
            func groups(_ part: String) -> [UInt16]? {
                if part.isEmpty { return [] }
                var out: [UInt16] = []
                for piece in part.split(separator: ":", omittingEmptySubsequences: false) {
                    guard !piece.isEmpty, piece.count <= 4, let n = UInt16(piece, radix: 16) else { return nil }
                    out.append(n)
                }
                return out
            }
            guard let head = groups(halves[0]) else { return nil }
            var all = head
            if halves.count == 2 {
                guard let rest = groups(halves[1]), head.count + rest.count <= 7 else { return nil }
                all += Array(repeating: 0, count: 8 - head.count - rest.count) + rest
            }
            guard all.count == 8 else { return nil }
            var out = all.flatMap { [UInt8($0 >> 8), UInt8($0 & 0xFF)] }
            if !tail.isEmpty { out.replaceSubrange(12..<16, with: tail) }
            return out
        }

        /// Not an address an ordinary website has. IPv6 is an allow-list: only 2000::/3 is public (minus the reserved
        /// blocks inside it), plus IPv4-mapped, NAT64 64:ff9b::/96 and 6to4, whose embedded IPv4 is checked. Everything
        /// else (including 64:ff9b:1::/48, 100::/64, ::, ::1, fc00::/7, fe80::/10, ff00::/8) is refused.
        public var isNonPublic: Bool {
            if bytes.count == 4 { return Self.nonPublicV4(bytes) }
            let b = bytes
            if b[0..<10].allSatisfy({ $0 == 0 }), b[10] == 0xFF, b[11] == 0xFF { return Self.nonPublicV4(Array(b[12..<16])) }  // ::ffff:v4
            if b[0] == 0x00, b[1] == 0x64, b[2] == 0xFF, b[3] == 0x9B, b[4..<12].allSatisfy({ $0 == 0 }) {
                return Self.nonPublicV4(Array(b[12..<16]))                                  // 64:ff9b::/96 NAT64
            }
            guard b[0] & 0xE0 == 0x20 else { return true }                                  // outside 2000::/3
            if b[0] == 0x20, b[1] == 0x02 { return Self.nonPublicV4(Array(b[2..<6])) }     // 2002::/16 6to4
            if b[0] == 0x20, b[1] == 0x01, b[2] & 0xFE == 0x00 { return true }             // 2001::/23 protocol assignments, Teredo
            if b[0] == 0x20, b[1] == 0x01, b[2] == 0x0D, b[3] == 0xB8 { return true }      // 2001:db8::/32 documentation
            if b[0] == 0x3F, b[1] == 0xFF, b[2] & 0xF0 == 0x00 { return true }             // 3fff::/20 documentation
            return false
        }

        static func nonPublicV4(_ b: [UInt8]) -> Bool {
            switch (b[0], b[1], b[2]) {
            case (0, _, _), (10, _, _), (127, _, _): return true
            case (100, 64...127, _): return true            // CGNAT
            case (169, 254, _): return true
            case (172, 16...31, _): return true
            case (192, 0, 0), (192, 0, 2): return true
            case (192, 168, _): return true
            case (198, 18...19, _): return true
            case (198, 51, 100), (203, 0, 113): return true
            case (224...255, _, _): return true             // multicast, reserved, broadcast
            default: return false
            }
        }
    }
}
