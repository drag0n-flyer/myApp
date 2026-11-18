import Foundation
import Testing
@testable import Messaging

/// The vectors shared with the server and Android: server/internal/vectors/envelope_groups.json.
/// `expect` lists the decoded fields (`photo` as `photoLen`); `{"unsupported": kind}` means the envelope decodes to
/// `.unsupported(kind:)`.
private struct GroupVector: Decodable {
    let name: String
    let json: String
    let expect: [String: Value]

    enum Value: Decodable, Equatable {
        case int(Int), string(String)
        init(from decoder: Decoder) throws {
            let c = try decoder.singleValueContainer()
            if let v = try? c.decode(Int.self) { self = .int(v) } else { self = .string(try c.decode(String.self)) }
        }
    }
}

private func loadVectors() throws -> [GroupVector] {
    var url = URL(fileURLWithPath: #filePath)
    for _ in 0..<7 { url.deleteLastPathComponent() }   // file, MessagingTests, Tests, Messaging, Packages, apple, apps
    url.appendPathComponent("server/internal/vectors/envelope_groups.json")
    return try JSONDecoder().decode([GroupVector].self, from: Data(contentsOf: url))
}

private func fields(_ env: ContentEnvelope) -> [String: GroupVector.Value] {
    switch env.body {
    case .group(let name, let photo, let ver):
        var f: [String: GroupVector.Value] = ["kind": .string("group"), "name": .string(name), "ver": .int(ver)]
        if let photo { f["photoLen"] = .int(photo.count) }
        return f
    case .unsupported(let kind): return ["unsupported": .string(kind)]
    default: return ["kind": .string("other")]
    }
}

@Suite("TestEnvelopeGroupsVectors") struct TestEnvelopeGroupsVectors {
    @Test func everySharedVectorDecodesAsExpected() throws {
        let all = try loadVectors()
        #expect(all.count >= 25)
        for v in all {
            #expect(fields(ContentEnvelope.decode(Data(v.json.utf8))) == v.expect, "\(v.name)")
        }
    }

    @Test func validVectorsSurviveARoundTrip() throws {
        for v in try loadVectors() where v.expect["unsupported"] == nil {
            let first = ContentEnvelope.decode(Data(v.json.utf8))
            let second = ContentEnvelope.decode(first.encoded())
            #expect(first == second, "\(v.name)")
        }
    }

    @Test func aGroupEnvelopeIsNeverStoredAsAMessage() {
        let ok = ContentEnvelope.decode(Data(#"{"v":1,"id":"m1","at":1,"k":"group","name":"Club","ver":1}"#.utf8))
        let bad = ContentEnvelope.decode(Data(#"{"v":1,"id":"m1","at":1,"k":"group","name":"","ver":1}"#.utf8))
        #expect(ok.isSignalOnly)
        #expect(bad.isSignalOnly)
    }

    @Test func aGroupEnvelopeNeverCarriesATimeToLiveOrACaption() {
        let env = ContentEnvelope.decode(Data(#"{"v":1,"id":"m1","at":1,"k":"group","name":"Club","ver":1,"ttl":60,"c":"x"}"#.utf8))
        #expect(env.ttl == nil && env.caption == nil && env.preview == nil)
    }
}
