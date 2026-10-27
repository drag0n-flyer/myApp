import AppCore
import Foundation

/// A server-readable or end-to-end message payload: versioned JSON with short keys, carried as
/// the MLS application-message plaintext (client messaging plan, "Content envelope").
public struct ContentEnvelope: Codable, Sendable, Equatable {
    public var v: Int
    public var id: String
    public var at: Int64
    public var body: Body

    public enum Body: Sendable, Equatable {
        case text(String, replyTo: String?)
        case photo(Att, w: Int, h: Int, thumb: Data?, replyTo: String?)
        case voice(Att, duration: Double, replyTo: String?)
        case file(Att, name: String, mime: String, replyTo: String?)
        case edit(target: String, text: String)
        case delete(target: String)
        case react(target: String, emoji: String)
        case receipt(ReceiptKind, ids: [String])
        case callSignal(CallSignalPayload)
        case mode(String)
        case unsupported(kind: String)
    }

    public init(v: Int = 1, id: String, at: Int64, body: Body) {
        self.v = v
        self.id = id
        self.at = at
        self.body = body
    }

    /// Never throws: bad JSON, a newer version, an unknown kind or a malformed known kind all
    /// give `.unsupported` (`"invalid"` when not even the header can be read).
    public static func decode(_ data: Data) -> ContentEnvelope {
        let decoder = JSONDecoder()
        guard let head = try? decoder.decode(Header.self, from: data) else {
            return ContentEnvelope(id: "", at: 0, body: .unsupported(kind: "invalid"))
        }
        func unsupported(_ kind: String) -> ContentEnvelope {
            ContentEnvelope(v: head.v, id: head.id, at: head.at, body: .unsupported(kind: kind))
        }
        guard head.v == 1 else { return unsupported(head.k) }
        guard Self.knownKinds.contains(head.k) else { return unsupported(head.k) }
        guard let wire = try? decoder.decode(Wire.self, from: data), let body = wire.body() else {
            return unsupported(Self.invalidKinds.contains(head.k) ? head.k : "invalid")
        }
        return ContentEnvelope(v: head.v, id: head.id, at: head.at, body: body)
    }

    /// Sorted keys, no whitespace, slashes unescaped.
    public func encoded() -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return (try? encoder.encode(Wire(self))) ?? Data()
    }

    public init(from decoder: Decoder) throws {
        let wire = try Wire(from: decoder)
        guard let body = wire.body() else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "invalid envelope"))
        }
        self.init(v: wire.v, id: wire.id, at: wire.at, body: body)
    }

    public func encode(to encoder: Encoder) throws {
        try Wire(self).encode(to: encoder)
    }

    // MARK: - Wire

    private static let knownKinds: Set<String> = [
        "text", "photo", "voice", "file", "edit", "delete", "react", "receipt", "callsig", "mode",
    ]
    /// Kinds whose bad field values are reported under their own name.
    private static let invalidKinds: Set<String> = ["mode", "receipt"]

    private struct Header: Decodable {
        let v: Int
        let id: String
        let at: Int64
        let k: String
    }

    private struct Wire: Codable {
        var v: Int
        var id: String
        var at: Int64
        var k: String
        var t: String?
        var r: String?
        var a: Att?
        var w: Int?
        var h: Int?
        var th: Data?
        var dur: Double?
        var name: String?
        var mime: String?
        var target: String?
        var e: String?
        var s: String?
        var ids: [String]?
        var p: CallSignalPayload?
        var m: String?

        init(_ env: ContentEnvelope) {
            v = env.v; id = env.id; at = env.at; k = ""
            switch env.body {
            case .text(let text, let reply): k = "text"; t = text; r = reply
            case .photo(let att, let w, let h, let thumb, let reply): k = "photo"; a = att; self.w = w; self.h = h; th = thumb; r = reply
            case .voice(let att, let duration, let reply): k = "voice"; a = att; dur = duration; r = reply
            case .file(let att, let name, let mime, let reply): k = "file"; a = att; self.name = name; self.mime = mime; r = reply
            case .edit(let target, let text): k = "edit"; self.target = target; t = text
            case .delete(let target): k = "delete"; self.target = target
            case .react(let target, let emoji): k = "react"; self.target = target; e = emoji
            case .receipt(let kind, let ids): k = "receipt"; s = kind.rawValue; self.ids = ids
            case .callSignal(let payload): k = "callsig"; p = payload
            case .mode(let mode): k = "mode"; m = mode
            case .unsupported(let kind): k = kind
            }
        }

        /// `nil` when a known kind lacks a required field or has a bad value.
        func body() -> Body? {
            switch k {
            case "text": return t.map { .text($0, replyTo: r) }
            case "photo":
                guard let a, let w, let h else { return nil }
                return .photo(a, w: w, h: h, thumb: th, replyTo: r)
            case "voice":
                guard let a, let dur else { return nil }
                return .voice(a, duration: dur, replyTo: r)
            case "file":
                guard let a, let name, let mime else { return nil }
                return .file(a, name: name, mime: mime, replyTo: r)
            case "edit":
                guard let target, let t else { return nil }
                return .edit(target: target, text: t)
            case "delete": return target.map { .delete(target: $0) }
            case "react":
                guard let target, let e else { return nil }
                return .react(target: target, emoji: e)
            case "receipt":
                guard let s, let kind = ReceiptKind(rawValue: s), let ids else { return nil }
                return .receipt(kind, ids: ids)
            case "callsig": return p.map { .callSignal($0) }
            case "mode":
                guard let m, m == "e2ee" || m == "server_readable" else { return nil }
                return .mode(m)
            default: return nil
            }
        }
    }
}

/// An encrypted attachment on the server. `key` is the per-file AES key, `dg` the SHA-256 of the
/// ciphertext, `sz` and `csz` the plaintext and ciphertext sizes. Binary fields are base64.
public struct Att: Codable, Sendable, Equatable {
    public var id: String
    public var key: Data
    public var dg: Data
    public var sz: Int64
    public var csz: Int64

    public init(id: String, key: Data, dg: Data, sz: Int64, csz: Int64) {
        self.id = id
        self.key = key
        self.dg = dg
        self.sz = sz
        self.csz = csz
    }
}

public enum ReceiptKind: String, Codable, Sendable { case delivered, read }
