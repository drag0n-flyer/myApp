import AppCore
import Foundation

/// A server-readable or end-to-end message payload: versioned JSON with short keys, carried as
/// the MLS application-message plaintext (client messaging plan, "Content envelope").
public struct ContentEnvelope: Codable, Sendable, Equatable {
    public var v: Int
    public var id: String
    public var at: Int64
    public var body: Body
    /// Seconds the message lives after `at` (1...31,622,400) on `text`, `photo`, `voice` and `file`; `nil` when absent
    /// or out of range (the message is kept).
    public var ttl: Int?
    /// A caption on `photo` and `file`, cut to `maxCaptionLength` characters on receive.
    public var caption: String?
    /// A link preview on `text`; `nil` when absent or invalid (the text is kept).
    public var preview: LinkPreview?

    public static let maxCaptionLength = 4096
    public static let maxTTL = 31_622_400
    public static let maxProfilePhoto = 24_576
    public static let maxPreviewImage = 8_192

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
        case typing(on: Bool)
        case presence(PresenceState)
        case timer(seconds: Int)
        case profile(name: String, photo: Data?, ver: Int)
        case unsupported(kind: String)
    }

    public init(v: Int = 1, id: String, at: Int64, body: Body, ttl: Int? = nil, caption: String? = nil, preview: LinkPreview? = nil) {
        self.v = v
        self.id = id
        self.at = at
        self.body = body
        self.ttl = ttl
        self.caption = caption
        self.preview = preview
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
        return ContentEnvelope(wire: wire, body: body)
    }

    private init(wire: Wire, body: Body) {
        self.init(v: wire.v, id: wire.id, at: wire.at, body: body)
        switch body {
        case .text(let text, _):
            preview = wire.lp?.value.flatMap { $0.valid(in: text) ? $0.preview : nil }
        case .photo, .file:
            caption = wire.c?.value.map { String($0.prefix(Self.maxCaptionLength)) }
        default: break
        }
        switch body {
        case .text, .photo, .voice, .file:
            if let ttl = wire.ttl?.value, (1...Self.maxTTL).contains(ttl) { self.ttl = ttl }
        default: break
        }
    }

    /// A kind that is a signal or a profile update: never stored as a message, never notifies, even when malformed.
    public var isSignalOnly: Bool {
        switch body {
        case .typing, .presence, .profile: true
        case .unsupported(let kind): ["typing", "presence", "profile"].contains(kind)
        default: false
        }
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
        self.init(wire: wire, body: body)
    }

    public func encode(to encoder: Encoder) throws {
        try Wire(self).encode(to: encoder)
    }

    // MARK: - Wire

    private static let knownKinds: Set<String> = [
        "text", "photo", "voice", "file", "edit", "delete", "react", "receipt", "callsig", "mode",
        "typing", "presence", "timer", "profile",
    ]
    /// Kinds whose bad field values are reported under their own name.
    private static let invalidKinds: Set<String> = ["mode", "receipt", "typing", "presence", "timer", "profile"]

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
        var on: Lenient<Bool>?
        var state: Lenient<String>?
        var seconds: Lenient<Int>?
        var photo: Lenient<String>?
        var ver: Lenient<Int>?
        var ttl: Lenient<Int>?
        var c: Lenient<String>?
        var lp: Lenient<LinkPreview.Wire>?

        init(_ env: ContentEnvelope) {
            v = env.v; id = env.id; at = env.at; k = ""
            switch env.body {
            case .text, .photo, .voice, .file: ttl = env.ttl.map { Lenient($0) }
            default: break
            }
            c = env.caption.map { Lenient($0) }
            lp = env.preview.map { Lenient($0.wire) }
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
            case .typing(let isOn): k = "typing"; on = Lenient(isOn)
            case .presence(let presence): k = "presence"; state = Lenient(presence.rawValue)
            case .timer(let secs): k = "timer"; seconds = Lenient(secs)
            case .profile(let profileName, let image, let version):
                k = "profile"; name = profileName; photo = image.map { Lenient($0.base64EncodedString()) }; ver = Lenient(version)
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
            case "typing": return on?.value.map { .typing(on: $0) }
            case "presence": return state?.value.flatMap(PresenceState.init(rawValue:)).map { .presence($0) }
            case "timer":
                guard let seconds = seconds?.value, TimerChoice.from(seconds: seconds) != nil else { return nil }
                return .timer(seconds: seconds)
            case "profile":
                guard let name, DisplayNameRule.clean(name) == name, let ver = ver?.value, ver >= 1 else { return nil }
                guard let photo else { return .profile(name: name, photo: nil, ver: ver) }
                guard let text = photo.value, let image = LinkPreview.base64(text), LinkPreview.isJPEG(image, max: ContentEnvelope.maxProfilePhoto) else { return nil }
                return .profile(name: name, photo: image, ver: ver)
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

/// `online` or `away`, sent with `presence`.
public enum PresenceState: String, Codable, Sendable { case online, away }

/// A sender-made preview of the first link in a text: `url` (https, present in the text), `title` 1...200 and `desc`
/// up to 300 code points, `img` a JPEG of at most 8,192 bytes.
public struct LinkPreview: Sendable, Equatable {
    public var url: String
    public var title: String
    public var desc: String?
    public var img: Data?

    public init(url: String, title: String, desc: String? = nil, img: Data? = nil) {
        self.url = url
        self.title = title
        self.desc = desc
        self.img = img
    }

    struct Wire: Codable {
        var url: String
        var title: String
        var desc: String?
        /// Base64 as sent: padded or not on receive, always padded on send.
        var imgText: String?

        enum CodingKeys: String, CodingKey {
            case url, title, desc
            case imgText = "img"
        }

        init(url: String, title: String, desc: String?, img: Data?) {
            self.url = url
            self.title = title
            self.desc = desc
            self.imgText = img?.base64EncodedString()
        }

        var img: Data? { imgText.flatMap(LinkPreview.base64) }

        /// A card from another device is not trusted: control and direction characters are stripped from the title and
        /// description, and the title is trimmed.
        var cleaned: Wire {
            var copy = self
            copy.title = LinkPreview.clean(title).trimmingCharacters(in: .whitespacesAndNewlines)
            copy.desc = desc.map { LinkPreview.clean($0).trimmingCharacters(in: .whitespacesAndNewlines) }.flatMap { $0.isEmpty ? nil : $0 }
            return copy
        }

        /// Valid for this text: an https link without a user name, password or odd port, found in the text, a non-blank
        /// title of 1...200 and a description of up to 300 code points (after cleaning), a small JPEG.
        func valid(in text: String) -> Bool {
            guard let parsed = URL(string: url), parsed.scheme?.lowercased() == "https", parsed.host?.isEmpty == false,
                  parsed.user == nil, parsed.password == nil, parsed.port == nil || parsed.port == 443 else { return false }
            guard text.contains(url) else { return false }
            let clean = cleaned
            guard (1...200).contains(clean.title.unicodeScalars.count) else { return false }
            if let desc = clean.desc, desc.unicodeScalars.count > 300 { return false }
            if imgText != nil {
                guard let img, LinkPreview.isJPEG(img, max: ContentEnvelope.maxPreviewImage) else { return false }
            }
            return true
        }

        var preview: LinkPreview { let clean = cleaned; return LinkPreview(url: clean.url, title: clean.title, desc: clean.desc, img: clean.img) }
    }

    /// `text` without control, format and direction characters.
    static func clean(_ text: String) -> String {
        var out = String.UnicodeScalarView()
        for scalar in text.unicodeScalars {
            switch scalar.value {
            // The joiners U+200C and U+200D stay: emoji sequences need them.
            case 0x00...0x1F, 0x7F...0x9F, 0x200B, 0x200E, 0x200F, 0x202A...0x202E, 0x2060...0x2069, 0x061C, 0xFEFF: continue
            default: out.append(scalar)
            }
        }
        return String(out)
    }

    var wire: Wire { Wire(url: url, title: title, desc: desc, img: img) }

    /// The preview as stored in `messages.link_preview` (sorted keys, binary as base64).
    public var json: String? {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return (try? encoder.encode(wire)).map { String(decoding: $0, as: UTF8.self) }
    }

    /// Reads what `json` wrote; `nil` for anything else.
    public init?(json: String) {
        guard let wire = try? JSONDecoder().decode(Wire.self, from: Data(json.utf8)) else { return nil }
        self.init(url: wire.url, title: wire.title, desc: wire.desc, img: wire.img)
    }

    /// Standard base64, padded or not.
    static func base64(_ text: String) -> Data? {
        let rest = text.count % 4
        if rest == 1 { return nil }
        return Data(base64Encoded: rest == 0 ? text : text + String(repeating: "=", count: 4 - rest))
    }

    /// Starts with FF D8 and is not empty or over `max` bytes.
    static func isJPEG(_ data: Data, max: Int) -> Bool {
        data.count >= 2 && data.count <= max && data[data.startIndex] == 0xFF && data[data.startIndex + 1] == 0xD8
    }
}

/// Decodes to `value == nil` instead of throwing, so one bad optional field never spoils a whole envelope.
private struct Lenient<T: Codable & Sendable>: Codable, Sendable {
    var value: T?
    init(_ value: T) { self.value = value }
    init(from decoder: Decoder) throws { value = try? T(from: decoder) }
    func encode(to encoder: Encoder) throws {
        if let value { try value.encode(to: encoder) } else { var c = encoder.singleValueContainer(); try c.encodeNil() }
    }
}
