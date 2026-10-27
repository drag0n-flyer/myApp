import Foundation
import Testing
@testable import AppCore

private let sample = """
{"texts": {"MIT": "Permission is hereby granted", "sha-1": "Copyright (c) Someone"},
 "components": [
  {"name": "serde", "version": "1.0.0", "license": "MIT OR Apache-2.0", "authors": "A, B",
   "repository": "https://example.org/serde", "textIds": ["MIT", "sha-1"], "pending": false},
  {"name": "WebRTC", "version": "m156", "license": "BSD-3-Clause", "authors": "", "repository": null,
   "textIds": [], "pending": true}
 ]}
"""

@Test func decodesComponents() throws {
    let book = try OSSNotices.decode(Data(sample.utf8))
    #expect(book.components.count == 2)
    let first = book.components[0]
    #expect(first.name == "serde")
    #expect(first.license == "MIT OR Apache-2.0")
    #expect(first.authors == "A, B")
    #expect(first.repository == "https://example.org/serde")
    #expect(first.id == "serde 1.0.0")
    #expect(book.components[1].pending)
    #expect(book.components[1].repository == nil)
}

@Test func textJoinsReferencedTexts() throws {
    let book = try OSSNotices.decode(Data(sample.utf8))
    let text = book.text(for: book.components[0])
    #expect(text.contains("Permission is hereby granted"))
    #expect(text.contains("Copyright (c) Someone"))
    #expect(book.text(for: book.components[1]).isEmpty)
}

@Test func missingTextIdIsSkipped() throws {
    let json = #"{"texts": {}, "components": [{"name": "a", "version": "1", "license": "MIT", "authors": "", "textIds": ["gone"]}]}"#
    let book = try OSSNotices.decode(Data(json.utf8))
    #expect(book.text(for: book.components[0]).isEmpty)
    #expect(book.components[0].pending == false)
}

@Test func rejectsBadData() {
    #expect(throws: (any Error).self) { try OSSNotices.decode(Data("nope".utf8)) }
}

@Test func bundledListHasRequiredComponents() throws {
    let book = try OSSNotices.bundled()
    let names = Set(book.components.map(\.name))
    for required in ["WebRTC", "Opus", "RNNoise", "mls-rs", "uniffi", "uniffi_core"] {
        #expect(names.contains(required), "missing \(required)")
    }
    #expect(book.components.filter { $0.license == "MPL-2.0" }.count == 8)
}

@Test func everyBundledComponentHasFullText() throws {
    let book = try OSSNotices.bundled()
    for c in book.components {
        #expect(!book.text(for: c).isEmpty, "\(c.name) has no license text")
        #expect(c.textIds.allSatisfy { book.texts[$0] != nil }, "\(c.name) has a dangling text id")
    }
    // MPL-2.0 requires the full text to ship.
    let uniffi = try #require(book.components.first { $0.name == "uniffi" })
    #expect(book.text(for: uniffi).contains("Mozilla Public License Version 2.0"))
}
