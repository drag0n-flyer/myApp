import Testing
import Foundation
@testable import CallCore

@Suite struct FrameMappingTests {
    @Test func mapsRing() {
        let e = FrameMapping.event(.ring(callID: "c1", fromAccountID: "a", fromDeviceID: "d", video: true, payload: Data([1, 2])))
        #expect(e == .ring(callID: "c1", fromAccountID: "a", fromDeviceID: "d", video: true, payload: Data([1, 2])))
        #expect(FrameMapping.event(.signal(callID: "c1", fromDeviceID: "d", payload: Data([3]))) == .signal(callID: "c1", fromDeviceID: "d", payload: Data([3])))
        #expect(FrameMapping.event(.answered(callID: "c1", deviceID: "d", payload: Data([4]))) == .answered(callID: "c1", deviceID: "d", payload: Data([4])))
        #expect(FrameMapping.event(.ended(callID: "c1", reason: "missed")) == .ended(callID: "c1", reason: "missed"))
        #expect(FrameMapping.event(.other) == nil)
    }

    @Test func inviteFrame() {
        let f = FrameMapping.frame(invite: "c1", to: "bob", video: false, payload: Data([0xfb, 0xff, 0xfe, 0x01]))
        #expect(f.type == "call.invite")
        #expect(Set(f.data.keys) == ["callID", "to", "video", "payload"])
        #expect(f.data["video"] == .bool(false))
        #expect(f.data["payload"] == .string("-__-AQ"))   // url-safe alphabet, no padding
    }

    @Test func turnJSON() throws {
        let json = Data(#"{"iceServers":[{"urls":["stun:h:3478","turn:h:3478?transport=udp"],"username":"1:ab","credential":"c"}],"ttl":43200}"#.utf8)
        #expect(try FrameMapping.iceServers(fromTurnJSON: json) == [IceServer(urls: ["stun:h:3478", "turn:h:3478?transport=udp"], username: "1:ab", credential: "c")])
        #expect(FrameMapping.turnTTL(fromTurnJSON: json) == 43200)
        #expect(throws: FrameMappingError.malformedTurn) { _ = try FrameMapping.iceServers(fromTurnJSON: Data("x".utf8)) }
    }

    @Test func statusAck() {
        #expect(FrameMapping.status(fromAck: Data(#"{"state":"ended","reason":"declined"}"#.utf8)) == .ended(reason: "declined"))
        #expect(FrameMapping.status(fromAck: Data(#"{"state":"ringing"}"#.utf8)) == .ringing)
        #expect(FrameMapping.status(fromAck: Data(#"{"state":"answered"}"#.utf8)) == .answered)
        #expect(FrameMapping.status(fromErrorCode: "not_found") == .unknown)
    }
}
