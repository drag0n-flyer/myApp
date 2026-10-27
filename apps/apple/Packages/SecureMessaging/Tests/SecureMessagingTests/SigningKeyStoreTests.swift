import Foundation
import Testing
@testable import SecureMessaging

@Suite struct SigningKeyStoreTests {
    @Test func inMemoryStoreRoundTripsAndCountsSaves() throws {
        let store = InMemorySigningKeyStore()
        #expect(try store.load(identity: "a/1") == nil)
        try store.save(identity: "a/1", publicKey: Data([1, 2]), secretKey: Data([3]))
        let loaded = try #require(try store.load(identity: "a/1"))
        #expect(loaded.publicKey == Data([1, 2]) && loaded.secretKey == Data([3]))
        #expect(store.saveCount == 1)
    }

    @Test func keychainItemEncodingRoundTrips() throws {
        let pub = Data((0..<32).map { UInt8($0) }), sec = Data((0..<64).map { UInt8(255 - $0) })
        let encoded = try SigningKeyEncoding.encode(publicKey: pub, secretKey: sec)
        #expect(Array(encoded.prefix(2)) == [0, 32])
        let decoded = try SigningKeyEncoding.decode(encoded)
        #expect(decoded.publicKey == pub && decoded.secretKey == sec)
    }

    @Test func corruptItemsAreRejected() {
        for bad in [Data(), Data([0]), Data([0, 5, 1, 2]), Data([0, 0, 1, 2]), Data([0, 2, 1, 2])] {
            #expect(throws: MLSError.self) { try SigningKeyEncoding.decode(bad) }
        }
    }
}
