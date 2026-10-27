import Foundation
import Testing
@testable import ServerKit

@Suite struct CodingTests {
    @Test func base64URLRoundTrip() throws {
        let v = Base64URLData(data: Data([0xfb, 0xff]))
        #expect(v.encoded == "-_8")
        #expect(Base64URLData(encoded: "-_8")?.data == Data([0xfb, 0xff]))
        #expect(Base64URLData(encoded: "-_8=")?.data == Data([0xfb, 0xff]))
        #expect(Base64URLData(data: Data()).encoded == "")
        let json = try ServerJSON.encoder().encode(["k": v])
        #expect(String(decoding: json, as: UTF8.self) == #"{"k":"-_8"}"#)
        let back = try ServerJSON.decoder().decode([String: Base64URLData].self, from: Data(#"{"k":"-_8="}"#.utf8))
        #expect(back["k"] == v)
    }

    @Test func decodesGoTimestamps() throws {
        struct T: Decodable { let at: Date }
        let d = ServerJSON.decoder()
        let a = try d.decode(T.self, from: Data(#"{"at":"2026-11-01T12:00:00.123456789Z"}"#.utf8)).at
        let b = try d.decode(T.self, from: Data(#"{"at":"2026-11-01T12:00:00Z"}"#.utf8)).at
        #expect(abs(a.timeIntervalSince(b) - 0.123) < 0.001)
    }
}
