import Foundation
import Testing
@testable import Messaging

/// The vectors shared with the server and Android: server/internal/vectors/envelope_people.json.
/// `expect` lists the decoded fields; a field not listed must be absent. `{"unsupported": kind}` means the envelope
/// decodes to `.unsupported(kind:)`. Photos and images are checked by length (`photoLen`, `imgLen`), a caption of
/// plain letters by `cLen`.
private struct PeopleVector: Decodable {
    let name: String
    let json: String
    let expect: [String: JSONValue]
}

private enum JSONValue: Decodable, Equatable {
    case bool(Bool), int(Int), double(Double), string(String), object([String: JSONValue])
    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Int.self) { self = .int(v) }
        else if let v = try? c.decode(Double.self) { self = .double(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }
}

private func loadVectors() throws -> [PeopleVector] {
    var url = URL(fileURLWithPath: #filePath)
    for _ in 0..<7 { url.deleteLastPathComponent() }   // file, MessagingTests, Tests, Messaging, Packages, apple, apps
    url.appendPathComponent("server/internal/vectors/envelope_people.json")
    return try JSONDecoder().decode([PeopleVector].self, from: Data(contentsOf: url))
}

private func fields(_ env: ContentEnvelope) -> [String: JSONValue] {
    var f: [String: JSONValue] = [:]
    if let ttl = env.ttl { f["ttl"] = .int(ttl) }
    func caption() {
        guard let c = env.caption else { return }
        if c.count >= 4000 { f["cLen"] = .int(c.count); f["cScalars"] = .int(c.unicodeScalars.count) } else { f["c"] = .string(c) }
    }
    switch env.body {
    case .typing(let on): f["kind"] = .string("typing"); f["on"] = .bool(on)
    case .presence(let state): f["kind"] = .string("presence"); f["state"] = .string(state.rawValue)
    case .timer(let seconds): f["kind"] = .string("timer"); f["seconds"] = .int(seconds)
    case .profile(let name, let photo, let ver):
        f["kind"] = .string("profile"); f["name"] = .string(name); f["ver"] = .int(ver)
        if let photo { f["photoLen"] = .int(photo.count) }
    case .text(let text, _):
        f["kind"] = .string("text"); f["text"] = .string(text)
        if let lp = env.preview {
            var o: [String: JSONValue] = ["url": .string(lp.url), "title": .string(lp.title)]
            if let d = lp.desc { o["desc"] = .string(d) }
            if let i = lp.img { o["imgLen"] = .int(i.count) }
            f["lp"] = .object(o)
        }
    case .photo(let att, let w, let h, _, _):
        f["kind"] = .string("photo"); f["attId"] = .string(att.id); f["w"] = .int(w); f["h"] = .int(h); caption()
    case .voice(let att, let duration, _):
        f["kind"] = .string("voice"); f["attId"] = .string(att.id); f["duration"] = .double(duration)
    case .file(let att, let name, let mime, _):
        f["kind"] = .string("file"); f["attId"] = .string(att.id); f["name"] = .string(name); f["mime"] = .string(mime); caption()
    case .unsupported(let kind): f["unsupported"] = .string(kind)
    default: f["kind"] = .string("other")
    }
    return f
}

@Suite("TestEnvelopePeopleVectors") struct TestEnvelopePeopleVectors {
    @Test func everySharedVectorDecodesAsExpected() throws {
        let all = try loadVectors()
        #expect(all.count >= 60)
        for v in all {
            let env = ContentEnvelope.decode(Data(v.json.utf8))
            var want = v.expect
            // a double written as 3.0 is read as an int by this reader
            if case .int(let i)? = want["duration"] { want["duration"] = .double(Double(i)) }
            #expect(fields(env) == want, "\(v.name)")
        }
    }

    @Test func validNewEnvelopesSurviveARoundTrip() throws {
        for v in try loadVectors() where v.expect["unsupported"] == nil {
            let first = ContentEnvelope.decode(Data(v.json.utf8))
            let second = ContentEnvelope.decode(first.encoded())
            #expect(first == second, "\(v.name)")
        }
    }
}
