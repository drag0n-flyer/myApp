import Foundation
import Testing
@testable import Messaging

/// The server layout (server design 7.4): u16be length, payload, zeros up to the next bucket.
private func serverPad(_ payload: Data) -> Data {
    var out = Data([UInt8(payload.count >> 8), UInt8(payload.count & 0xff)]) + payload
    let bucket = [256, 512, 1024, 2048, 3072].first { $0 >= out.count } ?? out.count
    out.append(Data(count: bucket - out.count))
    return out
}

@Suite("TestPaddingMatchesServer") struct TestPaddingMatchesServer {
    @Test(arguments: [0, 1, 254, 255, 1000])
    func unpadRestoresPayload(length: Int) throws {
        let payload = Data((0..<length).map { UInt8($0 % 251) })
        let padded = serverPad(payload)
        #expect([256, 512, 1024].contains(padded.count))
        #expect(try Padding.unpad(padded) == payload)
    }

    @Test func declaredLengthBeyondBufferThrows() {
        #expect(throws: PaddingError.self) { try Padding.unpad(Data([0x00, 0x10, 1, 2, 3])) }
        #expect(throws: PaddingError.self) { try Padding.unpad(Data([0xff, 0xff])) }
    }

    @Test func tooShortThrows() {
        #expect(throws: PaddingError.self) { try Padding.unpad(Data()) }
        #expect(throws: PaddingError.self) { try Padding.unpad(Data([0x00])) }
    }
}
