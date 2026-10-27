import Foundation
import Testing
@testable import AppCore

private let wireNames: [(DataMode, String)] = [
    (.ultraLow, "ultraLow"), (.dataSaver, "dataSaver"), (.balanced, "balanced"), (.high, "high"),
]

@Test func ceilingEncodesAsSpecString() throws {
    for (mode, name) in wireNames {
        let p = CallSignalPayload(kind: .modeChange, callID: "c1", ceiling: mode)
        let enc = JSONEncoder(); enc.outputFormatting = [.sortedKeys]
        let json = String(decoding: try enc.encode(p), as: UTF8.self)
        #expect(json == #"{"callID":"c1","ceiling":"\#(name)","kind":"modeChange"}"#)
    }
}

@Test func ceilingDecodesSpecStrings() throws {
    for (mode, name) in wireNames {
        let json = #"{"kind":"offer","callID":"c1","ceiling":"\#(name)"}"#
        let p = try JSONDecoder().decode(CallSignalPayload.self, from: Data(json.utf8))
        #expect(p.ceiling == mode)
    }
    let none = try JSONDecoder().decode(CallSignalPayload.self, from: Data(#"{"kind":"ring","callID":"c1"}"#.utf8))
    #expect(none.ceiling == nil)
}

@Test func unknownCeilingStringIsRejected() {
    let json = #"{"kind":"offer","callID":"c1","ceiling":"ultra"}"#
    #expect(throws: DecodingError.self) { try JSONDecoder().decode(CallSignalPayload.self, from: Data(json.utf8)) }
}

@Test func integerCeilingIsRejected() {
    let json = #"{"kind":"offer","callID":"c1","ceiling":2}"#
    #expect(throws: DecodingError.self) { try JSONDecoder().decode(CallSignalPayload.self, from: Data(json.utf8)) }
}

@Test func dataModeStorageFormatStaysInteger() throws {
    #expect(String(decoding: try JSONEncoder().encode(ModePreference.fixed(.balanced)), as: UTF8.self).contains("2"))
    #expect(try JSONDecoder().decode(DataMode.self, from: Data("2".utf8)) == .balanced)
}
