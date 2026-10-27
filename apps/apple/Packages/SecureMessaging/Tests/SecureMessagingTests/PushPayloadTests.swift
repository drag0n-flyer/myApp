import Foundation
import Testing
import SecureMessaging

private func padded(_ payload: [UInt8], declaredLength: Int? = nil, size: Int) -> Data {
    let length = declaredLength ?? payload.count
    var bytes = [UInt8(length >> 8 & 0xff), UInt8(length & 0xff)] + payload
    bytes += [UInt8](repeating: 0, count: size - bytes.count)
    return Data(bytes)
}

@Suite struct PushPayloadTests {
    @Test func zeroLengthMeansFetchInbox() throws {
        #expect(try PushPayload.unpad(Data(count: 256)) == nil)
    }

    @Test func payloadIsExtracted() throws {
        let data = padded(Array("hello".utf8), size: 256)
        #expect(data.count == 256)
        #expect(try PushPayload.unpad(data) == Data("hello".utf8))
    }

    @Test func allowedSizesAreExactly256To3072() throws {
        for size in [256, 512, 1024, 2048, 3072] {
            #expect(try PushPayload.unpad(padded(Array("x".utf8), size: size)) == Data("x".utf8))
        }
        for size in [0, 1, 2, 255, 257, 300, 511, 1023, 3071, 3073, 4096] {
            #expect(throws: MLSError.malformed) { try PushPayload.unpad(Data(count: size)) }
        }
    }

    @Test func lengthLongerThanDataIsMalformed() {
        let data = padded([], declaredLength: 300, size: 256)
        #expect(throws: MLSError.malformed) { try PushPayload.unpad(data) }
        // 255 would still fit inside a 256-byte buffer, 255 > 254 does not.
        let tooLong = padded([], declaredLength: 255, size: 256)
        #expect(throws: MLSError.malformed) { try PushPayload.unpad(tooLong) }
    }

    @Test func fullBufferIsAccepted() throws {
        let payload = [UInt8](repeating: 7, count: 254)
        #expect(try PushPayload.unpad(padded(payload, size: 256)) == Data(payload))
    }

    @Test func nonZeroPaddingIsMalformed() {
        var data = padded(Array("hello".utf8), size: 256)
        data[200] = 1
        #expect(throws: MLSError.malformed) { try PushPayload.unpad(data) }
        var empty = Data(count: 256)
        empty[255] = 1
        #expect(throws: MLSError.malformed) { try PushPayload.unpad(empty) }
    }

    @Test func tooShortIsMalformed() {
        #expect(throws: MLSError.malformed) { try PushPayload.unpad(Data()) }
        #expect(throws: MLSError.malformed) { try PushPayload.unpad(Data([0])) }
    }

    @Test func worksOnDataSlices() throws {
        let whole = Data([9, 9]) + padded(Array("hi".utf8), size: 256)
        let slice = whole.dropFirst(2)
        #expect(try PushPayload.unpad(slice) == Data("hi".utf8))
    }
}
