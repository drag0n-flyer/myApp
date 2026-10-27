import XCTest
import Foundation
@testable import ServerKit

final class LiveFrameTests: XCTestCase {
    private let b64 = Base64URLData(Data([1, 2, 3, 250, 251])).encoded

    func testDecodesEveryEventType() throws {
        let p = Data([1, 2, 3, 250, 251])
        let item = #"{"cursor":7,"kind":"message","conversationID":"cv","seq":3,"epoch":2,"senderAccountID":"a","senderDeviceID":"d","payload":"\#(b64)","sentAt":"2026-10-27T10:00:00.123456789Z"}"#
        guard case .event(.inbox(let got))? = decodeFrame(#"{"type":"inbox","data":\#(item)}"#) else {
            return XCTFail("inbox")
        }
        XCTAssertEqual(got.cursor, 7)
        XCTAssertEqual(got.payload, p)
        XCTAssertEqual(got.seq, 3)

        XCTAssertEqual(decodeFrame(#"{"type":"device.revoked","data":{"deviceID":"d9"}}"#), .event(.deviceRevoked("d9")))
        XCTAssertEqual(
            decodeFrame(#"{"type":"call.ring","data":{"callID":"c","fromAccountID":"a","fromDeviceID":"d","video":true,"payload":"\#(b64)"}}"#),
            .event(.callRing(callID: "c", fromAccountID: "a", fromDeviceID: "d", video: true, payload: p)))
        XCTAssertEqual(
            decodeFrame(#"{"type":"call.answered","data":{"callID":"c","deviceID":"d","payload":"\#(b64)"}}"#),
            .event(.callAnswered(callID: "c", deviceID: "d", payload: p)))
        XCTAssertEqual(
            decodeFrame(#"{"type":"call.signal","data":{"callID":"c","fromDeviceID":"d","payload":"\#(b64)"}}"#),
            .event(.callSignal(callID: "c", fromDeviceID: "d", payload: p)))
        XCTAssertEqual(
            decodeFrame(#"{"type":"call.ended","data":{"callID":"c","reason":"declined"}}"#),
            .event(.callEnded(callID: "c", reason: "declined")))
    }

    func testDecodesAckAndError() throws {
        guard case .ack(let id, let data)? = decodeFrame(#"{"type":"ack","id":"c3","data":{"state":"ringing"}}"#) else {
            return XCTFail("ack")
        }
        XCTAssertEqual(id, "c3")
        let obj = try JSONSerialization.jsonObject(with: XCTUnwrap(data)) as? [String: String]
        XCTAssertEqual(obj, ["state": "ringing"])
        XCTAssertEqual(decodeFrame(#"{"type":"ack","id":"c5"}"#), .ack(id: "c5", data: nil))
        XCTAssertEqual(decodeFrame(#"{"type":"error","id":"c4","data":{"code":"not_found"}}"#),
                       .error(id: "c4", code: "not_found"))
    }

    func testUnknownTypeIgnored() {
        XCTAssertNil(decodeFrame(#"{"type":"presence"}"#))
        XCTAssertNil(decodeFrame("not json"))
    }

    func testEncodesClientFrame() throws {
        let s = try encodeFrame(type: "call.hangup", id: "c1", data: ["callID": "x"])
        XCTAssertEqual(s, #"{"data":{"callID":"x"},"id":"c1","type":"call.hangup"}"#)
    }

    func testBackoffGrowsAndCaps() {
        var b = Backoff(random: { $0.upperBound })
        XCTAssertEqual((0..<8).map { _ in b.next() }, [1, 2, 4, 8, 16, 32, 60, 60])
        b.reset()
        XCTAssertEqual(b.next(), 1)
    }
}
