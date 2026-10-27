import Foundation

/// JSON coding for the server API. Keys are explicit in each type's CodingKeys.
enum ServerJSON {
    static func encoder() -> JSONEncoder {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .custom { date, enc in
            var c = enc.singleValueContainer()
            try c.encode(formatRFC3339(date))
        }
        return e
    }

    static func decoder() -> JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .custom { dec in
            let c = try dec.singleValueContainer()
            let s = try c.decode(String.self)
            guard let date = parseRFC3339(s) else {
                throw DecodingError.dataCorruptedError(in: c, debugDescription: "invalid RFC 3339 date")
            }
            return date
        }
        return d
    }

    private static func formatRFC3339(_ date: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.string(from: date)
    }

    /// Accepts 0 to 9 fractional digits (Go writes nanoseconds).
    static func parseRFC3339(_ s: String) -> Date? {
        var base = s
        var fraction = 0.0
        if let dot = s.firstIndex(of: ".") {
            var end = s.index(after: dot)
            while end < s.endIndex, s[end].isASCII, s[end].isNumber { end = s.index(after: end) }
            let digits = s[s.index(after: dot)..<end]
            guard !digits.isEmpty, digits.count <= 9, let f = Double("0." + digits) else { return nil }
            fraction = f
            base = String(s[..<dot]) + String(s[end...])
        }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        guard let d = f.date(from: base) else { return nil }
        return d.addingTimeInterval(fraction)
    }
}
