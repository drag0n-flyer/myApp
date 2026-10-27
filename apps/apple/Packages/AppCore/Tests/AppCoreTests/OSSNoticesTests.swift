import Foundation
import Testing
@testable import AppCore

@Test func decodesComponents() throws {
    let json = """
    {"components": [
      {"name": "serde", "version": "1.0.0", "license": "MIT OR Apache-2.0", "text": "Permission is hereby granted", "pending": false},
      {"name": "WebRTC", "version": "m156", "license": "BSD-3-Clause", "text": "Added later.", "pending": true}
    ]}
    """
    let list = try OSSNotices.decode(Data(json.utf8))
    #expect(list.count == 2)
    #expect(list[0].name == "serde")
    #expect(list[0].license == "MIT OR Apache-2.0")
    #expect(list[0].id == "serde 1.0.0")
    #expect(list[1].pending)
}

@Test func pendingDefaultsToFalse() throws {
    let json = #"{"components": [{"name": "a", "version": "1", "license": "MIT", "text": "t"}]}"#
    #expect(try OSSNotices.decode(Data(json.utf8))[0].pending == false)
}

@Test func rejectsBadData() {
    #expect(throws: (any Error).self) { try OSSNotices.decode(Data("nope".utf8)) }
}

@Test func bundledListHasRequiredComponents() throws {
    let list = try OSSNotices.bundled()
    let names = Set(list.map(\.name))
    for required in ["WebRTC", "Opus", "RNNoise", "mls-rs", "uniffi", "uniffi_core"] {
        #expect(names.contains(required), "missing \(required)")
    }
    #expect(list.allSatisfy { !$0.text.isEmpty && !$0.license.isEmpty })
    #expect(list.filter { $0.license == "MPL-2.0" }.count == 8)
}
