import AppCore
import Foundation
import Testing
@testable import Messaging

private struct Vector: Decodable { let name: String; let json: String }

private func vectors() throws -> [String: String] {
    let url = try #require(Bundle.module.url(forResource: "content-v1", withExtension: "json", subdirectory: "Resources"))
    let list = try JSONDecoder().decode([Vector].self, from: Data(contentsOf: url))
    return Dictionary(uniqueKeysWithValues: list.map { ($0.name, $0.json) })
}

private let att = Att(id: "att1", key: Data(repeating: 1, count: 32), dg: Data(repeating: 2, count: 32), sz: 100, csz: 116)
private let reply = "00000000-0000-4000-8000-000000000000"
private let callPayload = CallSignalPayload(
    kind: .offer, callID: "c1", video: true, sdp: "v=0\r\no=- 1 2 IN IP4 0.0.0.0", dtlsFingerprint: "sha-256 AB:CD:EF",
    candidate: IceCandidate(sdp: "candidate:1 1 udp 2130706431 1.2.3.4 5000 typ host", sdpMid: "0", sdpMLineIndex: 0),
    ceiling: .balanced, videoOn: false)

private let expected: [String: ContentEnvelope.Body] = [
    "text": .text("Hello 🙂", replyTo: nil),
    "text_reply": .text("re", replyTo: reply),
    "photo": .photo(att, w: 1600, h: 900, thumb: Data([0xff, 0xd8]) + Data("thumb".utf8), replyTo: "rr"),
    "photo_nothumb": .photo(att, w: 10, h: 20, thumb: nil, replyTo: nil),
    "voice": .voice(att, duration: 14.5, replyTo: nil),
    "file": .file(att, name: "Report.pdf", mime: "application/pdf", replyTo: "rr"),
    "edit": .edit(target: "t1", text: "fixed"),
    "delete": .delete(target: "t1"),
    "react": .react(target: "t1", emoji: "👍"),
    "react_remove": .react(target: "t1", emoji: ""),
    "receipt_delivered": .receipt(.delivered, ids: ["a", "b"]),
    "receipt_read": .receipt(.read, ids: ["a"]),
    "callsig": .callSignal(callPayload),
    "mode": .mode("e2ee"),
    "mode_server_readable": .mode("server_readable"),
]

@Suite("TestEnvelopeVectors") struct TestEnvelopeVectors {
    @Test func everyVectorDecodesAndReencodesExactly() throws {
        let all = try vectors()
        #expect(Set(all.keys) == Set(expected.keys))
        for (name, json) in all {
            let env = ContentEnvelope.decode(Data(json.utf8))
            #expect(env.v == 1 && env.id == "00000000-0000-4000-8000-000000000001" && env.at == 1_700_000_000_000, "\(name)")
            #expect(env.body == expected[name], "\(name)")
            #expect(String(decoding: env.encoded(), as: UTF8.self) == json, "\(name)")
        }
    }
}

@Suite("TestCallSignalEnvelope") struct TestCallSignalEnvelope {
    @Test func callsigRoundTripsByteForByte() throws {
        let json = try #require(vectors()["callsig"])
        let env = ContentEnvelope.decode(Data(json.utf8))
        guard case .callSignal(let p) = env.body else { Issue.record("not callsig"); return }
        #expect(p == callPayload)
        #expect(p.candidate != nil && p.ceiling == .balanced && p.videoOn == false && p.video == true)
        #expect(env.encoded() == Data(json.utf8))
    }
}

@Suite("TestModeEnvelope") struct TestModeEnvelope {
    @Test func modeVectorAndUnknownValue() throws {
        let json = #"{"at":1,"id":"x","k":"mode","m":"e2ee","v":1}"#
        let env = ContentEnvelope.decode(Data(json.utf8))
        #expect(env.body == .mode("e2ee"))
        #expect(String(decoding: env.encoded(), as: UTF8.self) == json)
        let odd = ContentEnvelope.decode(Data(#"{"at":1,"id":"x","k":"mode","m":"banana","v":1}"#.utf8))
        #expect(odd.body == .unsupported(kind: "mode"))
    }
}

@Suite("TestEnvelopeForwardCompat") struct TestEnvelopeForwardCompat {
    @Test func unknownKind() {
        let env = ContentEnvelope.decode(Data(#"{"v":1,"id":"x","at":1,"k":"poll"}"#.utf8))
        #expect(env.body == .unsupported(kind: "poll"))
        #expect(env.id == "x" && env.at == 1)
    }

    @Test func unknownKindWithOddFieldTypes() {
        let env = ContentEnvelope.decode(Data(#"{"v":1,"id":"x","at":1,"k":"poll","t":5,"a":[1]}"#.utf8))
        #expect(env.body == .unsupported(kind: "poll"))
    }

    @Test func newerVersion() {
        let env = ContentEnvelope.decode(Data(#"{"v":2,"id":"x","at":1,"k":"text","t":"hi"}"#.utf8))
        guard case .unsupported = env.body else { Issue.record("expected unsupported"); return }
    }

    @Test func garbage() {
        for bad in ["not json", "", "[]", "{}", #"{"v":1,"id":"x","at":1}"#, #"{"v":1,"id":"x","at":1,"k":"text"}"#] {
            let env = ContentEnvelope.decode(Data(bad.utf8))
            #expect(env.body == .unsupported(kind: "invalid"), "\(bad)")
        }
    }

    @Test func extraFieldIgnored() {
        let env = ContentEnvelope.decode(Data(#"{"v":1,"id":"x","at":1,"k":"text","t":"hi","zzz":{"a":1}}"#.utf8))
        #expect(env.body == .text("hi", replyTo: nil))
    }

    @Test func unknownReceiptKindIsUnsupported() {
        let env = ContentEnvelope.decode(Data(#"{"v":1,"id":"x","at":1,"k":"receipt","s":"seen","ids":[]}"#.utf8))
        #expect(env.body == .unsupported(kind: "receipt"))
    }
}
